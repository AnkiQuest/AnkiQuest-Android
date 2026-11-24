# Workflows

GitHub Actions is used to automate parts of our development workflow.

See https://docs.github.com/en/actions for more information.

## Quality Checks

We run checks on all Pull Requests (PRs). We recommend that when appropriate, developers run the 
following scripts before submitting a PR.

Alternately, you may run the actions on your fork of `Anki-Android`.

| **Job**                                                                                                    | **Command**                                                          | **Comments**                                                                                                                                             |
|------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------|
| [Lint (Kotlin)](lint.yml)            | `./gradlew lintAll ktLintCheck lint-rules:test --daemon`             | Android lint rules, formatting and tests for lint rules                                                                                                  |
| [Lint (JavaScript)](lint.yml)        | See script                                                           | Prettier, lint & code formatting                                                                                                                         |
| [Build Logic](lint.yml)              | `./gradlew -p buildSrc test` and `tools/validate-version-code.sh`    | Version-code bump checks                                                                                                                                 |
| [Unit Tests](tests_unit.yml)         | `./gradlew jacocoUnitTestReport --daemon`<br/>PRs also run `./gradlew :AnkiDroid:testPlayDebugUnitTest -PankiquestUpdates=github --tests 'com.ichi2.anki.ankiquest.AnkiquestUpdaterTest' --daemon` on Ubuntu | Unit tests for the Android Project                                                                                                                       |
| [Emulator Tests](tests_emulator.yml) | `TEST_RELEASE_BUILD=true ./gradlew jacocoAndroidTestReport --daemon` | Emulator tests for the Android Project.<br/>CI runs `bash .github/disable_art_metrics.sh` and `adb shell settings put global hide_error_dialogs 1` first.<br/>Then runs `ReleaseSmokeTest` from `:baselineprofile` with `TEST_RELEASE_BUILD=false` |
| [CodeQL](codeql.yml)                 | N/A                                                                  | GitHub-only check.<br/>[Docs](https://codeql.github.com/)                                                                                                |

## Other Workflows

`lint.yml`, `tests_unit.yml`, `tests_emulator.yml` and the three
`screenshot_*.yml` workflows are upstream's files with the fork's changes
(`master` branch, Ubuntu-only pull request runs, concurrency, Gradle retries,
manual screenshot baselines). Upstream merges conflict on them; keep the
fork's lines and take upstream's other changes.

The fork's [pinned AnkiQuest server snapshot](../../docs/ankiquest-server-snapshot.md)
documents `ankiquest_api_contract.yml`, `ankiquest_fixtures.yml` and the daily
`ankiquest_bump_server.yml`. Normal builds use committed data; server updates
arrive on `bot/server-bump` pull requests.

`ankiquest_labels.yml` runs AnkiQuest-Dev's shared labelling workflows; the
label set is `labels.json` there.

These are typically run by maintainers. See the [Maintenance guide](https://github.com/ankidroid/Anki-Android/wiki/Maintenance-guide)

### Merge queue cancellation

GitHub can leave jobs running after their merge group has been removed.

`cancel_merge_queue_runs.yml` is triggered when a temporary merge queue branch is deleted.
The workflow cancels unfinished runs for unmerged groups.

### Automatic publish

An automatic alpha publish is performed every Monday at 04:30 UTC. This publish is skipped if:

* A publish occurred fewer than 7 UTC calendar days ago.
* The app is not in alpha.
* There were no commits since the last release.

An alpha published on Monday does not block the following Monday's release, regardless of the time it finished.
