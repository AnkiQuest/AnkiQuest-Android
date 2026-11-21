#!/bin/sh
# SPDX-License-Identifier: AGPL-3.0-only
# Renames com.ichi2.anki.ankiquest to org.ankiquest, then lets ktlint rejoin
# lines that the shorter name now fits on. Run from the repository root.
set -eu
cargo run --quiet --manifest-path tools/ankiquest-packages/Cargo.toml -- "$@"
./gradlew ktlintFormat --quiet
