// SPDX-License-Identifier: AGPL-3.0-or-later
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const workflows = path.join(__dirname, "../workflows");

// Only pull request runs may share a group and cancel each other; every other
// run gets a group of its own (#269).
const GROUP = "${{ github.workflow }}-${{ github.event_name == 'pull_request' && github.ref || github.run_id }}";
const CANCEL = "${{ github.event_name == 'pull_request' }}";

// Returns the top-level concurrency settings, or null when there are none.
function concurrency(text) {
    const block = text.match(/^concurrency:\n((?: {2}.*\n|\n)*)/m);
    if (!block) {
        return null;
    }

    const value = key => block[1].match(new RegExp(`^ {2}${key}: (.*)$`, "m"))?.[1];
    return { group: value("group"), cancel: value("cancel-in-progress") };
}

// Upstream workflows that carry our changes follow the same rule as ours.
const UPSTREAM_OWNED = [
    "lint.yml",
    "screenshot_comment.yml",
    "screenshot_compare.yml",
    "screenshot_store.yml",
    "tests_emulator.yml",
    "tests_unit.yml",
];

const files = fs
    .readdirSync(workflows)
    .filter(name => /^ankiquest_.*\.yml$/.test(name) || UPSTREAM_OWNED.includes(name));

for (const name of files) {
    test(`${name}: a later run never cancels a master push run`, () => {
        const settings = concurrency(fs.readFileSync(path.join(workflows, name), "utf8"));

        // Workflows that only queue (cancel-in-progress: false) keep their own group.
        if (settings === null || settings.cancel === "false") {
            return;
        }

        assert.equal(settings.group, GROUP);
        assert.equal(settings.cancel, CANCEL);
    });
}
