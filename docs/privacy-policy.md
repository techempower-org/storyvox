---
layout: default
title: Candela · Privacy Policy
description: Candela's privacy policy. Plain-language summary: nothing leaves your device without your explicit action, no analytics, no ads, no tracking.
permalink: /privacy/
---

# Privacy Policy

> **TL;DR.** Candela doesn't ship analytics, advertising, or tracking code.
> Nothing leaves your device unless you explicitly turn on a feature that
> requires the network (signing in for sync, browsing a fiction backend,
> downloading a voice). Uninstalling the app deletes everything stored
> locally; signing out of sync deletes the cloud-side copy too.

**Effective date:** _draft — to be set at v1.0 ship date_  
**App:** Candela (`org.techempower.candela`)  
**Publisher:** TechEmpower (501(c)(3) nonprofit, operating Candela)  
**Maintainer:** JP Hein (`jp@jphein.com`)  
**Contact for privacy questions:** _DRAFT — JP to confirm. Candidates:
`claude2@techempower.org` or `jp@jphein.com`._

---

## 1. What Candela does

Candela is an Android app that reads text aloud using on-device neural
text-to-speech voices. It fetches text from the fiction backends you opt into
— Royal Road, RSS feeds, Notion, Wikipedia, EPUBs on your device, and a few
dozen more — and renders that text into audio. It is free, ad-free,
subscription-free, and open-source (GPL-3.0).

---

## 2. What we collect

**By default, Candela collects nothing.** Out of the box, the app has no
account, no telemetry, no crash reporter, no analytics SDK, and no
advertising identifier access. You can install and use it offline (with
already-downloaded voices) and nothing about your usage leaves the device.

A few features collect or transmit data **only when you turn them on**:

### 2.1 Sync (optional)

If you opt into cross-device sync (Settings → Sync), Candela prompts you
for an email address and sends you a magic sign-in code. The address and
your synced library state — what fictions you've added, current reading
position, voice preferences, settings — are stored in
[InstantDB](https://instantdb.com), the third-party real-time database
Candela uses for sync. Your email is the lookup key for your record. The
content of fictions themselves is **not** synced — only the references
(URLs / IDs) and the metadata (position, voice choice).

You can disable sync at any time and your record is deleted from InstantDB
on next sync-off. Uninstalling the app also deletes the local cache; the
InstantDB record remains until you sign out from another device or contact
us to delete it (see _Your rights_ below).

### 2.2 Fiction backends (your choice, per backend)

Candela connects to the public APIs and websites of the fiction backends
you enable. Each is opt-in via Settings → Plugins. The list, in v0.5.x:
Royal Road, GitHub, RSS, Outline, Memory Palace, Project Gutenberg, AO3,
Standard Ebooks, Wikipedia, Wikisource, Radio (Radio Browser API),
Notion, Hacker News, arXiv, PLOS, Discord, Telegram, Palace Project, Slack,
Matrix, and a generic "Readability" catch-all for arbitrary URLs you share
into the app.

When you browse one of these backends, Candela sends HTTPS requests to
that backend's servers exactly the way a web browser would. **Candela does
not act as an intermediary** — your requests go directly to (for example)
royalroad.com from your device. The backend operator sees a normal browser
request, with an IP address and possibly a User-Agent header. Candela does
not send your email, your sync state, or any identifying token from
Candela along with these requests.

For backends that require sign-in (Royal Road follows, AO3 marked-for-later,
GitHub OAuth Device Flow, Discord/Slack/Matrix/Telegram bot tokens, Notion
integration tokens, Azure HD speech key), credentials live on-device and
are sent only to that backend's own API. **Candela servers (there aren't
any beyond the sync database) never see your fiction-backend credentials.**

WebView cookies from AO3 and Royal Road logins are stored in Android's
`EncryptedSharedPreferences` on your device and never leave it.

### 2.3 Voice downloads

The first time you pick a voice, Candela downloads it from GitHub Releases
under the `voices-v2` tag of the Candela repository (or from the on-device
KittenTTS bundle, which is in-tree). The voice file itself is hosted on
GitHub's CDN; the download request goes directly to
`github.com` / `objects.githubusercontent.com`. GitHub can see your IP for
the duration of the download.

