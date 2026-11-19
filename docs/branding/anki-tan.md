# Anki-tan

Anki-tan is by Shigeyuki (https://www.patreon.com/Shigeyuki), from the
[Anki-tan asset pack](https://shigeyukey.github.io/shige-addons-wiki/Anki-tan.html)
v1.0. The pictures and every derivative of them (resized, recoloured,
outlined) are licensed under
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)
([text](../../LICENSES/CC-BY-SA-4.0.txt)). Anki-tan is an unofficial
character, not related to the official Anki project.

The masters are in AnkiQuest/AnkiQuest-Desktop `static/anki-tan/`.

## Launcher icons

Companion `anki_tan` gets her own launcher icons (`AnkiquestIcon.Icon`).
The app does not offer her as a companion yet: she lacks a frozen pose
(AGENTS.md, Companion art).

| Mood | Drawable | Master |
|---|---|---|
| default, frozen | `anki_tan_icon` | `icon.png` |
| happy | `anki_tan_icon_happy` | `emoji/Ankitan_09_fire_eyes.png` |
| worried | `anki_tan_icon_worried` | `emoji/Ankitan_02_cry.png` |
| sleepy | `anki_tan_icon_sleepy` | `emoji/Ankitan_35_sleeping.png` |
| monochrome (themed icon) | `anki_tan_icon_mono` | line art of `emoji/Ankitan_00_smile.png` |

The drawables are in `ankiquest-ui/src/main/res/drawable-nodpi/`, written by
the server's exporter:
`cargo run -p ankiquest-xtask -- export-android-art <android-checkout>`.
The adaptive icons in `AnkiDroid/src/quest/res/` inset her app icon by 10%
so its frame stays outside the launcher's mask, and the faces by 18%, inside
the 72dp safe zone, as Aki's are.

The About screen and the README credit her.
