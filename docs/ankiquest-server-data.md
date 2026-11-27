# AnkiQuest server data

The unit tests run against AnkiQuest-Server `master`. They read its API
examples (`docs/api/examples/*.json`) from the test resources'
`ankiquest-api/` and its shared fixtures (`tests/fixtures/*.json`) from
`ankiquest/`. Neither is committed and nothing pins a server commit:

```sh
node .github/ankiquest_server_data.cjs fetch
```

copies them from a shallow, sparse clone of `master` and prints the server
commit. `companion_poses.json` is not copied: the pose list is the pinned
AnkiQuest-Art release's `poses.json`, which the same command downloads and
checks against the SHA-256 `ankiquest-art.json` pins before writing it next
to the server data. Every workflow that runs the unit tests (`tests_unit.yml`,
`ankiquest_api_contract.yml`, the `screenshot_*.yml` workflows and
`ankiquest_banner.yml`) runs it before Gradle. Server and Art files are read
as JSON data only; no server code runs.

To test against a server change before it reaches `master`:

```sh
node .github/ankiquest_server_data.cjs fetch --ref <branch, tag or full commit SHA>
node .github/ankiquest_server_data.cjs fetch ../AnkiQuest-Server   # a local checkout
./gradlew :AnkiDroid:testFullDebugUnitTest --tests 'com.ichi2.anki.ankiquest.AnkiquestApiContractTest'
```

The API contract workflow takes the same ref as its optional `server_ref`
input on manual dispatch.

## Example catalog

`ankiquest-api/names.json` is committed. It assigns each example Android
knows to one group:

- `consumed`: exercised by the Android API consumer tests.
- `boards` and `challenges`: further consumed groups.
- `unused`: examples Android does not read.
- `not_yet`: examples for a future Android feature. Move them to a consumed
  group when its consumer assertions land.

An example the catalog doesn't list counts as `not_yet`, so a new server
example doesn't fail the tests. A name the server no longer has fails the
contract test until it leaves the catalog. The server's `android-contract`
check reads the consumed groups and rejects a server change that removes one
of those examples, a key path in it or a JSON type at that path.

## When the server changes

A server change that breaks these tests is fixed in the same change, or
Android is updated right after. CI on every pull request and on `master`
fetches `master` again, so a breaking server merge turns them red until
then. The screenshot tests and the README banner draw screens from the
examples: a server change to those values changes the pictures, and the
screenshot baseline is stored again.

Run the workflow helper tests with `node --test .github/tests/*.test.cjs`.

## Shared rules

Rules both clients compute move into AnkiQuest-Server's `crates/rules`, one
at a time (AnkiQuest/AnkiQuest-Server#83, `docs/shared-rules.md` there).
The app calls them through UniFFI's Kotlin bindings and JNA, in the
`:ankiquest-rules` module: `com.ichi2.anki.ankiquest.AnkiquestSharedRules`
there is the only code that imports the bindings (`org.ankiquest.rules`),
and no other module sees them; the rest of the app uses the module's own
types (`AnkiquestOptimizeRule`). Nothing is committed or pinned: every
build takes the newest `rules-<N>` release of AnkiQuest-Server (buildSrc
`AnkiquestRules.kt`):

- `ankiquest_rules.kt`, the bindings, into the module's generated Kotlin
  sources.
- `libankiquest_rules-android-<abi>.so` for arm64-v8a, armeabi-v7a, x86
  and x86_64, into the module's generated `jniLibs`.
- `ankiquest/rules-release.json` in the module's assets: the tag and the
  SHA-256 of each of those, so an APK names the release it was built from.
  The `quest-<n>` GitHub release carries a copy, for the F-Droid build.
- For the unit tests of `:AnkiDroid` (`useRulesHostLibrary`), the desktop
  library of the machine they run on (Linux x86_64 and aarch64, Windows
  x86_64, macOS arm64 and x86_64), which the test JVM loads through
  `jna.library.path`.

### Checks

Each downloaded asset is checked twice before the build uses it:

1. against the release's `SHA256SUMS`;
2. against the release's build provenance attestation,
   `ankiquest_rules.sigstore.json`: the Sigstore bundle must verify against
   Sigstore's public trust root (fetched through its TUF repository and
   cached for a day), its certificate must be issued by
   `https://token.actions.githubusercontent.com` to
   `https://github.com/AnkiQuest/AnkiQuest-Server/.github/workflows/rules-release.yml@refs/heads/master`,
   and the asset's SHA-256 must be one of its subjects
   (buildSrc `AnkiquestAttestation.kt`).

