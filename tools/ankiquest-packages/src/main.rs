// SPDX-License-Identifier: AGPL-3.0-or-later
//! Moves AnkiQuest's code from `com.ichi2.anki.ankiquest` to `org.ankiquest`.
//!
//! ```sh
//! # From the repository root: rename, then print what changed.
//! cargo run --manifest-path tools/ankiquest-packages/Cargo.toml
//! # Only report; exits 1 when anything is left to rename.
//! cargo run --manifest-path tools/ankiquest-packages/Cargo.toml -- --check
//! ```
//!
//! An optional path argument names the repository root (default: the current
//! directory). Running it twice changes nothing the second time.

use std::path::PathBuf;
use std::process::ExitCode;

use ankiquest_packages::{Mode, rename};

const USAGE: &str = "usage: ankiquest-packages [--check] [repository root]";

fn main() -> ExitCode {
    let mut mode = Mode::Apply;
    let mut root = PathBuf::from(".");
    for argument in std::env::args().skip(1) {
        match argument.as_str() {
            "--check" => mode = Mode::Check,
            "-h" | "--help" => {
                println!("{USAGE}");
                return ExitCode::SUCCESS;
            }
            path => root = PathBuf::from(path),
        }
    }

    // A root without settings.gradle.kts is not the app's repository.
    if !root.join("settings.gradle.kts").is_file() {
        eprintln!("{} is not the repository root\n{USAGE}", root.display());
        return ExitCode::from(2);
    }

    let summary = match rename(&root, mode) {
        Ok(summary) => summary,
        Err(error) => {
            eprintln!("error: {error}");
            return ExitCode::from(2);
        }
    };
    print!("{summary}");
    if mode == Mode::Check && !summary.is_empty() {
        eprintln!("com.ichi2.anki.ankiquest is still in use: run without --check");
        return ExitCode::FAILURE;
    }
    ExitCode::SUCCESS
}
