// SPDX-License-Identifier: AGPL-3.0-or-later
//! Runs the rename on small repositories written to a temporary directory.

use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicUsize, Ordering};

use ankiquest_packages::{Error, KEEP_MARKER, Mode, rename, replace_whole, sort_imports};

/// A fresh directory per test, removed when dropped.
struct Repo(PathBuf);

impl Repo {
    fn new() -> Repo {
        static NEXT: AtomicUsize = AtomicUsize::new(0);
        let n = NEXT.fetch_add(1, Ordering::SeqCst);
        let dir =
            std::env::temp_dir().join(format!("ankiquest-packages-{}-{n}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("settings.gradle.kts"), "include(\":AnkiDroid\")\n").unwrap();
        Repo(dir)
    }

    fn write(&self, path: &str, text: &str) {
        let file = self.0.join(path);
        fs::create_dir_all(file.parent().unwrap()).unwrap();
        fs::write(file, text).unwrap();
    }

    fn read(&self, path: &str) -> String {
        fs::read_to_string(self.0.join(path)).unwrap()
    }

    fn exists(&self, path: &str) -> bool {
        self.0.join(path).exists()
    }

    fn root(&self) -> &Path {
        &self.0
    }
}

impl Drop for Repo {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

const OLD_MAIN: &str = "AnkiDroid/src/main/java/com/ichi2/anki/ankiquest";
const NEW_MAIN: &str = "AnkiDroid/src/main/java/org/ankiquest";

#[test]
fn moves_sources_and_rewrites_package_and_imports() {
    let repo = Repo::new();
    repo.write(
        &format!("{OLD_MAIN}/Ankiquest.kt"),
        "package com.ichi2.anki.ankiquest\n\nimport com.ichi2.anki.R\nimport com.ichi2.anki.ankiquest.heatmap.Grid\n\nobject Ankiquest\n",
    );
    repo.write(
        &format!("{OLD_MAIN}/heatmap/Grid.kt"),
        "package com.ichi2.anki.ankiquest.heatmap\n\nclass Grid\n",
    );
    repo.write(
        "AnkiDroid/src/test/java/com/ichi2/anki/ankiquest/AnkiquestTest.kt",
        "package com.ichi2.anki.ankiquest\n\nclass AnkiquestTest\n",
    );

    let summary = rename(repo.root(), Mode::Apply).unwrap();

    assert_eq!(summary.moved.get("AnkiDroid"), Some(&3));
    assert!(!repo.exists("AnkiDroid/src/main/java/com"));
    assert!(!repo.exists("AnkiDroid/src/test/java/com"));
    assert_eq!(
        repo.read(&format!("{NEW_MAIN}/Ankiquest.kt")),
        "package org.ankiquest\n\nimport com.ichi2.anki.R\nimport org.ankiquest.heatmap.Grid\n\nobject Ankiquest\n",
    );
    assert_eq!(
        repo.read(&format!("{NEW_MAIN}/heatmap/Grid.kt")),
        "package org.ankiquest.heatmap\n\nclass Grid\n"
    );
    assert!(repo.exists("AnkiDroid/src/test/java/org/ankiquest/AnkiquestTest.kt"));
}

#[test]
fn moves_sources_of_every_module() {
    let repo = Repo::new();
    repo.write(
        "ankiquest-ui/src/main/java/com/ichi2/anki/ankiquest/ui/Screen.kt",
        "package com.ichi2.anki.ankiquest.ui\n\nimport com.ichi2.anki.ankiquest.ui.R\n\nclass Screen\n",
    );
    repo.write(
        "ankiquest-ui/build.gradle.kts",
        "android {\n    namespace = \"com.ichi2.anki.ankiquest.ui\"\n}\n",
    );

    rename(repo.root(), Mode::Apply).unwrap();

    assert_eq!(
        repo.read("ankiquest-ui/src/main/java/org/ankiquest/ui/Screen.kt"),
        "package org.ankiquest.ui\n\nimport org.ankiquest.ui.R\n\nclass Screen\n",
    );
    assert_eq!(
        repo.read("ankiquest-ui/build.gradle.kts"),
        "android {\n    namespace = \"org.ankiquest.ui\"\n}\n"
    );
}

#[test]
fn rewrites_references_outside_kotlin() {
    let repo = Repo::new();
    let files = [
        (
            "AnkiDroid/src/main/AndroidManifest.xml",
            "<receiver android:name=\"com.ichi2.anki.ankiquest.AnkiquestWidget\" />\n",
            "<receiver android:name=\"org.ankiquest.AnkiquestWidget\" />\n",
        ),
        (
            "AnkiDroid/src/main/res/layout/view.xml",
            "<com.ichi2.anki.ankiquest.heatmap.ReviewHeatmapGrid />\n",
            "<org.ankiquest.heatmap.ReviewHeatmapGrid />\n",
        ),
        (
            "AnkiDroid/proguard-rules.pro",
            "-keepnames class com.ichi2.anki.ankiquest.**\n",
            "-keepnames class org.ankiquest.**\n",
        ),
        (
            "AnkiDroid/src/main/resources/META-INF/services/org.acra.collector.Collector",
            "com.ichi2.anki.ankiquest.Collector\n",
            "org.ankiquest.Collector\n",
        ),
        (
            ".github/workflows/ankiquest_api_contract.yml",
            "run: ./gradlew test --tests 'com.ichi2.anki.ankiquest.AnkiquestApiContractTest'\n",
            "run: ./gradlew test --tests 'org.ankiquest.AnkiquestApiContractTest'\n",
        ),
        (
            ".github/workflows/ankiquest_banner.yml",
            "- 'AnkiDroid/src/test/java/com/ichi2/anki/ankiquest/AnkiquestBannerScreenshots.kt'\n",
            "- 'AnkiDroid/src/test/java/org/ankiquest/AnkiquestBannerScreenshots.kt'\n",
        ),
        (
            "AnkiDroid/src/test/java/com/ichi2/anki/IconTest.kt",
            r#"val r = Regex("com\\.ichi2\\.anki\\.ankiquest\\.(\\w+)")
"#,
            r#"val r = Regex("org\\.ankiquest\\.(\\w+)")
"#,
        ),
        (
            ".github/tests/a.test.cjs",
            "/com\\.ichi2\\.anki\\.ankiquest\\b/\n",
            "/org\\.ankiquest\\b/\n",
        ),
        (
            "AnkiDroid/src/main/java/com/ichi2/anki/Icon.kt",
            "val name = \"com.ichi2.anki.ankiquest.${alias}\"\n",
            "val name = \"org.ankiquest.${alias}\"\n",
        ),
    ];
    for (path, before, _) in files {
        repo.write(path, before);
    }

    rename(repo.root(), Mode::Apply).unwrap();

    for (path, _, after) in files {
        assert_eq!(repo.read(path), after, "{path}");
    }
}

#[test]
fn leaves_upstream_packages_and_the_api_library_alone() {
    let repo = Repo::new();
    let untouched = [
        (
            "AnkiDroid/src/main/java/com/ichi2/anki/DeckPicker.kt",
            "package com.ichi2.anki\n\nimport com.ichi2.anki.ankiquestion.Q\n",
        ),
        (
            "AnkiDroid/src/main/java/com/ichi2/anki/Other.kt",
            "val a = \"com.ichi2.anki.ankiquest_widget\"\nval b = \"xcom.ichi2.anki.ankiquest\"\n",
        ),
        (
            "api/src/main/java/com/ichi2/anki/ankiquest/Api.kt",
            "package com.ichi2.anki.ankiquest\n",
        ),
        (
            "AGENTS.md",
            "AnkiDroid/src/main/java/com/ichi2/anki/ankiquest/\n",
        ),
        (
            "AnkiDroid/build/generated/com/ichi2/anki/ankiquest/R.kt",
            "package com.ichi2.anki.ankiquest\n",
        ),
    ];
    for (path, text) in untouched {
        repo.write(path, text);
    }

    let summary = rename(repo.root(), Mode::Apply).unwrap();

    assert!(summary.is_empty(), "{summary}");
    for (path, text) in untouched {
        assert_eq!(repo.read(path), text, "{path}");
    }
}

#[test]
fn keeps_marked_lines() {
    let repo = Repo::new();
    let text = format!(
        "package com.ichi2.anki.ankiquest\n\nval legacy = \"com.ichi2.anki.ankiquest.PollWorker\" // {KEEP_MARKER}\n"
    );
    repo.write(&format!("{OLD_MAIN}/Poll.kt"), &text);

    let summary = rename(repo.root(), Mode::Apply).unwrap();

    assert_eq!(
        repo.read(&format!("{NEW_MAIN}/Poll.kt")),
        text.replacen(
            "package com.ichi2.anki.ankiquest",
            "package org.ankiquest",
            1
        ),
    );
    assert_eq!(summary.kept.len(), 1);
}

#[test]
fn second_run_changes_nothing() {
    let repo = Repo::new();
    repo.write(
        &format!("{OLD_MAIN}/A.kt"),
        "package com.ichi2.anki.ankiquest\n",
    );
    repo.write(
        "AnkiDroid/src/main/AndroidManifest.xml",
        "<activity android:name=\"com.ichi2.anki.ankiquest.A\" />\n",
    );

    assert!(!rename(repo.root(), Mode::Check).unwrap().is_empty());
    assert!(repo.exists(&format!("{OLD_MAIN}/A.kt")), "check mode wrote");
    assert!(!rename(repo.root(), Mode::Apply).unwrap().is_empty());
    let manifest = repo.read("AnkiDroid/src/main/AndroidManifest.xml");

    assert!(rename(repo.root(), Mode::Check).unwrap().is_empty());
    assert!(rename(repo.root(), Mode::Apply).unwrap().is_empty());
    assert_eq!(
        repo.read("AnkiDroid/src/main/AndroidManifest.xml"),
        manifest
    );
}

#[test]
fn picks_up_files_added_at_the_old_path_later() {
    let repo = Repo::new();
    repo.write(
        &format!("{OLD_MAIN}/A.kt"),
        "package com.ichi2.anki.ankiquest\n",
    );
    rename(repo.root(), Mode::Apply).unwrap();

    // A pull request merged after the rename adds a file at the old path.
    repo.write(
        &format!("{OLD_MAIN}/B.kt"),
        "package com.ichi2.anki.ankiquest\n",
    );
    let summary = rename(repo.root(), Mode::Apply).unwrap();

    assert_eq!(summary.moved.get("AnkiDroid"), Some(&1));
    assert_eq!(
        repo.read(&format!("{NEW_MAIN}/B.kt")),
        "package org.ankiquest\n"
    );
    assert_eq!(
        repo.read(&format!("{NEW_MAIN}/A.kt")),
        "package org.ankiquest\n"
    );
}

#[test]
fn refuses_to_overwrite_a_different_file() {
    let repo = Repo::new();
    repo.write(
        &format!("{OLD_MAIN}/A.kt"),
        "package com.ichi2.anki.ankiquest\n\nclass A\n",
    );
    repo.write(
        &format!("{NEW_MAIN}/A.kt"),
        "package org.ankiquest\n\nclass Other\n",
    );

    let error = rename(repo.root(), Mode::Apply).unwrap_err();

    assert!(matches!(error, Error::Collision(_)), "{error}");
    assert!(repo.exists(&format!("{OLD_MAIN}/A.kt")));
}

#[test]
fn sorts_moved_imports_into_place() {
    let repo = Repo::new();
    repo.write(
        "AnkiDroid/src/main/java/com/ichi2/anki/DeckPicker.kt",
        "package com.ichi2.anki\n\nimport android.os.Bundle\nimport com.ichi2.anki.ankiquest.Ankiquest\nimport com.ichi2.utils.A\nimport timber.log.Timber\nimport java.io.File\nimport kotlin.math.max\n\nclass DeckPicker\n",
    );

    rename(repo.root(), Mode::Apply).unwrap();

    assert_eq!(
        repo.read("AnkiDroid/src/main/java/com/ichi2/anki/DeckPicker.kt"),
        "package com.ichi2.anki\n\nimport android.os.Bundle\nimport com.ichi2.utils.A\nimport org.ankiquest.Ankiquest\nimport timber.log.Timber\nimport java.io.File\nimport kotlin.math.max\n\nclass DeckPicker\n",
    );
}

#[test]
fn sorting_keeps_aliases_last_and_sorted_files_unchanged() {
    let sorted = "import a.B\nimport java.io.File\nimport javax.inject.Inject\nimport kotlin.time.Duration\nimport a.C as D\n\nval x = 1\n";
    assert_eq!(sort_imports(sorted), sorted);
    assert_eq!(sort_imports("val x = 1\n"), "val x = 1\n");
}

#[test]
fn replaces_only_whole_names() {
    assert_eq!(
        replace_whole("a.b.c a.b.cd xa.b.c a.b.c.D", "a.b.c", "z"),
        "z a.b.cd xa.b.c z.D"
    );
}
