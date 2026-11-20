# AnkiQuest API

AnkiQuest API is AnkiDroid's API library (the `api` module) published as `org.ankiquest:api`. Apps that use it work with AnkiDroid, AnkiQuest and any other app offering AnkiDroid's flashcards provider ([`FlashCardsContract`](../api/src/main/java/com/ichi2/anki/FlashCardsContract.kt)).

## Switching from AnkiDroid's library

The packages and classes are AnkiDroid's (`com.ichi2.anki.api.AddContentApi`, `com.ichi2.anki.FlashCardsContract`), so an app switches by changing its dependency:

```kotlin
dependencies {
    implementation("org.ankiquest:api:2.0.0-ankiquest.1") // instead of com.ichi2.anki:api
}
```

Maven Central is the repository (`mavenCentral()`). Versions are `<AnkiDroid's api version>-ankiquest.<n>`.

## Which provider the API talks to

- Candidates are the installed apps whose provider declares the intent action `FlashCardsContract.ACTION_FLASHCARDS_PROVIDER` (`com.ichi2.anki.action.FLASHCARDS_PROVIDER`), plus AnkiDroid and AnkiQuest by their authority for releases without it.
- AnkiDroid (`com.ichi2.anki`) and AnkiQuest (`org.ankiquest.anki`) count only when signed with their release keys (AnkiDroid: Google Play/GitHub and F-Droid). A debuggable client also accepts self-built ones. No other package may hold their authority.
- Android grants only permissions the client declares, so an app whose permission the client does not declare is not a candidate. The library declares AnkiDroid's and AnkiQuest's. For another app, declare its permission, `<package>.permission.READ_WRITE_DATABASE`, in the client's manifest.
- One candidate: the API uses it once the client holds its permission. Request it as before; Android's dialog names the app.
- Several candidates: the library opens a chooser once per process. The user picks an app and grants its permission; the choice is pinned to the app's package, authority and SHA-256 signing certificate. If that app is uninstalled, its certificate changes or another package holds its authority, the choice is dropped and the user is asked again.

## New API

All additive; existing calls behave as before with AnkiDroid as the only provider.

- `AddContentApi.getProvider(context)`: the provider in use (`packageName`, `authority`, `permission`), or null. Request its `permission` instead of `FlashCardsContract.READ_WRITE_PERMISSION` to support other providers.
- `AddContentApi.getProviders(context)`: the candidates.
- `AddContentApi.chooseProvider(context)`: opens the chooser.
- `FlashCardsContract.ACTION_FLASHCARDS_PROVIDER`: the intent action providers declare.

Apps that build content URIs from `FlashCardsContract` directly should use `FlashCardsContract.getAuthorityUri(getProvider(context).authority)` instead of `AUTHORITY_URI`.

## Publishing

`.github/workflows/ankiquest_api_publish.yml` releases automatically. When a push to `main` touches `api/`, or on a manual run, it publishes the `version` in `api/build.gradle.kts` (such as `2.0.0-ankiquest.1`) to Maven Central, signed, if that version is not released yet (no tag `api-v<version>`, no POM on Maven Central), then tags it `api-v<version>`. A pull request that changes `api/` without a new `version` fails, since a version on Maven Central can never change. It needs the secrets `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` (a Central Portal user token for the `org.ankiquest` namespace), `SIGNING_PRIVATE_KEY` and `SIGNING_PASSWORD`, as organisation secrets shared with this repository.

To release: raise `version` in `api/build.gradle.kts` in the pull request and merge it.
