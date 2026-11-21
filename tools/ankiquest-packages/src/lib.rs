// SPDX-License-Identifier: AGPL-3.0-only
//! Moves AnkiQuest's Kotlin and Java code from `com.ichi2.anki.ankiquest` to
//! `org.ankiquest`, so the app names nothing of ours after AnkiDroid's
//! `com.ichi2.anki` (#461).
//!
//! The rename is textual and re-runnable:
//!
//! - every file under `src/<set>/<dir>/com/ichi2/anki/ankiquest/` in any
//!   module moves to `src/<set>/<dir>/org/ankiquest/`;
//! - every text file (Kotlin, Java, XML, Gradle, ProGuard, `META-INF/services`,
//!   workflows, docs) has `com.ichi2.anki.ankiquest`, `com/ichi2/anki/ankiquest`
//!   and the regex forms `com\.ichi2\.anki\.ankiquest` and
//!   `com\\.ichi2\\.anki\\.ankiquest` replaced, when the next
//!   character does not continue the name;
//! - Kotlin files it changed get their imports sorted again, in ktlint's order.
//!
//! Upstream AnkiDroid packages (`com.ichi2.anki` and its other subpackages),
//! the `api/` library, the generated `AGENTS.md` and `CLAUDE.md`, and build
//! output are left alone. A line containing [`KEEP_MARKER`] is never changed,
//! for names that must stay as installed apps stored them.

use std::collections::BTreeMap;
use std::fmt;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};

pub const OLD_PACKAGE: &str = "com.ichi2.anki.ankiquest";
pub const NEW_PACKAGE: &str = "org.ankiquest";
const OLD_PATH: &str = "com/ichi2/anki/ankiquest";
const NEW_PATH: &str = "org/ankiquest";
const OLD_ESCAPED: &str = r"com\.ichi2\.anki\.ankiquest";
const NEW_ESCAPED: &str = r"org\.ankiquest";
const OLD_STRING_ESCAPED: &str = r"com\\.ichi2\\.anki\\.ankiquest";
const NEW_STRING_ESCAPED: &str = r"org\\.ankiquest";

/// The spellings of the package and their replacements: as written, as a
/// path, as a regex, and as a regex inside a Kotlin or Java string.
const SPELLINGS: [(&str, &str); 4] = [
    (OLD_PACKAGE, NEW_PACKAGE),
    (OLD_PATH, NEW_PATH),
    (OLD_ESCAPED, NEW_ESCAPED),
    (OLD_STRING_ESCAPED, NEW_STRING_ESCAPED),
];

/// Lines with this text keep the old name (a stored class name, for example).
pub const KEEP_MARKER: &str = "ankiquest-packages: keep";

/// Directory names skipped wherever they appear: VCS data and build output.
const SKIPPED_NAMES: [&str; 7] = [
    ".git",
    ".gradle",
    ".idea",
    ".kotlin",
    "build",
    "node_modules",
    "target",
];

/// Paths from the repository root that are never touched.
/// `api/` is AnkiDroid's library that other apps compile against; `AGENTS.md`
/// and `CLAUDE.md` come from AnkiQuest-Agents; this tool names the old package.
const SKIPPED_PATHS: [&str; 4] = ["api", "AGENTS.md", "CLAUDE.md", "tools/ankiquest-packages"];

/// Larger files are data (fixtures, images), not code that names packages.
const MAX_TEXT_BYTES: u64 = 4 * 1024 * 1024;

/// Whether [`rename`] writes its changes or only reports them.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Mode {
    Apply,
    Check,
}

/// What one run changed, or would change in [`Mode::Check`].
#[derive(Debug, Default, PartialEq, Eq)]
pub struct Summary {
    /// Files moved to `org/ankiquest`, by top-level directory.
    pub moved: BTreeMap<String, usize>,
    /// Files whose text changed, by top-level directory.
    pub rewritten: BTreeMap<String, usize>,
    /// Lines left alone because they carry [`KEEP_MARKER`].
    pub kept: Vec<String>,
}

impl Summary {
    pub fn is_empty(&self) -> bool {
        self.moved.is_empty() && self.rewritten.is_empty()
    }
}