After the voice is on-device, all synthesis runs locally. No audio is sent
to a server. There are no per-character or per-minute charges.

### 2.4 Optional cloud TTS (Azure HD, BYOK)

If you provide your own Microsoft Azure Speech key (Settings → Voices →
Azure HD), Candela sends synthesis requests directly to Azure's TTS
endpoint with the text-to-be-spoken and your key. **Candela doesn't proxy,
log, or mediate this traffic.** Microsoft's privacy policy applies to that
request. If you don't add an Azure key, no Azure traffic happens.

### 2.5 AI chat (BYOK)

The "Chat with this book" feature requires a Large Language Model provider
key (OpenAI, Anthropic, Google, etc.). When you ask a question, Candela
sends the question + the configured grounding (current sentence / chapter /
whole book) directly to the provider's API with **your** API key.
**Candela doesn't proxy, log, or charge** for this traffic. The provider's
privacy policy applies.

### 2.6 Anthropic Teams sign-in (optional)

Candela supports signing into Anthropic Teams via Chrome Custom Tabs OAuth
for users on an Anthropic Teams plan who'd rather not paste an API key.
Sign-in lands a token in `EncryptedSharedPreferences` on-device. The token
goes to Anthropic when you chat; nowhere else.

### 2.7 Emergency Help card

The TechEmpower Home screen surfaces three emergency-resource shortcuts:
**988** (988 Suicide & Crisis Lifeline, US), **211** (United Way local
services, US/Canada), and **911** (emergency, US/Canada). Tapping a shortcut
opens the device's dialer with the number pre-filled — **Candela does not
place the call automatically**, you have to tap "Call" in the dialer. No
data about whether you tapped a shortcut is collected or transmitted by
Candela.

---

## 3. What we do NOT collect

- **No analytics.** No Firebase, no Google Analytics, no Mixpanel, no
  Sentry/Bugsnag, no Crashlytics. No "anonymous usage statistics". No
  fingerprinting.
- **No advertising.** No ad SDKs. No ad IDs requested. The
  `com.google.android.gms.permission.AD_ID` permission is not declared in
  the manifest.
