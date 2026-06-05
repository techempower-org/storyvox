---
layout: default
title: Candela · Play Store Listing (Draft)
description: Draft Play Store listing copy and assets for Candela v1.0.
---

# Play Store Listing — v1.0 (Draft)

This is the draft copy + asset inventory for Candela's Play Store
submission. **All copy is draft.** JP reviews and picks final wording before
upload. The asset list is a brief for the graphics — Claude does not generate
images; JP (or a future graphics-capable agent) produces the renders.

---

## App identity

- **App name (as published):** `Candela`
- **Package name:** `org.techempower.candela`
- **Default language:** English (United States)
- **Localizations:** English only for v1.0 — see _Localization_ below.
- **Publisher account:** TechEmpower (501(c)(3))
- **Pricing:** Free. No in-app purchases. No subscriptions.
- **Contains ads:** No

---

## Title and descriptions

### App title (50 char max)

Three drafts, ranked. JP picks final:

1. **`Candela · accessible tech help, read aloud`** (44 ch) ✅ recommended — leads with the TechEmpower mission; "read aloud" carries the audiobook angle without jargon.
2. `Candela: Audiobook reader for everything text` (46 ch) — leans audiobook-app, undersells the accessibility / TechEmpower side.
3. `Candela · Free tech guides, read aloud` (39 ch) — tight, leads with the resource library.

> **Constraint check.** Play Store policy now bars "promotional phrases" in
> the title field (no "#1", no "best", no all-caps gimmicks, no emoji).
> All three drafts comply.

### Short description (80 char max)

Three drafts, ranked:

1. **`Free books, tech guides, and accessible help — read aloud by TechEmpower.`** (74 ch) ✅ recommended — concrete benefit, mentions TechEmpower, no jargon.
2. `Neural-voice audiobook player. 21 sources. Free, ad-free, on-device.` (68 ch) — leads with the audiobook angle.
3. `Listen to anything text — tech guides, fiction, wiki, RSS — all on-device.` (74 ch) — leans into "anything text" but loses the TechEmpower hook.

### Full description (4000 char max)

Draft below. Headline + 5 body paragraphs + feature list. Comes in around
2,800 characters; comfortable margin for tuning.

```text
Candela is TechEmpower's free, accessible resource app — read aloud.

Browse free tech guides, find local help by dialing 211, reach a peer-
support Discord with a single tap, and read or listen to everything
through a high-quality neural text-to-speech engine that runs entirely on
your device. No subscription. No ads. No tracking.

Under the hood, Candela is a serious audiobook player for any text you
have access to — twenty-one fiction and reference backends are wired in,
side by side. Browse Royal Road, Archive of Our Own, Standard Ebooks,
Project Gutenberg, Wikipedia, Hacker News, arXiv, PLOS, and more. Open
EPUB files from your device's storage. Connect your Notion workspace,
your Outline wiki, your Memory Palace, your Discord / Slack / Matrix /
Telegram channels, or a Palace Project library card. Share any URL into
Candela from any app — a magic-link reader pulls the readable text and
queues it for narration.

Three on-device neural voice families ship: Piper (compact, dozens of
languages), Kokoro (multi-speaker, brass-warm narration), and KittenTTS
(the lightest tier, designed for slow phones — eight English speakers
share a single 24 MB model). Voices download once, then live offline.
Bring your own Microsoft Azure key for optional cloud-grade voices;
Candela doesn't take a cut, and falls back to your local voice if the
network drops.

Reader view shows the chapter text in EB Garamond on a warm dark theme,
with the spoken sentence highlighted in brass and glided along to match
the voice. Swipe to the audiobook view for a clean cover-and-scrubber
layout. Lock-screen and home-screen widget controls. Sleep timer. Gapless
chapter auto-advance.

Built for slow devices and built for accessibility: cold launch in under
a second on a Galaxy Tab A7 Lite, TalkBack-aware navigation, high-
contrast brass-on-near-black theme that passes WCAG AA, optional reduced
motion, larger touch targets, and an explicit accessibility settings
section. Twelve accessibility audit findings closed in version 0.5.43;
work continues.

FEATURES
• 21 fiction & reference backends — opt in to the ones you want
• 3 on-device neural voice families (Piper, Kokoro, KittenTTS)
• Optional Bring-Your-Own-Key cloud voices (Microsoft Azure)
• Reader view with synced sentence highlighting
• Home-screen widget — Continue Listening + Play/Pause
• Sleep timer, gapless auto-advance, lock-screen controls
• Cross-device sync (optional, email magic-link)
• AI chat per book (Bring-Your-Own-Key — Anthropic, OpenAI, Google, …)
• Wear OS companion with brass-circular scrubber
• Dark and light themes
• TalkBack-friendly; WCAG AA brass-on-near-black contrast
• Free, GPL-3.0, ad-free, no tracking

Candela is built by TechEmpower, a 501(c)(3) nonprofit. The source is
open at github.com/techempower-org/storyvox. Privacy policy:
storyvox.techempower.org/privacy/
```

