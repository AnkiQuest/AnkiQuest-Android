# F-Droid

How AnkiQuest gets into F-Droid's main repository. Nothing is submitted yet.

- [`org.ankiquest.ankidroid.yml`](org.ankiquest.ankidroid.yml): draft recipe for [fdroiddata](https://gitlab.com/fdroid/fdroiddata), `metadata/org.ankiquest.ankidroid.yml`.
- [`fastlane/metadata/android/en-US/`](../../fastlane/metadata/android/en-US): the store listing F-Droid reads from this repository (title, descriptions, changelog). It has no screenshots yet: the five screens `AnkiquestBannerScreenshots` records for the README banner would serve, saved to `images/phoneScreenshots/`. Without `images/icon.png` F-Droid shows the APK's launcher icon.

## What F-Droid checks

From the [inclusion policy](https://f-droid.org/docs/Inclusion_Policy/) and the [build metadata reference](https://f-droid.org/docs/Build_Metadata_Reference/):

| Requirement | AnkiQuest |
| --- | --- |
| Free licence, built from source with a free toolchain | AGPL-3.0-or-later and GPL-3.0; F-Droid builds the `full` flavour with Gradle and Debian's OpenJDK. |
| Prebuilt libraries only from trusted Maven repositories, freely licensed | The Rust backend comes from Maven Central as the `org.ankiquest` backend artifacts (`anki-backend-android`, GPL-3.0, built from AnkiQuest-Android-Backend and AnkiQuest-Anki), as upstream AnkiDroid's recipe takes `io.github.ankidroid:Anki-Android-Backend` from there. F-Droid does not build it from source. Other repositories: Google, Maven Central, JitPack. |
| No Google Play Services, Firebase, Crashlytics or proprietary analytics | None. Analytics (opt-in) and crash reports (ask first) are upstream's, which F-Droid already ships. |
| A fork has its own application ID, name and icon | `org.ankiquest.ankidroid` (#420), "AnkiQuest", the AnkiQuest launcher icon. Visible strings that named AnkiDroid say AnkiQuest (separate PR). |
| No self-updates without opt-in consent | The GitHub build checks for updates every six hours. The F-Droid build passes `-PankiquestUpdates=none` (#484), which removes the check and the install permission. |
| Assets built from or fetched by the recipe, freely licensed | The companion pictures come from the AnkiQuest-Art release `ankiquest-art.json` pins: the recipe's prebuild fetches its packs and launcher icons and checks their SHA-256 (see "Companion art"). AnkiQuest-Art's licence, Anki-tan's CC BY-SA 4.0. |
| Anti-features declared | None; see below. |

## Anti-features

The recipe declares the anti-features upstream AnkiDroid's `metadata/com.ichi2.anki.yml` declares, plus those AnkiQuest's additions need. Upstream's declares none. AnkiQuest's additions need none:

- `NonFreeNet`: the AnkiQuest server is free software (AnkiQuest-Server, AGPL-3.0-or-later).
- `TetheredNet`: the server address is chosen at sign-in (default `https://ankiquest.org`), and AnkiQuest is optional; the app works without an account.
- `Tracking`: nothing is sent without an account. Profiles are private unless the player makes them public, asked at sign-up with "public" off (AnkiQuest-Server and the sign-up PR in this repository). Crash reports ask first, as upstream's do.
- `NonFreeAssets`: companion poses are under AnkiQuest-Art's licence, Anki-tan's under CC BY-SA 4.0.
- `NonFreeDep`, `NonFreeAdd`: no Google Play Services, Firebase or proprietary libraries in the `full` flavour.

Revisit this list when a feature sends data to a new service or makes something public by default.

## Companion art

The app commits no companion pictures. Every build takes them from the AnkiQuest-Art release `ankiquest-art.json` pins: `BundleAnkiquestPacks` (buildSrc) bundles that release's `pack-*.zip` with `packs.json` as assets and unpacks the launcher icons' pictures from its `android-drawables.zip` as resources. It reads each file from its cache, named by SHA-256, and downloads only what is missing there.

F-Droid builds the F-Droid APK like the GitHub full APK, with the art bundled. Its prebuild:

1. lists `packs.json`, every `pack-*.zip` and `android-drawables.zip` with their SHA-256 from `ankiquest-art.json` into `ankiquest-art/SHA256SUMS`;
2. downloads each from `https://github.com/AnkiQuest/AnkiQuest-Art/releases/download/<tag>/`;
3. checks them with `sha256sum -c` (a mismatch fails the build) and renames each to its SHA-256.

`-PankiquestArtCache=ankiquest-art` points the build at that folder, so Gradle finds every file and downloads nothing; the "F-Droid APK" step passes the same property. `scanignore` keeps the scanner off the downloaded zips, which hold pictures and JSON only.

A srclib of AnkiQuest-Art at the pinned commit would also work, but the packs are made by AnkiQuest-Art's Rust exporter; building them from source would add Rust and that tool to the recipe for files whose SHA-256 the pin already fixes.

## Signing

F-Droid either signs its own build, or publishes ours when its rebuild matches ours byte for byte apart from the signature ([reproducible builds](https://f-droid.org/docs/Reproducible_Builds/)). AnkiQuest takes the second route: `Binaries` and `AllowedAPKSigningKeys` are set, so F-Droid installs and GitHub installs carry the same signer.

How a release reaches F-Droid:

1. A `quest-*` tag runs `ankiquest_apk.yml`. Its "F-Droid APK" step builds the universal APK with the recipe's Gradle inputs (`-Pankiquest`, `customName`, `-PankiquestUpdates=none`, the release number, the upstream commit, `SOURCE_DATE_EPOCH` from the commit), signs it with the AnkiQuest key alone and uploads it to the `fdroid` release as `AnkiQuest-<versionCode>.apk`. `Binaries` may only use `%v` (versionName, upstream's) and `%c` (versionCode), not the tag number, hence one release holding every version.
2. F-Droid builds the same commit from the recipe, copies our signature onto its build (`apksigcopier`) and publishes our APK only if that verifies against `AllowedAPKSigningKeys` (SHA-256 of the AnkiQuest certificate, `CN=AnkiQuest`, as in the repository README).

The F-Droid APK differs from `AnkiQuest.apk` on the release: it has no self-updater, and it has one signer. The GitHub APKs are signed by the public test key in v1/v2 and by the AnkiQuest key in v3 with a rotation lineage, which only serves updates of `com.ichi2.anki.quest`. fdroidserver rejects an APK whose signers differ ("APK signatures have different certificates"), so the F-Droid APK leaves out the test key and the lineage. On Android 9 and later an F-Droid install and a GitHub install update each other (same current signer). On Android 7 and 8 the GitHub APK verifies with the test key only, so it cannot replace an F-Droid install or the other way round, until the GitHub APKs of `org.ankiquest.ankidroid` are signed with the AnkiQuest key alone too. That package has no installs yet, so its first release can still make that change; afterwards Android 7 and 8 installs could not follow it.

What makes the rebuild match:

- Build time from the commit (`SOURCE_DATE_EPOCH`), as F-Droid's build server sets it.
- Native libraries packaged unchanged (`keepDebugSymbols` in `AnkiDroid/build.gradle.kts`), so the result does not depend on which NDK is installed.
- The same JDK output: releases build with JetBrains Runtime 21, F-Droid with Debian's OpenJDK. `ankiquest_reproducible.yml` compares two GitHub runners, not these two JDKs; the first F-Droid build shows whether they match. If they do not, F-Droid reports the mismatch and publishes nothing for that version.

Signature copying itself works on our APKs: copying quest-23's signature onto the same APK with its signature removed gives the identical file with apksigcopier 1.1.1 on Python 3.11. On Python 3.13 it does not (Python drops the UTF-8 flag of the `META-INF` entries), a problem of apksigcopier, not of our APK.

## Recipe notes

- `UpdateCheckMode: Tags ^quest-[0-9]+$` with `UpdateCheckData` takes the release number from the tag and the versionName from `AnkiDroid/build.gradle.kts`; `VercodeOperation` turns the number into the version code `ankiquest_apk.yml` gives a release (`50000000 + n * 1000`).
- The F-Droid bot writes new builds with a commit hash, so the prebuild reads the release number with `git describe`.
- The upstream commit in the debug info needs a fetch of AnkiDroid's `main` during prebuild.
- The universal APK is the output; F-Droid removes our `signingConfig` before building.
- The recipe's Gradle inputs and the "F-Droid APK" step must stay the same; `.github/tests/fdroid.test.cjs` compares them, and the `Binaries` file name with the uploaded one.
- The art prefetch must list every asset the build reads; `fdroid.test.cjs` runs its listing on `ankiquest-art.json` and compares it with the pin. `-PankiquestArtCache` needs #493.

## Submitting

Once #420, #484 and #493 are merged and the first `org.ankiquest.ankidroid` release is tagged:

1. Fill in the first build in the recipe (tag, versionName, versionCode).
2. Fork [fdroiddata](https://gitlab.com/fdroid/fdroiddata), add the recipe as `metadata/org.ankiquest.ankidroid.yml`, run `fdroid readmeta`, `fdroid rewritemeta org.ankiquest.ankidroid`, `fdroid lint org.ankiquest.ankidroid` and `fdroid build -v -l org.ankiquest.ankidroid`.
3. Open a merge request with the "App inclusion" template.
