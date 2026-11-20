// SPDX-License-Identifier: AGPL-3.0-only
// Labelling comes from AnkiQuest-Dev's reusable workflows, which hold the
// label set and their own tests; ankiquest_labels.yml is the caller. Upstream
// AnkiDroid's labelling workflows stay removed so labels are not applied
// twice.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const workflows = path.resolve(__dirname, "..", "workflows");

// Upstream AnkiDroid's labelling workflows: label.yml and conflict.yaml.
const REPLACED = ["label.yml", "conflict.yaml"];

// The reusable workflows the caller runs.
const CALLED = ["conflict-scan.yml", "issue-labels.yml", "labels.yml", "pr-labels.yml"];

test("upstream's labelling workflows are replaced by the shared ones", () => {
    for (const file of REPLACED) {
        assert.ok(!fs.existsSync(path.join(workflows, file)), file);
    }
});

test("the caller runs every shared labelling workflow", () => {
    const caller = fs.readFileSync(path.join(workflows, "ankiquest_labels.yml"), "utf8");
    const called = [...caller.matchAll(/AnkiQuest-Dev\/\.github\/workflows\/(\S+\.yml)@master/g)].map((match) => match[1]);
    assert.deepEqual(called.sort(), [...CALLED].sort());
});