---

## Categorization

Play Console asks for one primary category + tags / type.

- **Application type:** Apps (not Games)
- **Category:** **Books & Reference** (primary) — most natural fit; users
  searching for audiobook / reader / TTS apps land here.
- **Tags (Play Console suggests these from a controlled list once you pick
  a category):** Audiobooks, Accessibility, Reference, Education
- **Alternative considered: Music & Audio.** Reasons not to: Music & Audio
  competes with Spotify / Audible / podcast players, where Candela's
  "library of free text, read aloud" framing doesn't fit. Books & Reference
  is the home for audiobook readers and text-to-speech apps generally
  (e.g. Speechify, Voice Aloud Reader, Kindle, Libby, Moon+ Reader all sit
  in Books & Reference).

---

## Content rating (IARC)

The Play Console IARC questionnaire produces a content rating from the
operator's answers. Candela's situation:

- **The app itself has no built-in mature content.** Default surfaces are
  TechEmpower's resource library and a neutral library landing.
- **Backends can surface anything.** Royal Road, AO3, Discord channels, the
  magic-link Readability catch-all — any of these can fetch user-supplied
  URLs. The app does not filter, classify, or moderate that content.
- **No social / chat features** built into Candela itself (the Discord /
  Slack / Matrix / Telegram backends are read-only feeds — users browse
  channels they've added; they don't post from inside Candela).
- **No location sharing, no in-app purchases, no gambling, no real-money
  transactions.**

**Recommended answers** for the IARC questionnaire:

| Question | Answer | Why |
| --- | --- | --- |
| Does your app contain or allow access to violence, blood, sexual content, profanity, drug / alcohol / tobacco references, gambling, fear, crude humor? | **Users can access user-generated content from third-party services** — yes | Truthful; the magic-link reader, Royal Road, AO3 all surface third-party content Candela does not moderate. |
| Does the app share users' location with other users? | No | No location features. |
| Does the app allow users to interact with each other? | No | No in-app messaging. |
| Does the app allow users to purchase digital goods? | No | No IAP. |
| Does the app share personal information with third parties? | No | Optional sync sends email to InstantDB, but that's a backend you opt into for yourself, not third-party sharing. |
| Does the app collect / share precise location? | No | |

**Likely rating outcome:** the "user-generated content" answer pushes the
rating to **Teen (13+) / IARC Generic 12+ / PEGI 12** in most regions.
That's the rating Reddit, AO3-companion apps, and other "read what's on the
public web" apps carry — and is the conservative-but-honest answer for
Candela. The Play Store listing should include the standard
"Users interact with user-generated content — content may vary" advisory
that Play Console attaches to any app where that flag is set.

