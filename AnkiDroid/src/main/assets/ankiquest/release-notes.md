AnkiDroid with [AnkiQuest](https://github.com/AnkiQuest/AnkiQuest-Desktop): study, shared goals, and progress in one place.

**Moving to the new app.** This is the final update under `com.ichi2.anki.quest`. The next AnkiQuest installs alongside this one as `org.ankiquest.ankidroid`. On Android 9 and later, the official new app offers to move settings and sign-ins on first launch. On Android 7 and 8, sign in again in the new app. If both apps use the shared `AnkiDroid` folder, force-stop this app in Android settings before opening the collection in the new app. If the apps use separate folders, sync through AnkiWeb or export the collection here and import it there. Keep this app installed until you can open your collection in the new one.

**Study companion.** Settings → AnkiQuest → Study companion is its own screen. Ankilope joins you at level 30, Bluma after 10 hours of study and Anki-tan with a 66-day streak; locked companions show their condition and progress. The launcher icon shows your chosen companion. Aki greets you once after you install or update. Your companion's pose follows your streak on Today and the widgets: asleep, sad before today's study, hopeful after some, on fire once today counts, or frozen after a day a freeze kept.

**Today.** The streak flame stays unlit until today counts. A day counted from answers without review times, such as those AnkiQuest Lite reads, shows "Studied today · time not known". Friends' study stays hidden until you upload your first reviews, and the leaderboard, Records and the leaderboard widget say so. Between Monday and the weekly board's switch, Today and your profile show the XP already counting for next week. The Home card no longer repeats "study" while cards are due.

**Countdowns.** Today counts down to your events and exams, each with an optional deck. The list stays the same on all your devices, the desktop add-on included.

**Three tabs.** The bottom bar now has Decks, Today and Friends. Tap your avatar on Today to open Me, which holds Records, Study history and streak protection. Activity and Leaderboard are sections of Friends.

**Study time.** Me has a Study time section: today, this week, this month, total and per study day, from this collection's review history, uploaded or not.

**Review heatmap.** The heatmap says on what share of the days since your first review you studied, and "Today" jumps back to the current day. It is translated into German, Spanish, French and Portuguese.

**RWKV scheduler (experimental).** Off by default. RWKV, a neural network that predicts recall, can choose review cards (RWKV-Instant) or set answer intervals (RWKV-Curve), as the JSchoreels desktop fork does. AnkiQuest ships no RWKV weights, since they have no licence for redistribution. In Settings → AnkiQuest → RWKV model files, Download fetches the Small or Tiny models from JSchoreels/anki-rwkv's release on GitHub, or choose files you downloaded; only files that match the known models are kept. Then turn on RWKV scheduler and pick RWKV-Instant or RWKV-Curve as a preset's review scheduler in deck options. Turning it off hands scheduling back to FSRS at once. If a check fails when the app starts, for example because the model files were removed, it turns itself off and the setting says why. While it runs, Statistics shows an RWKV forecast and a chart of each card's recall.

**Optimize reminder.** When Optimize All Presets in deck options last ran at least 30 days and 1,000 reviews ago, a reminder offers Open deck options, Remind me in a week and Dismiss. For RWKV-Curve and RWKV-Instant presets it says that only their FSRS fallback is optimized and RWKV itself isn't trained. Turn it off with Settings → AnkiQuest → Remind me to optimize presets. A snooze, a dismissal and the switch apply on all your devices.

**Sharing.** One share sheet sends a moment to friends or groups in AnkiQuest, with an optional note, or to another app with "More apps…". It covers conquered cards, session moments, companion unlocks, your weekly place and finished decks. Undo takes back only that send.

**Player profiles.** From a player's profile you can send or answer a friend request, send a message, or invite them to a group.

**Deck alerts.** Each deck alert offers Stop for its sender, with Undo. Today's focus and deck copy use AnkiDroid's searchable deck list.

**Conquered cards.** Conquered cards show a readable deck name, which you can edit, shorten to a part of the path, or leave out. The suggested label comes from the fields on the card's front, without template text or ID fields. Two short fields, such as a word and its reading, are shown together. A lapse counts only when you pressed Again: the app sends your review history once more, with the button pressed, to a server that stores it.

**Sync settings.** Rank and streak notifications, the session recap, the mood icon, opening on Today, the deck-sharing prompt and the streak cue stay the same on all your devices, the desktop add-on included, on servers that support it.

**Sign-in.** Creating an account asks whether your profile is public, unticked: a public profile anyone can open and the global leaderboard lists. Change it later under Friends. Accounts with two-factor authentication ask for the authenticator or recovery code after the password. Manage account links to the website to set it up. Your sign-ins are listed as "AnkiQuest (your phone model)". Players signed in with a token from the server's configuration are asked to set a password. New accounts use ankiquest.org; saved accounts on the old address move there by themselves.

**Notifications.** Streak warnings wait until your quiet hours end.

**AnkiQuest name.** Screens and messages from AnkiDroid that named AnkiDroid say AnkiQuest, in every language AnkiDroid is translated into. Links to AnkiDroid's manual, its API and its contributors keep the name.

**Translations.** The last untranslated AnkiQuest texts, such as the leaderboard widget's settings, are now in German, Spanish, French and Portuguese.

**Rate and report.** About and the AnkiQuest changelog have "Rate AnkiQuest". "Report a bug" opens the bug form again instead of "No suitable app found".

**Streak widget.** Every size shows one flame, beside the number.

**Fixes.** A review on a freeze-protected day counts toward today instead of reading "not studied yet". A failed certificate check is named as such instead of "waiting for a connection". The share sheet's Send works and the sheet is translated. Deck name patterns no longer crash. Moments about a companion this build cannot draw wait for an update that can. Image occlusion notes keep every shape property after an empty value or a backslash. "Optimize all presets" asks to save only when the deck options changed.

**Server.** This release needs a current AnkiQuest server. On an older one, the app says "Update your AnkiQuest server".

This release includes the latest AnkiDroid changes. Companion pictures come from AnkiQuest-Art art-11.

Installs next to the official AnkiDroid as "AnkiQuest". Log in to AnkiWeb and sync to get your cards, then open Settings → AnkiQuest → Account → Sign in or create account. Later releases are offered inside the app.

Study uploads contain review history (card id, timestamp, previous interval, time taken, review type, the button pressed and your time-zone offset), never card content. Social actions send the invitation or message you choose to share.
