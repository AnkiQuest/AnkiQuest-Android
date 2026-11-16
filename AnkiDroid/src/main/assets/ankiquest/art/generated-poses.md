Anki-tan by Shigeyuki (https://www.patreon.com/Shigeyuki).

anki_tan_frozen.webp and anki_tan_chibi_frozen.webp are generated
derivatives of the Anki-tan asset pack, licensed CC BY-SA 4.0.
See LICENSE-Anki-tan.txt in this directory.

These and aki_sad.webp, aki_hopeful.webp, aki_frozen.webp and
aki_neglected.webp were generated with OpenAI's built-in image_gen tool. The tool did not expose its model identifier. Their masters, prompts
and cleanup record live in AnkiQuest/AnkiQuest-Desktop, under
docs/branding/core-poses-2026-11/. Exports use that repository's Rust tool.

Linux exports are canonical until the exporter is platform-independent.

The full-size Anki-tan additions (anki_tan_sleeping.webp,
anki_tan_sad.webp and anki_tan_neglected.webp) are generated derivatives
under the same CC BY-SA 4.0 licence and credit above. Their masters,
nine candidate prompts and cleanup measurements are in
AnkiQuest/AnkiQuest-Desktop docs/branding/ankitan-full-2026-11/.
The owner approved all three on 2026-11-16:
https://github.com/AnkiQuest/AnkiQuest-Desktop/issues/88#issuecomment-6904846793

These files match the Linux export artifact from commit
e8794893e539622078dbbdab4b4ffdd2a20c2e78 byte for byte:
https://github.com/AnkiQuest/AnkiQuest-Desktop/actions/runs/43789530883
They were inspected on light and dark backgrounds at 64 and 128 px.
Pose selection is maintained separately from these asset additions.

The canonical asleep name is now anki_tan_asleep.webp. It comes from
Linux export run 43866525279, source 10acab35622c12651086586c8dcbf333a0eb19e8:
https://github.com/AnkiQuest/AnkiQuest-Desktop/actions/runs/43866525279
Its bytes match the earlier sleeping export exactly; that file remains
unchanged. Active masters use the fixture pose names under static/anki-tan/,
with chibi derivatives in static/anki-tan/chibi/, under the same licence.
