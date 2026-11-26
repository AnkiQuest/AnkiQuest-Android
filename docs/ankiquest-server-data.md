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
The app calls them through UniFFI's Kotlin bindings and JNA. Nothing is
committed or pinned: every build takes the newest `rules-<N>` release of
AnkiQuest-Server (buildSrc `AnkiquestRules.kt`):

- `ankiquest_rules.kt`, the bindings, into generated Kotlin sources
  (package `org.ankiquest.rules`).
- `libankiquest_rules-android-<abi>.so` for arm64-v8a, armeabi-v7a, x86
  and x86_64, into generated `jniLibs`.
- `ankiquest/rules-release.json` in the assets: the tag and the SHA-256 of
  each of those, so an APK names the release it was built from.
- For the unit tests, the desktop library of the machine they run on
  (Linux x86_64 and aarch64, Windows x86_64, macOS arm64 and x86_64),
  which the test JVM loads through `jna.library.path`.

Each asset is checked against the release's `SHA256SUMS` and cached by
SHA-256 in `~/.gradle/caches/ankiquest-rules` (or `ANKIQUEST_RULES_CACHE`).
The newest tag comes from the repository's git refs, which have no API rate
limit; offline, a build uses the newest release it saw before.

```sh
ANKIQUEST_RULES_TAG=rules-3 ./gradlew ...   # rebuild with a given release
ANKIQUEST_RULES_DIR=<dir> ./gradlew ...     # assets and SHA256SUMS in a folder
```

`-PankiquestRulesTag`, `-PankiquestRulesDir` and `-PankiquestRulesCache`
do the same. `com.ichi2.anki.ankiquest.AnkiquestSharedRules` is the only
code that imports the bindings; the rest of the app uses its own types.
