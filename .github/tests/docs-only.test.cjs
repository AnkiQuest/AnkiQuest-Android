// SPDX-License-Identifier: AGPL-3.0-or-later
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const workflows = path.join(__dirname, "../workflows");

// What counts as documentation in this repository. Not the shared default `**/*.md`: the app's
// assets ship in the APK, and AnkiDroid/src/main/assets/ankiquest/release-notes.md is read by a
// unit test and is the release's notes.
const DOCS = [
    "*.md",
    "*/README.md",
    "*/*/README.md",
    ".agents/**/*.md",
    ".github/workflows/README.md",
    ".github/ISSUE_TEMPLATE/**",
    ".github/pull_request_template.md",
    ".idea/**/*.md",
    ".oss-scanner/*.md",
    "LICENSE*",
    "LICENSES/**",
    "docs/**",
];

const CONSOLIDATED = ["lint.yml", "tests_emulator.yml", "tests_unit.yml", "screenshot_compare.yml", "screenshot_store.yml"];

const files = fs
    .readdirSync(workflows)
    .filter(name => /^ankiquest_.*\.yml$/.test(name) || CONSOLIDATED.includes(name))
    .map(name => ({ name, text: fs.readFileSync(path.join(workflows, name), "utf8").replaceAll("\r\n", "\n") }));

// The globs of every `docs: |` input and every paths-ignore list in a workflow.
function lists(text) {
    const found = [];
    for (const match of text.matchAll(/^( *)docs: \|\n((?:\1 {2}.*\n)+)/gm)) {
        found.push(match[2].split("\n").map(line => line.trim()).filter(Boolean));
    }
    for (const match of text.matchAll(/^( *)paths-ignore:\n((?:\1 {2}- .*\n)+)/gm)) {
        found.push(match[2].split("\n").map(line => line.trim().replace(/^- '(.*)'$/, "$1")).filter(Boolean));
    }
    return found;
}

for (const { name, text } of files) {
    test(`${name}: documentation globs match the repository's list`, () => {
        for (const list of lists(text)) {
            assert.deepEqual(list, DOCS);
        }
    });

    // A path filter would leave a required check or a queue entry pending forever.
    test(`${name}: no path filters on a workflow that runs in the merge queue`, () => {
        if (!/^ {2}merge_group:/m.test(text)) {
            return;
        }
        assert.doesNotMatch(text, /^ {4}paths(-ignore)?:/m);
    });
}

test("the required unit test job skips steps, never itself", () => {
    const unit = files.find(file => file.name === "tests_unit.yml").text;
    const job = unit.slice(unit.indexOf("\n  unit:\n"));
    const condition = job.match(/^ {4}if: (.*)$/m)[1];
    assert.doesNotMatch(condition, /changes/);
    assert.match(job, /^ {8}run: \.\/gradlew jacocoUnitTestReport/m);
    assert.match(job, /- name: Run Unit Tests\n {8}if: needs\.changes\.outputs\.code != 'false'\n/);
});

test("every workflow that skips documentation is covered", () => {
    const skipping = files.filter(({ text }) => lists(text).length > 0).map(({ name }) => name);
    assert.deepEqual(skipping.sort(), [
        "ankiquest_api_contract.yml",
        "ankiquest_apk.yml",
        "ankiquest_art_check.yml",
        "ankiquest_fixtures.yml",
        "ankiquest_reproducible.yml",
        "lint.yml",
        "tests_emulator.yml",
        "tests_unit.yml",
    ]);
});
