// SPDX-License-Identifier: AGPL-3.0-or-later
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const workflows = path.join(__dirname, "../workflows");

// A plain (unquoted, single-line) YAML value may not contain ": ". GitHub then rejects
// the whole workflow file and runs none of its jobs, as the screenshot baseline
// did after "cut -d: -f2-" went into a one-line run: step.
test("one-line run steps contain no ': '", () => {
    const bad = [];
    for (const name of fs.readdirSync(workflows).filter((file) => file.endsWith(".yml"))) {
        const lines = fs.readFileSync(path.join(workflows, name), "utf8").split("\n");
        lines.forEach((line, index) => {
            const run = line.match(/^\s*(?:- )?run: (.*)$/);
            if (!run || /^[|>'"]/.test(run[1])) {
                return;
            }
            if (run[1].includes(": ")) {
                bad.push(`${name}:${index + 1}`);
            }
        });
    }
    assert.deepEqual(bad, []);
});