impl fmt::Display for Summary {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let total = |counts: &BTreeMap<String, usize>| counts.values().sum::<usize>();
        writeln!(f, "moved {} files to {NEW_PATH}", total(&self.moved))?;
        for (module, count) in &self.moved {
            writeln!(f, "  {module}: {count}")?;
        }
        writeln!(f, "rewrote {} files", total(&self.rewritten))?;
        for (module, count) in &self.rewritten {
            writeln!(f, "  {module}: {count}")?;
        }
        writeln!(f, "kept {} marked lines", self.kept.len())?;
        for line in &self.kept {
            writeln!(f, "  {line}")?;
        }
        Ok(())
    }
}

#[derive(Debug)]
pub enum Error {
    Io(PathBuf, io::Error),
    /// A file would move onto a different file already at the new path.
    Collision(PathBuf),
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::Io(path, error) => write!(f, "{}: {error}", path.display()),
            Error::Collision(path) => {
                write!(f, "{} already exists with other content", path.display())
            }
        }
    }
}

impl std::error::Error for Error {}

/// Renames the package in the repository at `root`.
pub fn rename(root: &Path, mode: Mode) -> Result<Summary, Error> {
    let mut files = Vec::new();
    collect(root, root, &mut files)?;

    let mut summary = Summary::default();
    let mut emptied = Vec::new();
    for relative in files {
        let source = root.join(&relative);
        let target_relative = moved_path(&relative);
        let module = top_level(&relative);

        // Rewrite the text first, so a moved file lands with its new package.
        let original = fs::read(&source).map_err(|e| Error::Io(source.clone(), e))?;
        let rewritten =
            text_of(&original).and_then(|text| rewrite_text(&relative, text, &mut summary.kept));
        if rewritten.is_some() {
            *summary.rewritten.entry(module.clone()).or_default() += 1;
        }
        let Some(target_relative) = target_relative else {
            if let (Some(text), Mode::Apply) = (rewritten, mode) {
                fs::write(&source, text).map_err(|e| Error::Io(source.clone(), e))?;
            }
            continue;
        };

        // Move the file; an identical file already there counts as moved.
        let target = root.join(&target_relative);
        let content = rewritten.map(String::into_bytes).unwrap_or(original);
        if let Ok(existing) = fs::read(&target)
            && existing != content
        {
            return Err(Error::Collision(target));
        }
        *summary.moved.entry(module).or_default() += 1;
        if mode == Mode::Check {
            continue;
        }
        if let Some(parent) = target.parent() {
            fs::create_dir_all(parent).map_err(|e| Error::Io(parent.to_path_buf(), e))?;
        }
        fs::write(&target, content).map_err(|e| Error::Io(target.clone(), e))?;
        fs::remove_file(&source).map_err(|e| Error::Io(source.clone(), e))?;
        emptied.push(source);
    }

    for file in emptied {
        remove_empty_parents(root, &file);
    }
    Ok(summary)
}

/// Every file under `dir` that the rename may touch, relative to `root`.
fn collect(root: &Path, dir: &Path, files: &mut Vec<PathBuf>) -> Result<(), Error> {
    let entries = fs::read_dir(dir).map_err(|e| Error::Io(dir.to_path_buf(), e))?;
    let mut paths = Vec::new();
    for entry in entries {
        let entry = entry.map_err(|e| Error::Io(dir.to_path_buf(), e))?;
        paths.push(entry.path());
    }
    paths.sort();

    for path in paths {
        let relative = path.strip_prefix(root).unwrap_or(&path).to_path_buf();
        if is_skipped(&relative) {
            continue;
        }
        let kind = fs::symlink_metadata(&path).map_err(|e| Error::Io(path.clone(), e))?;
        if kind.is_dir() {
            collect(root, &path, files)?;
        } else if kind.is_file() {
            files.push(relative);
        }
    }
    Ok(())
}

fn is_skipped(relative: &Path) -> bool {
    let name = relative.file_name().and_then(|n| n.to_str()).unwrap_or("");
    if SKIPPED_NAMES.contains(&name) {
        return true;
    }
    let slashed = slashed(relative);
    SKIPPED_PATHS.contains(&slashed.as_str())
}

fn slashed(relative: &Path) -> String {
    relative
        .components()
        .map(|c| c.as_os_str().to_string_lossy())
        .collect::<Vec<_>>()
        .join("/")
}

fn top_level(relative: &Path) -> String {
    let slashed = slashed(relative);
    match slashed.split_once('/') {
        Some((first, _)) => first.to_string(),
        None => ".".to_string(),
    }
}

