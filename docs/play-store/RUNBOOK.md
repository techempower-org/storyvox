# Play Console Upload Runbook

This runbook covers the **one-time** Play Console setup and the **per-release** upload workflow for storyvox. Once the one-time setup is done, future releases are a single `./gradlew publishReleaseBundle` away.

## What's automated (after setup)

After the one-time service-account setup below, this command does the full release:

```bash
./gradlew :app:publishReleaseBundle
```

It uploads the signed AAB to Play Console's Internal Test track as a **draft** (you click "Release" in the Play Console once to publish — that's intentional, see safety notes below).

The plugin is [Triple-T's gradle-play-publisher](https://github.com/Triple-T/gradle-play-publisher). Its config lives in `app/build.gradle.kts` in the `play { }` block, with defaults that match storyvox's release flow:

| Setting | Value | Why |
|---|---|---|
| `track` | `internal` | All uploads land in Internal Test. Promote to Production via Play Console UI or `./gradlew promoteArtifact`. |
| `releaseStatus` | `DRAFT` | Even within Internal Test, the new release stages as a draft. You click "Release" to publish — a half-broken CI run can't auto-ship. |
| `defaultToAppBundles` | `true` | `publishBundle` (AAB) is the canonical path. APK uploads only run if explicitly asked for. |
| `commit` | `false` | Every `publish*` builds up an "edit" on Play Console but doesn't commit it. The explicit `publishApps` task commits. Prevents half-published state. |
| `resolutionStrategy` | `IGNORE` | If a stale edit is hanging around in Play Console, we override it rather than failing. AGP `versionCode` is the source of truth. |

## One-time setup (~15 minutes)

These steps are **inherently manual** — they require browser interaction with Google's admin UI. Do them once; future releases automate.

### 1. Create the Play Console app entry (Play Console UI)

1. Open https://play.google.com/console as TechEmpower-org admin.
2. **Create app** → name "storyvox", default language English (US), app/game = App, free/paid = Free.
3. Accept declarations (developer program policies, US export laws).
4. Fill in the **Set up your app** dashboard items:
   - **App access** → all functionality available without restrictions (no login wall in v1.0).
   - **Content rating** → IARC questionnaire. Target rating: **Teen (13+)** with UGC advisory (the app surfaces user-supplied URLs).
   - **Target audience** → 13+ (matches content rating). NOT primarily child-directed.
   - **News apps** → No.
   - **COVID-19 contact tracing** → No.
   - **Data safety** → declare what storyvox collects (audiobook listening progress synced via InstantDB; no advertising IDs; no personal data sold).
   - **Government apps** → No.
   - **Financial features** → No.
   - **Health** → No.
   - **Ads** → No.
   - **Privacy policy** → URL of TechEmpower's privacy policy.
5. **Store listing** → upload graphics from `docs/play-store/v1.0/`:
   - App icon: `icon-512.png`
   - Feature graphic: `feature-1024x500.png`
   - Phone screenshots (4): `phone-01-library-hero.png` through `phone-04-fiction-detail.png`
   - 7-inch / 10-inch tablet screenshots (6): `tablet-01-library-hero.png` through `tablet-06-voice-picker.png`
   - Short description, full description, contact email/phone/website.

This is **first-time only**. Everything below is recurring.

### 2. Create the service account (Google Cloud Console)

1. Open https://console.cloud.google.com/ (use the same Google account as Play Console admin).
2. Create a new project named `techempower-storyvox-publishing` (or reuse an existing TechEmpower project).
3. Navigate to **APIs & Services → Library** → search for **Google Play Android Developer API** → enable it.
4. Navigate to **IAM & Admin → Service Accounts** → **Create service account**:
   - Name: `storyvox-play-publisher`
   - Role: leave blank at this step (we grant Play Console role separately).
5. Open the new service account → **Keys → Add key → Create new key → JSON**. A `.json` file downloads.
6. Move that JSON to `/home/jp/.storyvox-keystore/play-service-account.json` (alongside the release keystore — both kept out of the repo).

### 3. Grant the service account Play Console permissions

1. Back in **Play Console** → **Setup → API access**.
2. Click **Link** next to the Google Cloud project you created.
3. Find the new service account, click **Grant access**.
4. Permissions to grant:
   - **App permissions** → storyvox only (don't grant cross-app).
   - **Account permissions** → at minimum **Release manager**. (Release manager can publish to Internal/Closed/Open testing and Production, but cannot change app settings — exactly what we want for automated releases.)
5. Click **Invite user**. Accept the invitation from the same Google account.

### 4. Wire the credentials into the build

Edit `local.properties` (gitignored) and add:

```properties
storyvox.playPublisher.credentialsFile=/home/jp/.storyvox-keystore/play-service-account.json
```

That's the entire build-side configuration. No keystore changes; the existing release-keystore properties continue to sign the AAB.

### 5. Bootstrap the Play Console listing into the repo (optional)

If you want listing text (release notes, store description) to live in `app/src/main/play/` so it's version-controlled and uploads with each release:

```bash
./gradlew :app:bootstrapListing
```

This downloads the current Play Console listing into `app/src/main/play/`. Future edits to those files publish back automatically with `./gradlew publishReleaseBundle`. Skip this step if you'd rather edit listing copy in the Play Console UI.

## Per-release workflow (after one-time setup)

Once the steps above are done, **every future release** runs through:

```bash
# 1. Bump versionCode + versionName in app/build.gradle.kts
# 2. Update CHANGELOG.md
# 3. Tag + push:
git tag -a v0.5.69 -m "v0.5.69 — ..."
git push origin main v0.5.69

# 4. CI builds the AAB on tag push (self-hosted runner on katana).
#    The same AAB is what gets uploaded.

# 5. Upload to Play Console Internal Test:
./gradlew :app:publishReleaseBundle
# → uploads AAB, stages as DRAFT, prints the Play Console edit URL

# 6. Open Play Console → Internal Test → click "Release" to publish to Internal Test users
#    OR promote to Production once you've tested:
./gradlew :app:promoteArtifact --from-track internal --promote-track production
```

If the first run prints `PlayPublisher requires credentials`, double-check `storyvox.playPublisher.credentialsFile` in `local.properties` and that the file exists.

## Promoting Internal Test → Production

After a Beta test cycle (you and a few invitees test the Internal Test build for a day or two), promote to Production:

**Option A — Play Console UI**:
1. Play Console → Production → "Create new release" → "Add from library" → pick the Internal Test build.
2. Add release notes (or let them inherit from the Internal Test draft).
3. Submit for review. Google's SLA is typically a few hours to a few days; first submission usually takes longer because the listing review is also gated.

**Option B — Gradle**:
```bash
./gradlew :app:promoteArtifact --from-track internal --promote-track production
```

Either way, **Google review is mandatory** for the first Production release. They check for policy compliance (content rating accuracy, permissions justification, etc.). Subsequent updates usually clear review automatically.

## Safety net

- The plugin defaults are deliberately conservative: every `publishReleaseBundle` is a **draft** until you click "Release" in Play Console. A broken CI build never auto-publishes.
- The release keystore (`storyvox-release.keystore`) is what signs the AAB. Once it's accepted by Play App Signing, **never rotate** without going through Google's key-rotation flow — a wrong key uploaded against an existing app gets rejected with no recovery path.
- The service-account JSON has Play Console publishing rights. **Do not commit it** (it's outside the repo at `~/.storyvox-keystore/`, gitignored at the directory level). If exposed, rotate immediately via Google Cloud Console → Service Accounts → Keys.
- Both the release keystore AND the service-account JSON are backed up in Vaultwarden (`storyvox-release-keystore` item; add the service-account JSON as a Secure Note attachment).

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `PlayPublisher requires credentials` | `storyvox.playPublisher.credentialsFile` unset in local.properties OR file missing | Re-check step 4 above |
| `403 The caller does not have permission` | Service account not granted Play Console access | Re-check step 3 (grant Release manager role in Play Console → API access) |
| `version code X has already been used` | A previous tagged release used the same `versionCode` | Bump `versionCode` in `app/build.gradle.kts` and re-tag |
| `Signed Bundle's package name does not match` | AAB signed with a different keystore than the app's Play App Signing key expects | Re-check `storyvox.releaseStoreFile` points at the correct keystore (`/home/jp/.storyvox-keystore/storyvox-release.keystore`, SHA256 `38:9F:BD:AA…85:E0:16`) |
| `The signing key used to sign the App Bundle is not registered` | Play App Signing hasn't been enrolled OR Google has a different upload key | Play Console → Setup → App integrity → re-confirm the upload key matches our release keystore's SHA256 |

## References

- gradle-play-publisher docs: https://github.com/Triple-T/gradle-play-publisher
- Play Console API access: https://play.google.com/console (login required, then Setup → API access)
- Google Cloud Console: https://console.cloud.google.com/
- Service-account key rotation: https://cloud.google.com/iam/docs/keys-create-delete
