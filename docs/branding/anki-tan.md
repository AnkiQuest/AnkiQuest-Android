# Anki-tan

Anki-tan is by Shigeyuki (https://www.patreon.com/Shigeyuki), from the
[Anki-tan asset pack](https://shigeyukey.github.io/shige-addons-wiki/Anki-tan.html)
v1.0. The pictures and every derivative of them (resized, recoloured,
outlined) are licensed under
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)
([text](../../LICENSES/CC-BY-SA-4.0.txt)). Anki-tan is an unofficial
character, not related to the official Anki project.

The masters are in AnkiQuest/AnkiQuest-Art `anki-tan/`; its README lists
which file each pose uses. Her face is the chibi's face, a headshot; every
other pose is full body.

Pictures that are no longer used, such as her neglected pose with all
three headband stars lit, are in AnkiQuest-Art `archive/anki-tan/`. This
repository commits no companion picture, in `docs/` either; the art check
fails on one.

## Launcher icons

Companion `anki_tan` gets her own launcher icons (`AnkiquestIcon.Icon`).

| Mood | Drawable | Master |
|---|---|---|
| default, frozen | `anki_tan_icon` | `chibi/face.png`, through the icons chain |
| happy | `anki_tan_icon_happy` | `emoji/Ankitan_09_fire_eyes.png` |
| worried | `anki_tan_icon_worried` | `emoji/Ankitan_02_cry.png` |
| sleepy | `anki_tan_icon_sleepy` | `emoji/Ankitan_35_sleeping.png` |
| monochrome (themed icon) | `anki_tan_icon_mono` | line art of `emoji/Ankitan_00_smile.png` |

The drawables are not committed: the build unpacks them from the
`android-drawables.zip` of the AnkiQuest-Art release pinned in
`ankiquest-art.json`, after checking its SHA-256.
The adaptive icons in `AnkiDroid/src/quest/res/` inset her app icon by 10%
so its frame stays outside the launcher's mask, and the faces by 18%, inside
the 72dp safe zone, as Aki's are.

The About screen and the README credit her.
