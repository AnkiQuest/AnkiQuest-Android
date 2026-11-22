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

// Latest-wins generators that never run on a branch push (the README banner) let the newest run
// for a ref cancel an older one.
const LATEST = "${{ github.workflow }}-${{ github.ref }}";

// Returns the top-level concurrency settings, or null when there are none.
function concurrency(text) {
    const block = text.match(/^concurrency:\n((?: {2}.*\n|\n)*)/m);
    if (!block) {
        return null;
    }

    const value = key => block[1].match(new RegExp(`^ {2}${key}: (.*)$`, "m"))?.[1];
    return { group: value("group"), cancel: value("cancel-in-progress") };
}

// ankiquest_labels.yml is a byte-for-byte copy of AnkiQuest-Dev's
// ankiquest-labels.yml, so its concurrency is decided and tested there.
const COPIED = ["ankiquest_labels.yml"];

const files = fs
    .readdirSync(workflows)
    .filter(name => /^ankiquest_.*\.yml$/.test(name) && !COPIED.includes(name));

for (const name of files) {
    test(`${name}: a later run never cancels a master push run`, () => {
        const text = fs.readFileSync(path.join(workflows, name), "utf8");
        const settings = concurrency(text);

        // Workflows that only queue (cancel-in-progress: false) keep their own group.
        if (settings === null || settings.cancel === "false") {
            return;
        }

        if (settings.cancel === "true") {
            assert.doesNotMatch(text, /^ {2}push:\n {4}branches/m, "a latest-wins workflow may not run on branch pushes");
            assert.equal(settings.group, LATEST);
            return;
        }

        assert.equal(settings.group, GROUP);
        assert.equal(settings.cancel, CANCEL);
    });
}
