<p align="center">
<img alt="" src="docs/graphics/logos/banner_readme.png"/>
</p>

<a href="https://github.com/AnkiQuest/AnkiQuest-Android/releases"><img src="https://img.shields.io/github/v/release/AnkiQuest/AnkiQuest-Android" alt="release"/></a>
<a href="https://github.com/AnkiQuest/AnkiQuest-Android/actions"><img src="https://img.shields.io/github/checks-status/AnkiQuest/AnkiQuest-Android/main?label=build" alt="build"/></a>
<a href="https://github.com/AnkiQuest/AnkiQuest-Android/commits/main"><img src="https://img.shields.io/github/commit-activity/m/AnkiQuest/AnkiQuest-Android" alt="commit-activity"/></a>
<a href="https://github.com/AnkiQuest/AnkiQuest-Android/graphs/contributors"><img src="https://img.shields.io/github/contributors/AnkiQuest/AnkiQuest-Android" alt="contributors"/></a>
<a href="https://github.com/AnkiQuest/AnkiQuest-Android/blob/main/COPYING"><img src="https://img.shields.io/github/license/AnkiQuest/AnkiQuest-Android" alt="license"/></a>

# AnkiQuest-Android

[AnkiDroid](https://github.com/ankidroid/Anki-Android), the Android port of the open source [Anki](https://apps.ankiweb.net/index.html) spaced repetition flashcard system, with AnkiQuest built in.

AnkiQuest adds XP, streaks, daily quests, friends and leaderboards to Anki. Clients send review history without card content; sharing a conquered card label or a deck copy is an explicit choice. AnkiQuest has three repositories:

- [AnkiQuest-Server](https://github.com/AnkiQuest/AnkiQuest-Server): the server and its website.
- [AnkiQuest-Desktop](https://github.com/AnkiQuest/AnkiQuest-Desktop): the Anki desktop add-on.
- [AnkiQuest-Android](https://github.com/AnkiQuest/AnkiQuest-Android): the Android app, a fork of AnkiDroid.

<img src="docs/graphics/logos/ankidroid_logo.png" align="right" width="40%" height="100%"></img>

### Features

<div style="display:flex;">
 
- AnkiQuest: XP, streaks, quests, records and a leaderboard widget
- Night mode
- Whiteboard 
- Progress widget
- Detailed statistics
- [Review heatmap beneath the deck list](docs/review-heatmap.md)
- Syncing with AnkiWeb
- Write answers (optional)
- Text-to-speech integration
- More than 10,000 premade decks
- Spaced repetition (AI-optimized [FSRS algorithm](https://github.com/open-spaced-repetition))
- Supported contents: text, images, sounds, MathJax
- Add cards by intent from other applications like dictionaries

</div>

## Install

<div style="display:flex;">

1. Create an account on an AnkiQuest server, such as [ankiquest.rationality-munich.com](https://ankiquest.rationality-munich.com). To run your own, see [AnkiQuest-Server](https://github.com/AnkiQuest/AnkiQuest-Server).
2. Download the APK from [Releases](https://github.com/AnkiQuest/AnkiQuest-Android/releases/latest). It installs next to AnkiDroid as "AnkiQuest" and offers later releases itself. Sync your collection through AnkiWeb as usual.
3. Open **Settings → ankiquest → Account → Sign in or create account**.

<a href="http://apps.obtainium.imranr.dev/redirect.html?r=obtainium://add/https://github.com/AnkiQuest/AnkiQuest-Android">
    <img alt="Get it on Obtainium" height="80"
        src="https://github.com/user-attachments/assets/713d71c5-3dec-4ec4-a3f2-8d28d025a9c6"/></a>

</div>

For everything Anki, AnkiDroid's [user manual](https://docs.ankidroid.org/), [wiki](https://github.com/ankidroid/Anki-Android/wiki) and community ([Discord](https://discord.gg/qjzcRTx), [Reddit](https://www.reddit.com/r/Anki), [Anki forums](https://forums.ankiweb.net/)) apply here too. Report AnkiQuest bugs and ideas in [Issues](https://github.com/AnkiQuest/AnkiQuest-Android/issues).

## Development

```sh
./gradlew ktlintCheck
./gradlew :AnkiDroid:testPlayDebugUnitTest --tests '…'
node --test .github/tests/*.test.cjs
```

AnkiQuest code is in `AnkiDroid/src/main/java/com/ichi2/anki/ankiquest/`; [AGENTS.md](AGENTS.md) has the rules. Pull requests are welcome [here](https://github.com/AnkiQuest/AnkiQuest-Android/pulls). Changes that are not about AnkiQuest belong [upstream](https://github.com/ankidroid/Anki-Android); see AnkiDroid's [contribution guide](https://github.com/ankidroid/Anki-Android/wiki/Contributing).

## Credits
<!--- Do not rename this section. AnkiDroid contains a deep link to the section
header - see https://github.com/ankidroid/Anki-Android/pull/11803 --->

### Code Contributors

AnkiQuest for Android is built on the work of [AnkiDroid's contributors](https://github.com/ankidroid/Anki-Android/graphs/contributors). See also [this fork's contributors](https://github.com/AnkiQuest/AnkiQuest-Android/graphs/contributors).

### Art

Anki-tan (`anki_tan_*.webp`) is by [Shigeyuki](https://www.patreon.com/Shigeyuki), from the [Anki-tan asset pack](https://shigeyukey.github.io/shige-addons-wiki/Anki-tan.html), licensed [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). Derivatives of her art are CC BY-SA 4.0 too. See [docs/branding/anki-tan.md](docs/branding/anki-tan.md).

### [Translators](https://crowdin.com/project/ankidroid/activity-stream)

Thanks to our 1400 translators, for allowing us to be available, partially or totally, in 99 languages as of July 2022.

## License

* [GPL-3.0 License](https://github.com/AnkiQuest/AnkiQuest-Android/blob/main/COPYING) for AnkiDroid, including the AnkiDroid files this fork modifies
* [AGPL-3.0 License](https://github.com/AnkiQuest/AnkiQuest-Android/blob/main/LICENSES/AGPL-3.0-only.txt) for the AnkiQuest code: every file marked `SPDX-License-Identifier: AGPL-3.0-only`, plus `docs/branding/`, `docs/review-heatmap.md`, the `aki_*.png` artwork, `AnkiDroid/src/test/resources/ankiquest/`, `AnkiDroid/src/main/assets/ankiquest/release-notes.md` and `tools/ankiquest-signing-lineage.bin`. As GPLv3 section 13 allows, the app combines both, and the AGPL's network-interaction terms apply to the combined work
* [AGPL-3.0 License](https://github.com/ankitects/anki/blob/main/LICENSE) for some part of the back-end
* [CC BY-SA 4.0 License](https://github.com/AnkiQuest/AnkiQuest-Android/blob/main/LICENSES/CC-BY-SA-4.0.txt) for the Anki-tan artwork (`anki_tan_*.webp`), by Shigeyuki
* [LGPL-3.0 License](https://github.com/AnkiQuest/AnkiQuest-Android/blob/main/api/COPYING.LESSER) for the AnkiDroid API