/// The new path of a source file under a `com/ichi2/anki/ankiquest` directory.
pub fn moved_path(relative: &Path) -> Option<PathBuf> {
    let slashed = slashed(relative);
    let directory = format!("/{OLD_PATH}/");
    let at = slashed.find(&directory)?;
    if !slashed[..at].contains("src/") && !slashed.starts_with("src/") {
        return None;
    }
    let moved = format!(
        "{}/{NEW_PATH}/{}",
        &slashed[..at],
        &slashed[at + directory.len()..]
    );
    Some(PathBuf::from(moved))
}

/// The file as text, or `None` for binary and very large files.
fn text_of(bytes: &[u8]) -> Option<&str> {
    if bytes.len() as u64 > MAX_TEXT_BYTES || bytes.contains(&0) {
        return None;
    }
    std::str::from_utf8(bytes).ok()
}

/// The rewritten text, or `None` when nothing changed. Marked lines are
/// recorded in `kept`.
fn rewrite_text(relative: &Path, text: &str, kept: &mut Vec<String>) -> Option<String> {
    if !SPELLINGS.iter().any(|(old, _)| text.contains(old)) {
        return None;
    }

    let mut out = String::with_capacity(text.len());
    for line in text.split_inclusive('\n') {
        if line.contains(KEEP_MARKER) {
            if SPELLINGS.iter().any(|(old, _)| line.contains(old)) {
                kept.push(format!("{}: {}", slashed(relative), line.trim()));
            }
            out.push_str(line);
            continue;
        }
        out.push_str(&replace_line(line));
    }

    if relative
        .extension()
        .is_some_and(|e| e == "kt" || e == "kts")
    {
        out = sort_imports(&out);
    }
    (out != text).then_some(out)
}

/// Replaces every spelling of the package in one line where the name ends.
fn replace_line(line: &str) -> String {
    let mut line = line.to_string();
    for (old, new) in SPELLINGS {
        line = replace_whole(&line, old, new);
    }
    line
}

/// Replaces `old` by `new` where it is not part of a longer name.
pub fn replace_whole(text: &str, old: &str, new: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut rest = text;
    while let Some(at) = rest.find(old) {
        let before = rest[..at].chars().next_back();
        let after = rest[at + old.len()..].chars().next();
        let whole = !before.is_some_and(continues_name) && !after.is_some_and(continues_name);
        out.push_str(&rest[..at]);
        out.push_str(if whole { new } else { old });
        rest = &rest[at + old.len()..];
    }
    out.push_str(rest);
    out
}

fn continues_name(c: char) -> bool {
    c.is_alphanumeric() || c == '_'
}

/// Sorts the Kotlin import list in ktlint's default layout
/// (`*,java.**,javax.**,kotlin.**,^`): other imports, then java, javax,
/// kotlin, then aliased imports, each group by path.
pub fn sort_imports(text: &str) -> String {
    let lines: Vec<&str> = text.split_inclusive('\n').collect();
    let Some(start) = lines.iter().position(|l| l.starts_with("import ")) else {
        return text.to_string();
    };
    let end = lines[start..]
        .iter()
        .position(|l| !l.starts_with("import "))
        .map_or(lines.len(), |n| start + n);

    let mut imports: Vec<&str> = lines[start..end].to_vec();
    imports.sort_by_cached_key(|line| import_key(line));
    imports.dedup();

    let mut out = String::with_capacity(text.len());
    lines[..start].iter().for_each(|l| out.push_str(l));
    imports.iter().for_each(|l| out.push_str(l));
    lines[end..].iter().for_each(|l| out.push_str(l));
    out
}

fn import_key(line: &str) -> (u8, String) {
    let path = line.trim_start_matches("import ").trim().replace('`', "");
    let group = if path.contains(" as ") {
        4
    } else if path.starts_with("java.") {
        1
    } else if path.starts_with("javax.") {
        2
    } else if path.starts_with("kotlin.") {
        3
    } else {
        0
    };
    (group, path)
}

/// Removes directories left empty by a move, up to the source-set root.
fn remove_empty_parents(root: &Path, file: &Path) {
    let mut dir = file.parent();
    while let Some(current) = dir {
        if current == root || fs::remove_dir(current).is_err() {
            return;
        }
        dir = current.parent();
    }
}
