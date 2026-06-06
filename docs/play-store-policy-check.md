---
layout: default
title: Candela · Play Store Policy Compliance Check
description: Walkthrough of every Play Store policy surface that Candela might trip, with disclosures + rationale pre-drafted.
---

# Play Store Policy Compliance Check

A pass through Google Play's developer policies against Candela's surfaces,
with each potential trigger flagged, the in-app behavior summarized, the
policy disclosure pre-drafted, and (where relevant) a remediation checklist.

> **Source.** [Google Play Developer Program Policies](https://support.google.com/googleplay/android-developer/topic/9858052)
> as of the v1.0 prep cycle (May 2026).

---

## 1. User-generated content (UGC)

### Surface

Candela surfaces text from third-party services that the user has opted
into. Specifically:

- **Magic-link Readability catch-all** (Add fiction → paste any URL). The
  user pastes a URL; Candela runs a Readability extraction on the fetched
  HTML and renders the result. PR #587 added a scheme allowlist that
  restricts the URL to `http://` and `https://` schemes only — no
  `file://`, `content://`, `javascript:`, etc.
- **Royal Road / AO3 / Standard Ebooks / Wikipedia / Wikisource /
  Gutenberg / Hacker News / arXiv / PLOS** — read-only browses of public
  websites; the user picks what to read, Candela fetches it.
- **Notion / Outline / Memory Palace** — user-owned data behind the user's
  own access token.
- **Discord / Slack / Telegram / Matrix** — message feeds, read-only, with
  per-service auth that the user provides.

Candela itself is **not a UGC platform**. Users don't post anything from
inside Candela; there's no in-app feed of user-submitted content; there's
no way for Candela users to share content with each other through
Candela. The "UGC" exposure is "the public web, the user's own data
stores, and chat services where the user is already a member."

### Policy: UGC

[Play UGC policy](https://support.google.com/googleplay/android-developer/answer/9876937)
requires:

1. A policy for content moderation.
2. A mechanism for users to flag objectionable content.
3. A mechanism for users to block other users.

For Candela, these obligations are subtle because Candela isn't *hosting*
UGC — but Play's policy is read by reviewers as "if your app surfaces
content the user might not have generated themselves, you need a reporting
mechanism."

### Disclosure / remediation

| Item | Status | Notes |
| --- | --- | --- |
| UGC platform? | **No, we're a reader** | The app reads content from public web or user-authenticated services. Source is the third-party service's own moderation. |
| Scheme allowlist on magic-link | **Done in PR #587** | http/https only. No `javascript:`, no `file://`, no `content://`. Sufficient — the remaining surface ("any URL on the public web") is the same surface a web browser exposes, which Play accepts. |
| In-app "Report content" affordance | **Add for v1.0** — Settings → About → "Report objectionable content" → mailto with a pre-filled subject including the active fiction's title + URL. Lightweight; no separate web form. | Not yet implemented in the worktree; orchestrator's call whether to wire in this PR or a follow-up. |
| In-app block / mute | **N/A** | No social graph. |
| Defaults that surface family-safe content | **Yes** | TechEmpower Home is the default landing surface in v0.5.51+, library defaults to TechEmpower's Notion-backed resource library — not the Royal Road browse tab. |

**Verdict: pass.** Candela is a reader, not a UGC host. The scheme
allowlist + the Play-required content advisory ("Users interact with
user-generated content — content may vary") is sufficient. The Report-
content mailto is a small UX addition that closes the "reviewer concerned
about UGC" gap with minimal code.

---

## 2. Foreground services

### Surface

Candela declares two foreground service types:

1. `FOREGROUND_SERVICE_MEDIA_PLAYBACK` — the Media3 session powering the
   actual audiobook playback (lock-screen controls, notification with
   transport buttons, ongoing TTS rendering while the screen is off).
2. `FOREGROUND_SERVICE_DATA_SYNC` — used by
   - `ChapterRenderJob` (PR-F / #86), the WorkManager job that pre-renders
     a chapter's PCM into the cache while the user is reading the current
     chapter. This is local synthesis work, not a media playback surface.
   - The tag-sync worker (#520), which sync-merges fiction-tag state with
     InstantDB.

### Policy: Foreground services

[Play foreground services policy](https://support.google.com/googleplay/android-developer/answer/13392821)
requires:
- The service type matches the user-visible workload.
- The service runs only while the user-visible workload is active.
- The user can stop the service if desired.

### Disclosure / remediation

| Service | Type | User-visible | User-stoppable | Notes |
| --- | --- | --- | --- | --- |
| Media3 playback session | `mediaPlayback` | Yes — notification with transport buttons, lock-screen controls, audio playing | Yes — tap Pause / clear from recents / clear notification | Standard audiobook player pattern. Aligns with policy. |
| `ChapterRenderJob` PCM pre-render | `dataSync` | Yes — silent foreground notification "Preparing chapter N" while the worker runs | Yes — user can pause / kill the worker via Settings → Performance | Local synthesis; doesn't fit `mediaPlayback` (no Media3 session), doesn't fit any other type — `dataSync` is the documented closest match per AOSP docs. |
| Tag-sync worker (#520) | `dataSync` | Yes — silent foreground notification "Syncing library" during the burst | Yes — the worker is short-lived (typically <2s); user can disable sync entirely | Brief; runs only when the user has just changed sync state or returned to the app. |

**Reviewer-facing justification** (copy into the Play Console "Foreground
services" justification field if asked):

> Candela uses `mediaPlayback` for its audiobook player session — the
> user starts playback, the foreground service runs while audio plays,
> and the user can pause / stop / clear at any time via the notification
> or lock-screen controls.
>
> Candela uses `dataSync` for two workloads: (1) pre-rendering the next
> chapter's audio into a local cache so playback is gapless across
> chapter transitions, and (2) syncing the user's library state with
> InstantDB when the user has opted into cross-device sync. Both workers
> are user-initiated (start when the user navigates / signs in), are
> visible via a foreground notification, run only as long as the work
> requires, and can be cancelled by the user via the app's Performance
> and Sync settings.

**Verdict: pass.** Both service types are accurately scoped to user-
visible workloads and are user-stoppable.

---

## 3. Telephony / emergency-related content

### Surface

The TechEmpower Home screen's Emergency Help card surfaces three buttons:

- **988** — 988 Suicide & Crisis Lifeline (US)
- **211** — United Way local services (US / Canada)
- **911** — emergency services (US / Canada)

Tapping any of them fires `Intent.ACTION_DIAL` with the number pre-filled.
**Candela never calls `ACTION_CALL`** (which would dial automatically and
need `CALL_PHONE` permission). The user has to tap "Call" in the dialer
themselves.

`<uses-feature android:name="android.hardware.telephony"
android:required="false" />` is declared (#546) so Candela stays
installable on WiFi-only tablets, which can't make calls. On those
devices, the in-app `dialOrSurfaceFallback` helper detects the absence of
`PackageManager.FEATURE_TELEPHONY` at runtime and shows a "this device
can't make calls" dialog with copy-to-clipboard and a web fallback for 211
(`unitedway.org/211`).

### Policy: Emergency content

[Play health misinformation + emergency content policies](https://support.google.com/googleplay/android-developer/answer/9888379):

- Apps that surface emergency / crisis content must do so accurately and
  with the appropriate disclaimers.
- Apps cannot impersonate emergency services or charge for access to
  emergency-service contact info.

Candela doesn't charge, doesn't claim to be an emergency service, and
uses the public, well-known short codes (988 / 211 / 911 are first-party
US numbers). The UX is "open the dialer with a number pre-filled" —
identical to clicking a `tel:` link in a web browser.

### Disclosure / remediation

| Item | Status |
| --- | --- |
| Uses `CALL_PHONE` permission? | **No** — uses `ACTION_DIAL` only, no permission required |
| Auto-places calls? | **No** — user must confirm in the dialer |
| Accurate numbers? | **Yes** — 988, 211, 911 are the documented US/CA short codes |
| Tablet / WiFi-only handling? | **Yes** — runtime check + fallback dialog |
| Disclaimer text in the card? | **Recommend adding for v1.0** — one line "These are US/Canada numbers. For emergencies outside US/Canada, use your local emergency number." |

**Verdict: pass.** The dial-only / no-CALL_PHONE pattern is what Play
explicitly endorses for non-emergency-service apps that want to make
emergency numbers reachable. Adding the geo-disclaimer is a small UX
hardening; not a Play-blocker.

---

## 4. Data Safety form (pre-draft)

The Play Console Data Safety form asks for, per data type: (a) is it
collected? (b) is it shared? (c) is it required to use the app? (d) is it
encrypted in transit? (e) can the user request its deletion?

Pre-drafted answers below. JP enters these in the Play Console at
submission time.

### "Data collected" answers

| Data type | Collected? | Required? | Encrypted in transit? | Deletable? | Purpose |
| --- | --- | --- | --- | --- | --- |
| **Email address** | Yes, **only with optional sync** | No | Yes (HTTPS to InstantDB) | Yes — sign out of sync | Account / sync lookup key |
| **User IDs** | The InstantDB user record ID — internal to InstantDB, not exposed to other users | No | Yes | Yes | Sync record key |
| **Library state** (fiction IDs, reading positions, voice preferences) | Yes, **only with optional sync** | No | Yes | Yes — sign out | App functionality (sync) |
| Crash / diagnostic data | **No** | — | — | — | We don't collect crash data |
| Approximate / precise location | **No** | — | — | — | Never requested |
| Photos / videos / audio | **No** | — | — | — | No camera / mic access |
| Files / docs | **No** | — | — | — | EPUB import reads files via SAF; doesn't send them anywhere |
| Contacts | **No** | — | — | — | Never requested |
| App activity (page views, in-app actions) | **No** | — | — | — | No analytics |
| App info / performance | **No** | — | — | — | No crash reporter |
| Device or other identifiers | **No** | — | — | — | No advertising ID, no device fingerprinting |
| Financial info | **No** | — | — | — | No IAP, no payments |
| Health / fitness | **No** | — | — | — | |
| Messages | **No** (Candela reads Discord/Slack/Matrix/Telegram channels via *user's own credentials* directly to those services; Candela doesn't see or store the messages anywhere we control) | — | — | — | |

### "Data shared" answers

| Sharing case | Disclosed as | Rationale |
| --- | --- | --- |
| Email + library state → InstantDB | **Sharing email + library state with InstantDB** when sync is on | InstantDB is Candela's sync backend; this is data sharing under Play's definition. |
| Sign-in to Discord / Notion / Royal Road / etc. | **Not shared** — user-initiated direct call from their device to that service | Per Play's guidance, when the user enters credentials into a third-party service from the app, that's "the user transmitting data to that service," not the app sharing it. |
| BYOK Azure / Anthropic / OpenAI etc. | **Not shared** — same reasoning | The user provides their own key; Candela is the conduit, not the data steward. |

### "Security practices" answers

- Data encrypted in transit: **Yes** (all HTTPS, enforced by
  `network_security_config.xml`)
- Users can request deletion: **Yes** (sign out of sync deletes the
  record; uninstall deletes on-device data)
- Independent security review: **Not yet** — JP's call whether to commit
  to this; safe to answer No
- Follows Families policy guidelines: **No** — we're a 13+ app with a UGC
  advisory

---

## 5. Permissions audit

Every `<uses-permission>` in `app/src/main/AndroidManifest.xml`, why it's
declared, and the user benefit. (Source: lines 5–19 of the manifest.)

| Permission | Why | User benefit |
| --- | --- | --- |
| `android.permission.INTERNET` | Fetching fiction content from backends; downloading voices; optional sync; BYOK Azure / LLM API calls | Without this, the app is voice-files-only and won't fetch any text — the entire fiction-backend feature set requires it |
| `android.permission.ACCESS_NETWORK_STATE` | Detecting offline vs online for graceful fallbacks (Azure HD → local voice; "you're offline" banner on browse screens) | Better offline UX; no surprise stalls when the network drops mid-fetch |
| `android.permission.WAKE_LOCK` | The TTS engine and the Media3 playback session need a partial wake lock to keep the CPU running during audio synthesis + playback with the screen off | Audiobooks have to keep playing with the screen off — non-negotiable for an audiobook player |
| `android.permission.FOREGROUND_SERVICE` | Required umbrella permission on API 28+ for any foreground service | Required by platform; see specific types below |
| `android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Media3 session foreground service type (API 34+) | Audiobook playback continues with the screen off / app backgrounded |
| `android.permission.FOREGROUND_SERVICE_DATA_SYNC` | ChapterRenderJob + tag-sync worker (API 34+) | Gapless chapter transitions (pre-render); cross-device sync |
| `android.permission.POST_NOTIFICATIONS` | The playback notification (transport buttons + Continue Listening); also used by the WorkManager foreground notifications | Lock-screen and notification-shade transport controls — primary UX for audiobook playback. Without this on API 33+ the user has no transport surface outside the app itself. |
| `android.permission.RECEIVE_BOOT_COMPLETED` | The home-screen widget (#159) needs to redraw on boot if the user has a widget placed; the receiver listens for the broadcast so the widget shows the last-played state when the device finishes booting | Widget shows correct state after reboot rather than a "tap to launch" placeholder |
| `<uses-feature android:hardware.telephony required="false" />` | (not a permission — feature gate) | Keeps app installable on WiFi-only tablets; runtime check before dial |

**Permissions NOT requested** (and why each is intentionally absent):

| Permission | Why we don't ask |
| --- | --- |
| `CALL_PHONE` | Emergency dials use `ACTION_DIAL` (opens dialer, user confirms) — not `ACTION_CALL` |
| `READ_PHONE_STATE` | We don't need device identifiers, IMEI, line type |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | No location features |
| `RECORD_AUDIO` | No mic access (TTS generates; nothing records) |
| `CAMERA` | No camera access |
| `READ_CONTACTS` / `WRITE_CONTACTS` | No contacts use |
| `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` / `MANAGE_EXTERNAL_STORAGE` | EPUB import uses the Storage Access Framework (SAF) — no broad storage permission needed |
| `com.google.android.gms.permission.AD_ID` | No advertising; no ad ID needed |
| `BLUETOOTH_*` | Audio routing is handled by the OS via the standard media session APIs — no direct BT |

**Verdict: pass.** Every requested permission has a clear, user-visible
benefit; no permission is "just in case." The two foreground service types
are scoped to real workloads.

---

## 6. Other policy surfaces (quick pass)

| Policy area | Status | Notes |
| --- | --- | --- |
| **Subscriptions / IAP** | N/A | No IAP, no subscriptions |
| **Real-money gambling** | N/A | Not present |
| **Health misinformation** | Considered | Emergency Help card cites real US/CA short codes; doesn't claim to be an emergency service |
| **Designed for Families** | Out of scope | App is 13+ |
| **News policy** | N/A | Not a news app |
| **VPN policy** | N/A | Not a VPN |
| **Accessibility services** | We don't USE Accessibility Service APIs | (We're accessibility-*friendly*; we don't *consume* the accessibility service) |
| **Device & network abuse** | Clean | No background CPU hogging; foreground services are user-visible and stoppable |
| **Deceptive behavior** | Clean | App does what the listing says |
| **Impersonation** | Clean | We don't pretend to be Audible, Libby, etc. |
| **Open-source license attribution** | Settings → About → Open Source Licenses should list every dep | Verify before submission |
| **Restricted content (sexual / violence / etc.)** | UGC advisory covers it | The default surfaces (TechEmpower Home + library) don't contain restricted content |
| **Spam / minimum functionality** | Clean | Real app, 20+ working backends, meaningful UX |

---

## 7. Pre-submission checklist

- [ ] Release keystore generated (see `release-keystore.md`)
- [ ] AAB built with the release keystore, verified with `apksigner verify --print-certs`
- [ ] Play App Signing enrolled (upload key cert exported & uploaded)
- [ ] Privacy policy live at `https://candela.techempower.org/privacy/`
- [ ] Listing copy finalized (`play-store-listing.md`)
- [ ] Graphics produced (icon, feature graphic, 4–8 phone, 4–8 tablet)
- [ ] Data Safety form completed per section 4 above
- [ ] Content rating questionnaire completed → likely 13+ with UGC advisory
- [ ] Permissions justified per section 5
- [ ] Foreground service types justified per section 2
- [ ] Emergency Help disclaimer added ("US/Canada numbers; use your local
      emergency number elsewhere") — section 3
- [ ] Settings → About → Privacy Policy link added (links to
      `candela.techempower.org/privacy/`)
- [ ] Settings → About → Report objectionable content (mailto) added —
      section 1
- [ ] Settings → About → Open Source Licenses verified complete
- [ ] First internal-testing build uploaded and confirmed launchable on
      a clean install
