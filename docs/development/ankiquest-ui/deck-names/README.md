# Conquest deck names

Part of [#247](https://github.com/AnkiQuest/AnkiQuest-Android/issues/247).

The conquest screen and session card offer the same editable deck name. Players
can use a meaningful path component, type their own name or omit the deck. Choices
stay on the device until explicitly shared and are remembered per home-deck ID,
AnkiQuest account and collection.

Each accepted upload batch records a newly noticed conquest's home deck. Later
moves, filtered study and failure of a subsequent upload batch do not replace or
discard that record. A conquest from before this feature falls back to its current
home deck when no snapshot exists.

The cleaner follows `ankiquest/deck_names.json`, copied with the complete fixture
and API snapshot from server commit
`5714b76375d5b9c54095cd72380466ca3495b884` (merged server PRs #247/#248).
Its 20 cases include supplementary characters at the 120-character limit and
short leaves that need their parent. The editor uses the same character count.

## Native captures

`AnkiquestDeckNameScreenshotTest` covers the picker at normal and 200% font sizes,
a 320×568 dp phone, and both entry points in light and dark themes. Geometry checks
keep actions reachable, words intact and the selected deck name untruncated. The
session's actions stack vertically so Share cannot wrap letter by letter.

```sh
./gradlew :AnkiDroid:testPlayDebugUnitTest \
  --tests 'com.ichi2.anki.ankiquest.AnkiquestDeckNameScreenshotTest' \
  -Pscreenshot -Ptheme=light,dark -Proborazzi.test.record=true
```

Run ordinary regression tests separately without `-Pscreenshot`; screenshot mode
selects screenshot tests only. Native rendering does not replace physical-device
keyboard, TalkBack or Accessibility Scanner checks.

14 native cases passed; all 22 captures were reviewed, including the scrolled
actions at 200% font size. The four conquest control captures match their top
captures byte for byte.

| Capture | Light | Dark |
|---|---|---|
| deck_name | [View](deck_name.png) | [View](dark_deck_name.png) |
| deck_name_small_phone | [View](deck_name_small_phone.png) | [View](dark_deck_name_small_phone.png) |
| deck_name_200percent | [View](deck_name_200percent.png) | [View](dark_deck_name_200percent.png) |
| conquest_deck_top | [View](conquest_deck_top.png) | [View](dark_conquest_deck_top.png) |
| conquest_deck_200percent_top | [View](conquest_deck_200percent_top.png) | [View](dark_conquest_deck_200percent_top.png) |
| moment_deck | [View](moment_deck.png) | [View](dark_moment_deck.png) |
| moment_deck_actions | [View](moment_deck_actions.png) | [View](dark_moment_deck_actions.png) |
| moment_deck_200percent | [View](moment_deck_200percent.png) | [View](dark_moment_deck_200percent.png) |
| moment_deck_200percent_actions | [View](moment_deck_200percent_actions.png) | [View](dark_moment_deck_200percent_actions.png) |

After integration with the current share sheet and picture download actions, all
140 affected ordinary/API tests and ktlint pass. The 14 native cases pass again;
all 22 captures were refreshed and reviewed.
A regression also verifies that a pending lookup cannot replace a deck edited
during another foreground visit.
