AnkiDroid with [AnkiQuest](https://github.com/AnkiQuest/AnkiQuest-Desktop): study, shared goals, and progress in one place.

**New app.** AnkiQuest now installs as `org.ankiquest.ankidroid`. Update the old app to [quest-24](https://github.com/AnkiQuest/AnkiQuest-Android/releases/tag/quest-24) first. On Android 9 and later, this app offers to move settings and sign-ins from it on first launch. On Android 7 and 8, sign in again here. If both apps use the shared `AnkiDroid` folder, force-stop the old AnkiQuest in Android settings before opening the same collection here. If the apps use separate folders, sync your collection with AnkiWeb or export it from the old app and import it here. Keep the old app until you can open your collection in the new one. Obtainium users can remove the old entry and add AnkiQuest again. Sync automations use `org.ankiquest.ankidroid.DO_SYNC`.

**AnkiQuest key.** Every APK is signed with the AnkiQuest key alone, on every Android version. Its fingerprints are under "Verify the APK" in the README.

**Smaller downloads.** Besides `AnkiQuest.apk`, which runs on every phone, each release has a smaller APK for each processor type: arm64-v8a, armeabi-v7a, x86_64 and x86. Releases also build an app bundle for Google Play.

**F-Droid.** Each release also builds the APK F-Droid's recipe reproduces, signed with the same key, so F-Droid and GitHub installs update each other. AnkiQuest is not in F-Droid yet.

This release includes the latest AnkiDroid changes. Companion pictures come from AnkiQuest-Art art-11.

Installs next to the official AnkiDroid as "AnkiQuest". Log in to AnkiWeb and sync to get your cards, then open Settings → AnkiQuest → Account → Sign in or create account. Later releases are offered inside the app.

Study uploads contain review history (card id, timestamp, previous interval, time taken, review type, the button pressed and your time-zone offset), never card content. Social actions send the invitation or message you choose to share.
