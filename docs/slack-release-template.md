# Candela release announcement — Slack template

Candela is [TechEmpower](https://techempower.org)'s accessible resource
app, built on a Library Nocturne brass-on-warm-dark engine. Every
release announcement to `#storyvox` leads with TechEmpower's mission
framing first ("Technology for All. Access Made Easy.") and the
engineering capability framing second — the brass + candle visual
voice stays, the copy emphasis shifts so the announcement reads as a
TechEmpower release rather than a generic audiobook-engine release.

This file is the canonical template — both the *shape* of the post
and the *vocabulary* that keeps posts visually coherent
release-over-release.

Source of truth for the deterministic sigil name lives at
[`app/src/main/kotlin/in/jphe/storyvox/sigil/Sigil.kt`](../app/src/main/kotlin/in/jphe/storyvox/sigil/Sigil.kt).

---

## The template

Mustache-style `{{VARS}}` get filled in per release. The output goes via
`mcp__claude_ai_Slack__slack_send_message_draft` to channel
`#storyvox` (id `C0B2SGPERE0`) — drafted, not sent, so JP can review
the wording before broadcasting.

```
:candle: *Candela {{VERSION}}* — {{TAGLINE}}
✦  _{{SIGIL_NAME}}_  ✦

> {{POETIC_LINE}}

_TechEmpower — Technology for All. Access Made Easy._ ([techempower.org](https://techempower.org) · [Discord](https://discord.gg/j3SVttxw7k) · [Donate](https://techempower.org/donate))

*What's new*
{{FEATURES_BLOCK}}

*Under the hood*
{{ENGINEERING_BLOCK}}

*Install*
:tablet: Direct APK → {{APK_URL}}
:scroll: Release notes → {{RELEASE_URL}}
:fast_forward: Diff → {{COMPARE_URL}}

{{TABLET_VERIFICATION_LINE}}

——
:hammer_and_wrench:  realm `{{REALM}}`  ·  built `{{BUILT_LOCAL}}`  ·  commit `{{SIGIL_HASH}}`{{DIRTY_FLAG}}
```

**Mission framing line** (the `_TechEmpower — ..._` italic row) sits
between the poetic-line and the features block on every release post.
It's a consistent identifier that TechEmpower's mission is the
default frame; new features, no matter how engineering-flavoured,
sit underneath that anchor.

### Variable reference

| Variable | Where it comes from | Example |
|----------|---------------------|---------|
| `{{VERSION}}` | `app/build.gradle.kts` → `versionName` | `v0.5.12` |
| `{{TAGLINE}}` | author's choice — one short clause | `cloud sync foundation, shelves, history & resume` |
| `{{SIGIL_NAME}}` | `Sigil.nameFor(SIGIL_HASH)` (deterministic — see Sigil.kt). Strip the hash suffix; it goes in the footer. | `Infernal Dominion` |
| `{{POETIC_LINE}}` | one-line theme — borrows from past milestone style. Optional but encouraged. | `Cloud-bound at last, with the right places to put a book and the right way to find it again.` |
| `{{FEATURES_BLOCK}}` | bullets — one feature per line, with curated emoji (see palette below) | see worked example |
| `{{ENGINEERING_BLOCK}}` | bullets — schema bumps, infra, migrations, security work | see worked example |
| `{{APK_URL}}` | from `gh release view {{VERSION}} --json assets` | `https://github.com/techempower-org/storyvox/releases/download/v0.5.12/storyvox-v0.5.12.apk` |
| `{{RELEASE_URL}}` | `https://github.com/techempower-org/storyvox/releases/tag/{{VERSION}}` | — |
| `{{COMPARE_URL}}` | `https://github.com/techempower-org/storyvox/compare/{{PREV}}...{{VERSION}}` | — |
| `{{TABLET_VERIFICATION_LINE}}` | states which device verified — empty if not installed yet | `_Installed on R83W80CAFZB · clean launch, no migration crash._` |
| `{{REALM}}` | from `BuildConfig.SIGIL_REALM` | `fantasy` |
| `{{BUILT_LOCAL}}` | from `BuildConfig.SIGIL_BUILT`, formatted local | `2026-05-13 06:50 PDT` |
| `{{SIGIL_HASH}}` | from `BuildConfig.SIGIL_HASH` (8-char short) | `73549ff0` |
| `{{DIRTY_FLAG}}` | `  · :warning: dirty` if `SIGIL_DIRTY`, else empty | empty for clean releases |

### Emoji palette — keep it consistent

Pick from this curated vocabulary so the feature feed reads as a coherent
typology and not an emoji soup. Each emoji has one job.

| Domain | Emoji | When to use |
|--------|-------|-------------|
| Theme / framing | `:candle:` | release header — Library Nocturne candlelight |
| Sigil / build provenance | `:hammer_and_wrench:` | footer line w/ realm + hash + built |
| Library, shelves | `:bookmark_tabs:` | shelves, collections, sorting |
| Story content | `:book:` `:books:` | new backend, fiction surface |
| History, time | `:hourglass_flowing_sand:` | reading history, recents |
| Cloud sync | `:cloud:` | InstantDB, sync, magic-code |
| AI / LLM | `:crystal_ball:` | chat, summaries, dictionary |
| Voice / TTS | `:microphone:` | voice library, providers, Azure |
| Audio playback | `:headphones:` | player, buffering, controls |
| Brass / UI polish | `:sparkles:` | "magical" feature, animation, theming |
| Performance | `:zap:` | startup, scroll, render speed |
| Security / privacy | `:lock:` | crypto, PBKDF2, encrypted prefs |
| Bug fix | `:bug:` | reactive fixes |
| Infra / CI | `:tools:` | runners, workflows, build system |
| Tests / quality | `:test_tube:` | new migrations, coverage |
| Docs / release notes | `:scroll:` | CHANGELOG, README, release |
| Navigation / motion | `:fast_forward:` | nav, indicator, transitions |
| Settings / configuration | `:gear:` | settings, defaults, debug |
| Milestones | `:tada:` | x.0.0, x.5.0, anniversaries |
| Tablet verification | `:tablet:` | "installed on" line |
| Dirty / warning | `:warning:` | dirty builds, beta caveats |

**Rule of thumb**: one emoji per bullet, leading. Don't sprinkle a
second one mid-sentence — it dilutes the visual rhythm. Headers use
`*Bold*` (Slack-style asterisks); `**bold**` works too because the
draft tool accepts standard markdown.

---

## Worked example — v0.5.12

This is what the draft tool produced for the four-PR bundle that landed
on 2026-05-13. Reproduce by passing the rendered text below to
`slack_send_message_draft` with `channel_id="C0B2SGPERE0"`.

```
:candle: *Candela v0.5.12* — cloud sync foundation, shelves, history & resume
✦  _Infernal Dominion_  ✦

> Cloud-bound at last, with the right places to put a book and the right way to find it again.

_TechEmpower — Technology for All. Access Made Easy._ (techempower.org · discord.gg/j3SVttxw7k · techempower.org/donate)

*What's new*
:cloud: *InstantDB cloud sync foundation* (#360) — new `core-sync` module syncing library, follows, playback positions, bookmarks, pronunciation dictionary, and secrets. Magic-code sign-in, per-syncer conflict policies, 24h tombstone TTL so re-adds propagate.
:bookmark_tabs: *Library shelves — Reading / Read / Wishlist* (#362, closes #116) — chip-row filter above the grid on the All sub-tab; long-press a cover to manage shelves; per-shelf empty states.
:hourglass_flowing_sand: *Reading history sub-tab* (#363, closes #158) — Library is now All / Reading / History. History is a chronological chapter-open feed, most-recent first, with relative-time labels and forever retention. Tap a row to open the reader (no auto-play).
:sparkles: *Magical resume prompt* (#361) — Playing tab now surfaces a brass-themed Library Nocturne prompt when the user paused mid-chapter.

*Under the hood*
:lock: PBKDF2 bumped 100k → 600k rounds (NIST 2024 / OWASP) for user-derived sync keys; envelope format v2 drops the cosmetic salt.
:test_tube: Room schema → v6 (1→2→3→4→5→6, all additive). `chapter_history` rebased v5→v6 at merge time because shelves claimed v5 first; renumber kdoc'd in the migration file so the next person hits the same paved path.
:scroll: Introduces `CHANGELOG.md` — no more "see git log".
:tools: Self-hosted runner on katana still doing the CI work while jphein's hosted Actions remain capped through 2026-06-01.

*Install*
:tablet: Direct APK → https://github.com/techempower-org/storyvox/releases/download/v0.5.12/storyvox-v0.5.12.apk
:scroll: Release notes → https://github.com/techempower-org/storyvox/releases/tag/v0.5.12
:fast_forward: Diff → https://github.com/techempower-org/storyvox/compare/v0.5.11...v0.5.12

_Installed on R83W80CAFZB · clean launch, no migration crash._

——
:hammer_and_wrench:  realm `fantasy`  ·  built `2026-05-13 06:50 PDT`  ·  commit `73549ff0`
```

---

## Authoring checklist

Before drafting a release post, walk this:

- [ ] `app/build.gradle.kts` versionName matches the git tag being announced
- [ ] CHANGELOG.md has an entry for this version (Added / Changed / Fixed sections)
- [ ] `gh release view {{VERSION}} --json assets` confirms the APK is attached (self-hosted runner must have finished)
- [ ] Sigil computed from short `SIGIL_HASH` (8 chars from `git rev-parse --short=8 HEAD` on the tagged commit)
- [ ] APK installed on R83W80CAFZB and the app launches without a migration crash (logcat scanned)
- [ ] Feature emojis pulled from the palette above — no off-roster substitutions
- [ ] The post acknowledges any open caveats (rate limits, capped Actions, known regressions) so the channel isn't surprised later

The autonomy boundary that's worth respecting: the draft never auto-sends
(per the [[feedback_slack_announce_every_release]] memory rule). JP
reads, optionally edits the wording, then sends.

---

## Generating the sigil quickly

The 8-char short hash off `HEAD` plus the algorithm in `Sigil.kt` is all
that's needed. Python one-liner for ad-hoc computation:

```bash
python3 -c "
HASH = '$(git rev-parse --short=8 HEAD)'
seed = int(HASH, 16) & 0xFFFFFFFF
if seed & 0x80000000: seed -= 0x100000000
adj = ['Arcane','Blazing','Celestial','Draconic','Eldritch','Fabled','Gilded','Hallowed','Infernal','Jade','Kindled','Luminous','Mythic','Noble','Obsidian','Primal','Radiant','Spectral','Twilight','Valiant']
noun = ['Aegis','Beacon','Crown','Dominion','Ember','Forge','Grimoire','Herald','Insignia','Jewel','Keystone','Lantern','Monolith','Nexus','Oracle','Pinnacle','Quartz','Relic','Sigil','Throne']
print(f'{adj[seed % 20]} {noun[(seed >> 8) % 20]} · {HASH}')
"
```

Same algorithm Sigil.kt runs in-app; produces identical output for the
same commit.