Only then is it cached, by SHA-256, in `~/.gradle/caches/ankiquest-rules`
(or `ANKIQUEST_RULES_CACHE`). A cached asset was verified when it was
cached, so it is used again without network. The newest tag comes from the
repository's git refs, which have no API rate limit; offline, a build uses
the newest release it saw before.

The verifier is [sigstore-java](https://github.com/sigstore/sigstore-java),
run by Gradle's worker API in a class loader of its own, so its protobuf
and Guava never meet the Android Gradle plugin's. It needs no `gh` CLI, no
GitHub token and no API call: the bundle is a release asset. Anyone can
check an asset by hand too:

```sh
gh attestation verify libankiquest_rules-android-arm64-v8a.so --repo AnkiQuest/AnkiQuest-Server \
  --signer-workflow AnkiQuest/AnkiQuest-Server/.github/workflows/rules-release.yml --source-ref refs/heads/master
```

### Other sources

```sh
ANKIQUEST_RULES_TAG=rules-3 ./gradlew ...   # rebuild with a given release
ANKIQUEST_RULES_DIR=<dir> ./gradlew ...     # assets and SHA256SUMS in a folder
```

`-PankiquestRulesTag`, `-PankiquestRulesDir` and `-PankiquestRulesCache`
do the same. A folder is checked against its `SHA256SUMS` only, without an
attestation: it is for assets built from source or not published yet. With
both a folder and a tag, the record names the tag.

### F-Droid

F-Droid builds the app from source, so its recipe builds the shared rules
from AnkiQuest-Server's source too, at the tag of the release the GitHub
APK was built with, and passes them as a folder. Built that way, the
libraries, the bindings and `rules-release.json` have the bytes of the
release, so the APK still matches ours. AnkiQuest-Server's
`crates/rules/build-android.sh <out>` builds them (its CI checks that two
builds from different folders and Cargo homes match) and writes `<out>` in
the layout `ANKIQUEST_RULES_DIR` takes.

AnkiQuest-Server is a srclib, `srclibs/AnkiQuestServer.yml` in fdroiddata:

```yaml
RepoType: git
Repo: https://github.com/AnkiQuest/AnkiQuest-Server.git
```

The build entry's additions (`subdir: AnkiDroid`, so `..` is the
checkout):

```yaml
    srclibs:
      # Any rules-<N> tag; prebuild checks out the one the release records.
      - AnkiQuestServer@rules-1
    ndk: r28c
    prebuild:
      # The rules release the GitHub APK of this tag was built with.
      - curl -fsSL --retry 3 -o ../rules-release.json "https://github.com/AnkiQuest/AnkiQuest-Android/releases/download/$(git describe --tags --exact-match --match 'quest-[0-9]*')/rules-release.json"
      - rules=$(sed -n 's/^ *"tag": *"\(rules-[0-9]*\)".*/\1/p' ../rules-release.json) && test -n "$rules" && git -C $$AnkiQuestServer$$ checkout -q "refs/tags/$rules" && echo "ankiquestRulesTag=$rules" >> ../gradle.properties
    build:
      # The toolchain crates/rules pins, its Android targets and cargo-ndk 4.1.2.
      - curl -fsSL --retry 3 -o ../rustup-init https://static.rust-lang.org/rustup/archive/1.29.3/x86_64-unknown-linux-gnu/rustup-init
      - echo "b1c8cb14e30889e7e2999e6b69e62d9e9b9ffc0f4df8180564d306d9680e6c55  ../rustup-init" | sha256sum -c
      - chmod +x ../rustup-init && ../rustup-init -y --profile minimal --default-toolchain none && rm ../rustup-init
      - . "$HOME/.cargo/env"
      - (cd $$AnkiQuestServer$$/crates/rules && rustup toolchain install && rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android)
      - cargo install cargo-ndk --version 4.1.2 --locked
      # The libraries and bindings, from source; the app's build reads the folder.
      - ANDROID_NDK_HOME=$$NDK$$ $$AnkiQuestServer$$/crates/rules/build-android.sh ../ankiquest-rules
    gradleprops:
      - ankiquestRulesDir=ankiquest-rules
```

F-Droid joins the commands of a step into one shell, so the `cd` runs in a
subshell and `../` stays the repository root. `ankiquestRulesDir` is
relative to the repository root too. The record in the
APK then names the release's tag and the hashes of the libraries built
here; if they differ from the release's, the APK differs from ours and
F-Droid publishes nothing for that version, so a mismatch shows at the
first F-Droid build. F-Droid's `checkupdates` copies the build entry for
each new tag, `srclibs` included; the prebuild picks the right rules tag
for each, so the entry needs no manual change.
