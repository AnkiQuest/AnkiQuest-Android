# Bluma core art

Original AnkiQuest purple cow, generated with OpenAI `image_gen` on
2026-11-16. The tool did not expose its model identifier. The repository
licence applies. Aki and Ankilope supplied the illustration style; the
selected happy candidate supplied Bluma's identity for the other poses.

[Desktop PR #322](https://github.com/AnkiQuest/AnkiQuest-Desktop/pull/322)
contains the eight 1254 px masters under `static/bluma/`, all 24 prompts,
selections, style and pose prompts, alpha-cleanup measurements and review
comparisons. Selections: face C, asleep A, happy C, streak B, sad C,
hopeful B, frozen B and neglected B. Aldana's appearance approval is pending.

These eight 1024 px WebPs are byte-identical to the canonical output of
`export-android-art` in
[run 43793906258](https://github.com/AnkiQuest/AnkiQuest-Desktop/actions/runs/43793906258),
source commit `d7b7b96e7c7a50ddb7559f898ef2b16932f63367`.
The matching desktop PNGs and export manifest are committed in that PR.

The canonical names are now `bluma_asleep` and `bluma_happy`, from
[run 43866857494](https://github.com/AnkiQuest/AnkiQuest-Desktop/actions/runs/43866857494),
source `8c3bcf00e2964614ba668cf1ac2c816f9480a388`. They have identical bytes
to the earlier `bluma_sleeping` and `bluma_celebrate` files, which remain
unchanged. Active masters use fixture names under `static/cow/`.
The remaining names match the core pose names. Existing art is preserved.
The separate resource keep file retains these drawables through resource
shrinking. Companion selection and unlock activation remain with the
contributor maintaining those rules; this change supplies assets only.

Part of [#340](https://github.com/AnkiQuest/AnkiQuest-Android/issues/340)
and [Desktop #88](https://github.com/AnkiQuest/AnkiQuest-Desktop/issues/88).

| File | SHA-256 |
|---|---|
| bluma_face.webp | 79ab824c187a5df93a9b0588ea6b553f0b3c7888d6135a3a8abb7e9a61820027 |
| bluma_sleeping.webp | c12da35455cdeb36b1802f09fadf833807a5d30b81cabd969fa0d73461d8c7af |
| bluma_asleep.webp | c12da35455cdeb36b1802f09fadf833807a5d30b81cabd969fa0d73461d8c7af |
| bluma_celebrate.webp | 44dde7c166fe83abea67f59f8ba76796e1de3634dbd1d66ca87e200248c9e485 |
| bluma_happy.webp | 44dde7c166fe83abea67f59f8ba76796e1de3634dbd1d66ca87e200248c9e485 |
| bluma_streak.webp | 3b26426aa293b5eb0f950535eeee7be67103e3ec6652199047b9bc81685702f7 |
| bluma_sad.webp | b84e998029b640e700b71d39fad1a11b36f48fa9f770133b2b61d81cc90a374d |
| bluma_hopeful.webp | 78b6d6b7665674617587920db9f3de88bce9f71c81bbef3ee148aa8671fb5472 |
| bluma_frozen.webp | d7bfb4a55a9355a65eefb7697365d88f50948cc6c7f711360248fe07fb0d7595 |
| bluma_neglected.webp | 1f76c316d369d72c0a03a34af4213b875952ad5b6eb93163a89b516c0eb27e96 |
