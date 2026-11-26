**What RWKV does.** RWKV is a small neural network that predicts how well you will remember a card. The RWKV scheduler is experimental and off by default: until you turn it on, nothing about your scheduling changes. On, it can pick which review cards to show you (RWKV-Instant) or set each card's next interval (RWKV-Curve), in the presets you choose. Everything else keeps the scheduler it had.

**Model files.** AnkiQuest ships no RWKV weights: they have no licence for redistribution. Under Settings → AnkiQuest → RWKV model files, Download fetches a size's models from the JSchoreels/anki-rwkv release on GitHub and checks each file's SHA-256 (Small: about 6.9 MB; Tiny: about 7.7 MB). Or choose files yourself: the release's zips or the weights.f32.bin files inside them. Importing one size deletes the other size's files. The weights are for personal and research use only (WEIGHTS-NOTICE.md of the release).

**Small and Tiny.** The two sizes are each about 1.3 MB on disk. They differ in how smart they are and in memory while running: the model keeps a little state per card, about 24 KB, so a 22,000-card collection needs about half a GB of RAM. AnkiQuest turns RWKV on only when a quarter of your phone's RAM covers the estimate: a 4 GB phone fits a 22,000-card collection, a 2 GB one does not.

**Turning it on.** Turn on Settings → AnkiQuest → RWKV scheduler, then choose which presets use it on each preset's deck options page: RWKV-Instant to pick your review cards, RWKV-Curve for intervals. Other presets are untouched.

**Turning it off.** Turn the RWKV scheduler switch off and every preset returns to the scheduler you set for it. A preset remembers its RWKV choice; set it back to your usual scheduler on the deck options page to leave RWKV behind.

**The statistics panel.** While RWKV runs, Statistics shows two more cards. RWKV forecast: how many cards are due, near and safe if you stop reviewing now, in an hour, in three hours and by tomorrow. RWKV recall now: each card's predicted recall, in bands, with how many sit below their desired retention.

What the weights are and how they were trained: the [RWKV weights guide](https://github.com/AnkiQuest/AnkiQuest-Anki/blob/master/docs/rwkv-weights.md) of AnkiQuest's Anki fork.
