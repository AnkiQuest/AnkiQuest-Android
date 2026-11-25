# F-Droid

How AnkiQuest gets into F-Droid's main repository. Nothing is submitted yet.

- [`org.ankiquest.ankidroid.yml`](org.ankiquest.ankidroid.yml): draft recipe for [fdroiddata](https://gitlab.com/fdroid/fdroiddata), `metadata/org.ankiquest.ankidroid.yml`.
- [`fastlane/metadata/android/en-US/`](../../fastlane/metadata/android/en-US): the store listing F-Droid reads from this repository (title, descriptions, changelog). It has no screenshots yet: the five screens `AnkiquestBannerScreenshots` records for the README banner would serve, saved to `images/phoneScreenshots/`. Without `images/icon.png` F-Droid shows the APK's launcher icon.

## What F-Droid checks

From the [inclusion policy](https://f-droid.org/docs/Inclusion_Policy/) and the [build metadata reference](https://f-droid.org/docs/Build_Metadata_Reference/):

| Requirement | AnkiQuest |
| --- | --- |
| Free licence, built from source with a free toolchain | AGPL-3.0-or-later and GPL-3.0; F-Droid builds the `full` flavour with Gradle and Debian's OpenJDK. |
| Prebuilt libraries only from trusted Maven repositories, freely licensed | The Rust backend `org.ankiquest:anki-backend-android` comes from Maven Central (GPL-3.0), as upstream AnkiDroid's `io.github.ankidroid:Anki-Android-Backend` does in its F-Droid recipe. Other repositories: Google, Maven Central, JitPack. |
| No Google Play Services, Firebase, Crashlytics or proprietary analytics | None. Analytics (opt-in) and crash reports (ask first) are upstream's, which F-Droid already ships. |
| A fork has its own application ID, name and icon | `org.ankiquest.ankidroid` (#420), "AnkiQuest", the AnkiQuest launcher icon. Most upstream strings still say "AnkiDroid"; a reviewer may ask for them to change. |
| No self-updates without opt-in consent | The GitHub build checks for updates every six hours. The F-Droid build passes `-PankiquestUpdates=none` (#411), which removes the check and the install permission. |
| Anti-features declared | None expected: the AnkiQuest server is free software and the server address is chosen at sign-in, so neither `NonFreeNet` nor `TetheredNet` applies. Reviewers may weigh `Tracking` for public profiles listed on the global leaderboard by default. |

## Signing

F-Droid either signs its own build, or publishes ours when its rebuild matches ours byte for byte apart from the signature ([reproducible builds](https://f-droid.org/docs/Reproducible_Builds/)).

**F-Droid signs.** The recipe as drafted. F-Droid's key differs from ours, so an F-Droid install cannot update from GitHub or the other way round; switching means uninstalling. No release changes needed.

**F-Droid publishes our APK.** Uncomment `Binaries` and `AllowedAPKSigningKeys`. F-Droid builds from the recipe, copies our signature onto its build (`apksigcopier`) and publishes our APK only if that verifies. Needed first:

- Sign with the AnkiQuest key alone. Releases are signed by the public test key in v1/v2 and by the AnkiQuest key in v3 with a rotation lineage. fdroidserver rejects an APK whose signers differ ("APK signatures have different certificates"). The lineage only serves updates of `com.ichi2.anki.quest`; `org.ankiquest.ankidroid` has no installs yet, so its first release can drop it. Once installs signed with the test key exist, Android 7 and 8 devices cannot follow a change of signer.
- Publish an APK built as the recipe builds it (`-PankiquestUpdates=none`), since the GitHub APK keeps the updater. The draft uses a release named `fdroid` holding `AnkiQuest-<versionCode>.apk`, because `Binaries` may only use `%v` (versionName, upstream's) and `%c` (versionCode), not the tag number.
- Build time from the commit (`SOURCE_DATE_EPOCH`), as F-Droid's build server sets it. `ankiquest_apk.yml` does this now.
- Native libraries packaged unchanged, so the result does not depend on which NDK is installed. `AnkiDroid/build.gradle.kts` does this now.
- The same JDK output: releases build with JetBrains Runtime 21, F-Droid with Debian's OpenJDK. `ankiquest_reproducible.yml` compares two GitHub runners, not these two JDKs; the first F-Droid build shows whether they match.

Signature copying itself works on our APKs: copying quest-23's signature onto the same APK with its signature removed gives the identical file with apksigcopier 1.1.1 on Python 3.11. On Python 3.13 it does not (Python drops the UTF-8 flag of the `META-INF` entries), a problem of apksigcopier, not of our APK.

## Recipe notes

- `UpdateCheckMode: Tags ^quest-[0-9]+$` with `UpdateCheckData` takes the release number from the tag and the versionName from `AnkiDroid/build.gradle.kts`; `VercodeOperation` turns the number into the version code `ankiquest_apk.yml` gives a release (`50000000 + n * 1000`).
- The F-Droid bot writes new builds with a commit hash, so the prebuild reads the release number with `git describe`.
- The upstream commit in the debug info needs a fetch of AnkiDroid's `main` during prebuild.
- The universal APK is the output; F-Droid removes our `signingConfig` before building.

## Submitting

hill submits, once #420 and #411 are merged and the first `org.ankiquest.ankidroid` release is tagged:

1. Fill in the first build in the recipe (tag, versionName, versionCode).
2. Fork [fdroiddata](https://gitlab.com/fdroid/fdroiddata), add the recipe as `metadata/org.ankiquest.ankidroid.yml`, run `fdroid readmeta`, `fdroid rewritemeta org.ankiquest.ankidroid`, `fdroid lint org.ankiquest.ankidroid` and `fdroid build -v -l org.ankiquest.ankidroid`.
3. Open a merge request with the "App inclusion" template.
