# Cloud sync (InstantDB)

Storyvox v0.6 introduces cloud sync via [InstantDB](https://www.instantdb.com).
The goal: a reinstall (or accidental `adb uninstall`) doesn't wipe your
library, follows, reading positions, bookmarks, pronunciation dictionary,
or your AI keys. Sign in on a new device → everything comes back.

This doc covers the architecture, what syncs vs. doesn't, conflict rules,
and the recovery story. The implementation lives in `:core-sync`.

## Quick story

```
First device                 InstantDB                 Second device
============                  =========                  ============
sign in (magic code) ───────► refresh token ──────────► sign in (magic code)
push local state ───────────► row store
                              │
                              └───────────────────────► pull on launch
                                                        local state restored
mutate (add fiction) ───────► transact
                              │
                              └───── (eventual pull)──► local state catches up
```

## Architecture

```
:core-sync (Kotlin / Hilt)
├── client/
│   ├── InstantClient        — auth-plane HTTP (send/verify magic code,
│   │                          verify refresh token, sign out)
│   ├── InstantSession       — refresh token persistence (EncryptedSharedPreferences)
│   ├── InstantBackend       — data-plane seam (fetch + upsert per (entity, id))
│   ├── DisabledBackend      — used when INSTANTDB_APP_ID is the placeholder
│   ├── FakeInstantBackend   — in-memory; tests + offline-only mode
│   └── HttpInstantBackend   — production; HTTP /admin/query + /admin/transact
│                              (as-token impersonation, no admin secret shipped)
├── crypto/
│   └── UserDerivedKey       — PBKDF2 + AES-GCM for secrets-before-push
├── coordinator/
│   ├── SyncCoordinator      — scheduler over the multibound Set<Syncer>
│   ├── Syncer               — interface every per-domain syncer implements
│   ├── ConflictPolicies     — LWW, union-with-tombstones, max-scalar
│   └── TombstoneStore       — per-domain deletion log (DataStore-backed)
├── domain/
│   ├── SetSyncer            — reusable union-with-tombstones syncer
│   ├── LwwBlobSyncer        — reusable last-write-wins blob syncer
│   ├── LibrarySyncer        — Fiction rows the user added
│   ├── FollowsSyncer        — Fictions followed remotely
│   ├── PlaybackPositionSyncer — per-fiction position (max-stamped)
│   ├── BookmarksSyncer      — per-chapter bookmarkCharOffset (LWW)
│   ├── PronunciationDictSyncer — user's pronunciation overrides (LWW)
│   └── SecretsSyncer        — encrypted secrets bundle (LWW, AES-GCM)
└── di/
    └── SyncModule           — Hilt multibinds for Set<Syncer>
```

The auth UI lives in `:feature/sync/` (`SyncAuthScreen` + `SyncAuthViewModel`),
keeping the Library Nocturne aesthetic. `:app/StoryvoxApp.kt` calls
`SyncCoordinator.initialize()` on cold start.

## What syncs

| Domain | Storage | Conflict strategy | Encrypted? |
|---|---|---|---|
| Library | Fiction.inLibrary | union + tombstones | no |
| Follows | Fiction.followedRemotely | union + tombstones | no |
| Reading positions | playback_position | max(updatedAt) per fiction | no |
| Chapter bookmarks (#121) | Chapter.bookmarkCharOffset | LWW per chapter (blob) | no |
| Pronunciation dictionary (#135) | settings.PronunciationDict | LWW (single blob) | no |
| Secrets (LLM keys, cookies, tokens) | EncryptedSharedPreferences | LWW (single blob) | **yes — AES-GCM, user passphrase** |

## What does NOT sync

- **Fiction metadata bodies** (title, author, cover URL, tag list). Each
  device re-fetches metadata from the originating source — Royal Road,
  GitHub, Outline, etc. Pushing this row content would balloon the wire
  format and create cross-device "who wins on a stale metadata fetch"
  races. The synced set membership ("this user added fictionId=X")
  triggers a metadata fetch on the second device.
- **Chapter bodies** (`Chapter.htmlBody`, `plainBody`). Re-fetchable
  from the source; multi-MB per chapter. We sync the *reading position*
  inside a chapter, not the chapter text.
- **Real-time playback position**. The playback layer updates 4-60 Hz —
  we'd burn bandwidth and battery pushing every frame. Position is
  synced on chapter change, sleep-timer fire, and periodic checkpoints
  (the existing PlaybackPosition row, which already debounces).
- **Voice model `.onnx` blobs**. 14 MB – 330 MB each, slow to push,
  always re-downloadable from the `voices-v2` GitHub release. We
  could sync the *list of installed voices* in a follow-up so a new
  device pre-downloads the user's favorites; not in v1.
- **PCM render cache**. Device-local performance optimization.
- **LLM chat sessions & messages**. Listed as out-of-scope for v1 in
  the PR — the entity set is large and the value-density is lower
  than library/positions/secrets. Mechanical follow-up: a new
  `LlmSessionsSyncer` slotting into the existing multibind.
- **App settings (Proto DataStore preferences)**. ~80 keys with
  enum and float and Boolean values. v1 ships the syncer seam but
  not the per-preference wiring — adding "sync theme override"
  alone is a 5-LOC repository touch + a syncer line. Follow-up PR
  lands the rest.
- **Auth state row (`auth_cookie`)**. The actual cookie/token is in
  EncryptedSharedPreferences and rides the SecretsSyncer; the
  `auth_cookie` Room row is a derived projection rebuilt from the
  decrypted secrets on first launch.

## Conflict rules

The brief's spec is implemented in `coordinator/ConflictPolicies.kt`:

- **LWW for scalars**: higher `updatedAt` wins; ties go to local.
- **Union with tombstones for sets**: `(local ∪ remote) − tombstones`.
  Deletion is sticky until the server-side membership reflects the
  tombstone, at which point the local tombstone can be forgotten.
- **Max for reading position**: the listener "generally only moves
  forward," so the server keeps the maximum seen across devices.
  Implemented as max-stamped — newer updatedAt wins; tie-break by
  higher charOffset so two devices simultaneously updating with
  clock skew don't lose the listener's actual position.

A corollary trade-off: a deliberate **rewind across chapters** loses
to a later forward listen on another device. Mitigation: the
chapter-change action carries its own LWW updatedAt, so an
intentional chapter pick beats a max. Rewinding within a chapter
(charOffset down) is handled by the next position write, which
restamps and wins on the device's next sync.

## Encryption story for secrets

All secrets (LLM API keys, Royal Road cookies, GitHub PAT, Outline
tokens) live locally in `EncryptedSharedPreferences` and are
encrypted *client-side again* before push.

- KDF: PBKDF2-HMAC-SHA256, 100k iterations, 16-byte salt.
- Cipher: AES-256-GCM.
- Envelope: `v1:<salt-b64>:<iv-b64>:<ct-b64>`.
- Salt: deterministically derived from the user id (so a new device
  with the same passphrase + userId derives the same key).

The user must set a **sync passphrase** in `Settings → Account → Sync`
before secrets sync. If they don't, the SecretsSyncer is a no-op and
the user is warned that their secrets won't ride the sync layer.

**Recovery caveat**: forget the passphrase, lose the secrets. The
*rest* of the sync layer (library, positions, bookmarks) still
recovers fine — they're not encrypted. This is by design: we'd
rather force a re-paste of an API key than ship a key-escrow
service or store the master key plaintext anywhere.

## Recovery story

The four-line user journey:

1. Get a new phone, install storyvox.
2. Open Settings → Account → Sync.
3. Enter the same email, paste the magic code from your inbox.
4. (Optional, for secrets): enter the sync passphrase.

What happens:

- `SyncCoordinator.initialize()` validates the persisted refresh
  token; if it's fresh and valid, every syncer runs `pull`.
- Library entries reappear (set membership). Metadata fetches
  lazily on first browse.
- Reading positions land in the playback DAO — the Library "Resume"
  tile shows where you were.
- Bookmarks restore.
- Pronunciation dictionary restores.
- With the passphrase entered, LLM keys / RR cookies / GitHub PAT
  restore — chat works, RR sign-in is honored, GitHub OAuth state
  is back.

## Setup (for the developer / JP)

One-time, in [the InstantDB dashboard](https://www.instantdb.com):

1. Create an account (magic-link, no password to remember).
2. Create a new app. Note the **App ID** (UUID-shaped).
3. In the storyvox repo's `local.properties` (gitignored), add:
   ```
   INSTANTDB_APP_ID=00000000-0000-0000-0000-000000000000
   ```
4. Build. The `:core-sync` Gradle script reads `local.properties` at
   configure time and bakes the value into `BuildConfig.INSTANTDB_APP_ID`.
5. Without that property the build still succeeds — `INSTANTDB_APP_ID`
   defaults to the literal `"PLACEHOLDER"` sentinel and the sync DI
   graph resolves to `DisabledBackend`. The app builds, the sign-in
   screen shows a "Sync isn't configured for this build" message,
   nothing crashes.
6. Permissions / schema: InstantDB's schema is dynamic — the first
   `transact` creates the entity shapes implicitly. No DB migration
   script is needed. If you want to lock down write permissions to
   "only the signed-in user owns their own rows," add a rule in the
   dashboard like:
   ```
   { "blobs": { "allow": { "$default": "auth.id == data.userId" } } }
   ```
   For v1 we ship without permission rules — the row IDs already embed
   the user id, so a careful client only writes to its own row, but a
   malicious client could overwrite another user's. Rule hardening is
   tracked as a follow-up issue.

## Wire format

InstantDB schema is dynamic. We use three entities:

- `sets` — rows for the union-with-tombstone set domains (library,
  follows). Row id is `<domain>:<userId>`. Payload is a JSON map
  `{ "members": [...], "tombstones": [...], "updatedAt": ... }`.
- `blobs` — rows for LWW blob domains (pronunciation dict, bookmarks,
  secrets envelope, future settings). Row id is `<domain>:<userId>`.
  Payload is the domain's encoded blob string. updatedAt is a
  separate column.
- `positions` — single row per user, containing every per-fiction
  position. Row id is `positions:<userId>`. Payload is a JSON map of
  `fictionId → { chapterId, charOffset, ... }`.

The actual transact step format mirrors the InstantDB JS SDK:
```
["update", "blobs", "<domain>:<userId>", { "payload": "...", "updatedAt": 12345 }]
```

## Known limitations (v1)

These are documented gaps, not bugs:

- **Push-on-mutate is not wired yet.** Today the coordinator's
  `requestPush(domain)` exists but the per-repository writes don't
  call it. Follow-up: wire `requestPush("library")` into
  `FictionRepositoryImpl.addToLibrary()` etc. (5 LOC per domain).
  Mitigation today: a push happens on next sign-in and on cold
  start — so a reinstall recovery still works; you just may lose
  the last write made offline if the device dies before the next
  cold start.
- **No real-time pull.** v1 talks to `/admin/query` and `/admin/transact`
  as one-shot requests on each sync round. v2 will hoist this onto a
  long-lived WebSocket against `/runtime/session` and subscribe to
  `add-query`, surfacing changes from other devices in real time. The
  first attempt at the WS path (v0.5.41) shipped broken — see issue
  #691 — because it parsed the `datalog-result`/EAV envelope as the
  materialised entity-keyed shape; the admin HTTP API returns the
  shape we actually want, at the cost of one TCP handshake per round.
- **Settings sync is stubbed.** The seam exists; per-preference
  wiring lands in a follow-up.
- **LLM session sync is deferred.** Same story as settings.
- **No permission rules on the InstantDB app.** See setup step 6.
- **Voice-blob inventory not synced.** Future work.

## Testing

`./gradlew :core-sync:testDebugUnitTest` runs:

- `ConflictPoliciesTest` — LWW, union-with-tombstones, max-stamped.
- `UserDerivedKeyTest` — KDF determinism, AES-GCM roundtrip, envelope
  parsing, IV freshness.
- `InstantClientTest` — magic-code auth happy path against a fake
  transport (asserts the wire format matches the InstantDB runtime).
- `SetSyncerTest` — first-time push, tombstone propagation, union of
  concurrent adds.
- `LwwBlobSyncerTest` — newer-side-wins on both directions.
