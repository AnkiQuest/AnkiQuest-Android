// SPDX-License-Identifier: AGPL-3.0-or-later
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const workflows = path.join(__dirname, "../workflows");

// Workflows that produce master's required checks. The merge queue waits for
// each of them on its merge commit, so each must run on merge_group.
const REQUIRED = [
    "ankiquest_api_contract.yml",
    "ankiquest_art_check.yml",
    "ankiquest_fixtures.yml",
    "ankiquest_lint.yml",
    "ankiquest_screenshot_compare.yml",
    "ankiquest_tests_unit.yml",
];

// The `on:` block of a workflow.
function triggers(text) {
    const block = text.match(/^on:\n((?: {2}.*\n|\n)*)/m);
    assert.ok(block, "no on: block");
    return block[1];
}

for (const name of REQUIRED) {
    test(`${name}: runs for every merge queue group`, () => {
        const on = triggers(fs.readFileSync(path.join(workflows, name), "utf8"));
        const group = on.match(/^ {2}merge_group:\n((?: {4}.*\n)*)/m);
        assert.ok(group, "no merge_group trigger");

        // A filtered trigger leaves the check pending and the queue waiting.
        assert.doesNotMatch(group[1], /^ {4}(?:paths|branches)/m);
    });
}