- **No tracking pixels.** No third-party JavaScript loaded into the reader.
- **No location data.** Candela never asks for location permission.
- **No microphone access.** Candela never asks for microphone permission.
  (The TTS engine generates audio; it doesn't record any.)
- **No contacts access.** Candela never reads your contact book.
- **No background data collection.** When the app isn't running, it isn't
  doing anything (with one exception: the now-playing widget reads its own
  cached state to render; no network is performed by the widget).

---

## 4. Third-party services

Candela connects directly to these third parties **only when you opt into a
feature that uses them**. We don't share any data with them; you communicate
with their servers from your device, the same way a web browser would.

| Service | When | What's sent | Their privacy policy |
| --- | --- | --- | --- |
| InstantDB | Sync enabled | Your email, your library state | [instantdb.com/privacy](https://instantdb.com/privacy) |
| GitHub | Voice download, GitHub source, voice catalog fetch | HTTP request (IP visible) | [GitHub privacy](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement) |
| Royal Road / AO3 / etc. | When you browse those backends | Your browser-like request | Their respective policies |
| Microsoft Azure | Only if you add an Azure key | Text-to-speak + your key | [Microsoft Privacy Statement](https://privacy.microsoft.com/en-us/privacystatement) |
| OpenAI / Anthropic / Google AI | Only if you add an LLM key | Your chat messages + your key | Their respective policies |
| Notion | Only if you add a Notion token | Notion API requests | [Notion privacy](https://www.notion.so/Privacy-Policy) |
| Discord / Slack / Telegram / Matrix | Only with explicit token / homeserver setup | Bot-token API calls | Their respective policies |
| Radio Browser API | Radio backend enabled (default off) | Station search queries | [radio-browser.info](https://www.radio-browser.info/) |
| Palace Project (libraries) | Palace backend enabled | OPDS catalog queries | [thepalaceproject.org/privacy](https://thepalaceproject.org/privacy/) |

We do not sell, share, or rent your data. We don't have your data to sell —
it's on your device.

---

## 5. Data retention

- **On-device data** (library, settings, voice files, WebView cookies, BYOK
  tokens, AI chat history) lives in Candela's private app storage. It
  persists until you uninstall the app or clear app data from Android
  Settings. Uninstalling deletes everything.
- **Synced data** (only if sync is enabled) lives in InstantDB until you
  sign out of sync. Signing out of sync deletes your record.
- **Voice downloads** stay in Candela's cache until you delete them
  (Settings → Voices → Manage → Delete).

---

## 6. Children's privacy (COPPA)

Candela is built to be accessible — among other things, that means it has
to work for users who can't or don't want to navigate complex interfaces,
including older children. We do **not** knowingly collect any personal
information from children under 13.

- The default install collects nothing, period.
- The sync feature requires an email address; we don't verify age, but
  children under 13 in the US (and under the analogous local age elsewhere)
  should not enable sync without parental consent per applicable law.
- Fiction backends may surface content not suitable for children. Candela
  does not filter or moderate backend content — that's up to the backend
  operator. Parents managing the app for a child user should disable
  backends with unfiltered user-generated content (Royal Road, AO3,
  Discord, Slack, the magic-link Readability catch-all).

If you believe a child under 13 has provided Candela with personal
information via the sync feature, contact us (see _Your rights_) and we'll
delete the record.

---

## 7. Security

- **On-device encryption.** Sensitive credentials (WebView cookies, BYOK
  API keys, Anthropic Teams OAuth tokens) are stored in Android's
  [`EncryptedSharedPreferences`](https://developer.android.com/reference/androidx/security/crypto/EncryptedSharedPreferences),
  which encrypts at rest with a per-app key bound to the device.
- **HTTPS everywhere.** All network requests use HTTPS. The app's
  `network_security_config.xml` enforces this — cleartext HTTP is rejected
  by the platform.
- **No analytics surface to compromise.** We don't ship analytics SDKs,
  which means there's no analytics endpoint that could be compromised to
  exfiltrate user data.
- **Open source.** Anyone can audit Candela's network behavior at
  [github.com/techempower-org/candela](https://github.com/techempower-org/candela).
  If you spot a privacy bug, file an issue or email us.

---

## 8. Your rights

You have the right to:

- **Know what's stored.** Everything is either on your device (you can
  inspect via Android's Settings → Apps → Candela → Storage) or in your
  InstantDB sync record (you can view via the sync settings screen).
- **Delete your data.** Uninstall the app to delete on-device data; sign
  out of sync to delete the InstantDB record.
- **Export your data.** EPUB export of your library is on the roadmap;
  contact us if you need an export today.
- **Contact us with privacy questions** at the contact address listed at
  the top of this document.

If you're a resident of California (CCPA), the EU (GDPR), or another
jurisdiction with applicable data-protection law, you have additional
rights including the right to request access, correction, or deletion of
your personal data. Contact us and we'll honor those requests within the
statutory timeframe.

---

## 9. Changes to this policy

We'll post material changes to this page with an updated "Effective date"
above. The previous versions are visible in the git history at
`docs/privacy-policy.md` in the Candela repository — the policy itself is
versioned alongside the app.

For substantive changes (new third-party service, new data collected, etc.)
we'll mention the change in the Play Store release notes for the version
that introduces the change, and surface a one-time notice in-app.

---

## 10. Contact

For questions, requests, or concerns about your privacy in Candela:

- **Email:** _DRAFT — JP to fill in: claude2@techempower.org or jp@jphein.com_
- **GitHub issues:** [github.com/techempower-org/candela/issues](https://github.com/techempower-org/candela/issues)
  (public; do not include private information)

Candela is operated by **TechEmpower**, a 501(c)(3) nonprofit. The app is
licensed under the [GNU General Public License v3.0](https://github.com/techempower-org/candela/blob/main/LICENSE).