> **Recommendation: ship as 13+ (Teen) with the UGC advisory.** Asking for
> "All ages" would require either filtering user-supplied URLs (which we
> don't) or removing the magic-link feature (which is core), and Play
> Store reviewers would reject the rating.

---

## Required graphics

Play Console requires:

### App icon — High-res 512×512 PNG

- **Source:** the existing Library Nocturne adaptive icon (the brass
  monogram on warm-dark). Render the adaptive icon's foreground + background
  layers at 512×512 with the standard safe-zone padding.
- **No alpha required** (Play requires a square PNG; the launcher itself
  generates the rounded mask).
- **File:** _to be produced by JP / graphics agent. Suggested filename:
  `docs/play-store/icon-512.png`._

### Feature graphic — 1024×500 PNG / JPG

The biggest piece of Play Store storefront real estate; the user sees it
inline on the listing page before they scroll. Must be readable at small
sizes.

**Design brief:**
- **Composition:** Library Nocturne palette (warm-dark base, brass accents)
- **Hero element:** the Candela wordmark in brass, large enough to read on
  a phone-size preview, with a tagline below
- **Tagline candidate:** "Free books, tech guides, and accessible help —
  read aloud."
- **Negative space on the right** for the Play Store's overlaid
  install button (Play Console docs recommend keeping the right ~30% free
  of important content)
- **No screenshots inside the feature graphic** — Play guidelines reject
  feature graphics that just collage screenshots
- **No price / ratings / "Free!" text** — Play guidelines reject
  promotional text in graphics
- **File:** _to be produced. Suggested:
  `docs/play-store/feature-graphic-1024x500.png`._

### Phone screenshots (min 4, max 8, 1080×1920 or 1080×2400)

Six panels recommended. Each should be a clean render with a one-line
caption baked into the design.

| # | Surface | Caption |
| --- | --- | --- |
| 1 | Library tab with TechEmpower hero card + Continue Listening | "Free books, tech guides, and peer support — in one library." |
| 2 | Reader view with sentence highlighted in brass | "Read along while a neural voice does the work." |
| 3 | Voices tab with Piper / Kokoro / KittenTTS picker | "Three on-device voice families. Pick what fits your phone." |
| 4 | Settings → Accessibility | "Built for accessibility. WCAG AA. TalkBack-friendly." |
| 5 | TechEmpower Home with peer-support Discord + 211 + 988 | "Real peer support, one tap away. Dial 211 or 988." |
| 6 | Emergency Help card on TechEmpower Home (light mode, to show dark+light) | "Light or dark. Your call." |

Capture at the 1080×2400 (Tab A7 Lite) or 1080×1920 (older phone) ratio.

### 7-inch tablet screenshots (min 1, max 8, 1200×1920)

Same six surfaces as phone, captured on a 7-inch tablet emulator or the Tab A7
Lite itself (already Candela's primary test device — JP can use the existing
screenshot pipeline).

### 10-inch tablet screenshots (min 1, max 8, 1600×2560)

Same six surfaces, 10-inch tablet emulator. Show the wider grid on the
Library tab (5-col adaptive grid kicks in at 10-inch).

### (Optional) Promotional video — 30s YouTube URL

Skip for v1.0; revisit post-launch.

---

## Categorization metadata

- **Government app?** No
- **Suitable for Designed for Families program?** No (UGC content advisory disqualifies)
- **Suitable for Teacher Approved?** Maybe — apply post-launch once metrics exist; not blocking v1.0.

---

## Data Safety form

A separate pre-draft lives in `play-store-policy-check.md`. The summary:
Candela collects nothing by default; with sync enabled, collects email +
library state, encrypted in transit, deletable by the user.

---

## Privacy policy URL

`https://storyvox.techempower.org/privacy/`

The policy lives at `docs/privacy-policy.md`; Jekyll renders it at
`/privacy/` via the `permalink: /privacy/` front-matter directive. Verify
the URL resolves before Play submission.

---

## Contact

- **Email:** _DRAFT — JP to fill in_ (`claude2@techempower.org` or
  `jp@jphein.com`)
- **Website:** `https://storyvox.techempower.org`
- **Phone:** Play Console requires a phone number on the developer profile,
  not on the listing. JP's call which number to use (TechEmpower's main, or
  a personal number).

---

## Localization

**English (US) only for v1.0.** Reasons:

1. The TTS engines themselves are language-agnostic — Piper carries dozens
   of languages, Kokoro has multi-language speakers, KittenTTS is en-US
   only. Users in non-English locales can install Candela and add their
   own language's voice via the Voices tab; the **UI** is what's English-
   only.
2. Internal translation review is a v1.1+ workstream. Shipping an
   auto-translated UI before review would land us with awkward strings in
   accessibility surfaces — bad for the very users Candela is built for.
3. The Play Store listing copy is in English-only too for v1.0; localized
   listings can roll out per-language as translations land.

The Play Console "Listing translations" UI lets us add languages
incrementally post-launch without re-submitting.

---

## Pre-launch checklist (Play Console)

- [ ] Developer account verified (organization, paid $25 fee, ID verified)
- [ ] App created with package `org.techempower.candela`
- [ ] Play App Signing enrolled — upload the release keystore's PUBLIC
      certificate (`keytool -export -rfc` output)
- [ ] First internal-testing AAB uploaded and accepted
- [ ] Listing copy filled in from this doc (JP-edited)
- [ ] Graphics uploaded (icon, feature graphic, 4–8 phone, 4–8 tablet)
- [ ] Privacy policy URL resolves at the address listed
- [ ] Data Safety form completed (see policy-check doc)
- [ ] Content rating questionnaire completed → likely 13+
- [ ] Target audience: 13+ (matches content rating)
- [ ] Ads: No
- [ ] Government app: No
- [ ] Sensitive permissions (`POST_NOTIFICATIONS`, foreground service
      types) justified inline at the Play Console permissions screen — copy
      lives in `play-store-policy-check.md`
- [ ] First internal-testing → closed-testing → production rollout plan
      drafted (suggest 7-day internal → 14-day closed (JP + a handful of
      early-access users) → staged rollout starting at 5%)

---

## Post-launch hooks for the listing

Things to wire into the Candela UI as part of v1.0:

- **Settings → About** → "Privacy Policy" link → `https://storyvox.techempower.org/privacy/`
- **Settings → About** → "Open Source Licenses" (already present, verify it lists every dep)
- **Settings → About** → "Rate Candela" → `market://details?id=org.techempower.candela`
- **Settings → About** → "What's New" — pull from the latest release notes
- **First-run sheet** — one panel mentioning the privacy posture ("nothing
  leaves your device unless you turn on a feature that needs the network").
  Keep it short; users are here for the audiobook, not a privacy lecture.
