// SPDX-License-Identifier: AGPL-3.0-only
// .github/labels.json is the label set shared with AnkiQuest-Server and
// AnkiQuest-Desktop. It must hold every label this repository's workflows
// and Dependabot apply, spelled the way they spell it.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const github = path.resolve(__dirname, "..");
const read = (file) => fs.readFileSync(path.join(github, file), "utf8");
const labels = JSON.parse(read("labels.json"));

// GitHub's limits for a label.
const COLOR = /^[0-9a-f]{6}$/;
const MAX_DESCRIPTION = 100;

// Labels the upstream AnkiDroid workflows add; each must still appear
// literally in its workflow so a rename on either side fails this test.
const APPLIED = [
    ["workflows/label.yml", "Strings"],
    ["workflows/label.yml", "New contributor"],
    ["workflows/label.yml", "Needs Review"],
    ["workflows/conflict.yaml", "Has Conflicts"],
];

// Labels Dependabot adds, from the `labels:` lists in dependabot.yml.
function dependabotNames() {
    const names = new Set();
    for (const [, list] of read("dependabot.yml").matchAll(/^\s*labels:\n((?:\s*- .*\n)+)/gm)) {
        for (const [, name] of list.matchAll(/- "?([^"\n]+)"?/g)) {
            names.add(name.trim());
        }
    }
    return [...names];
}

test("labels.json entries are well formed and unique ignoring case", () => {
    const seen = new Set();
    for (const label of labels) {
        assert.deepEqual(Object.keys(label).sort(), ["aliases", "color", "description", "name"], label.name);
        assert.match(label.color, COLOR, label.name);
        assert.ok(label.description.length <= MAX_DESCRIPTION, label.name);
        for (const name of [label.name, ...label.aliases]) {
            assert.ok(!seen.has(name.toLowerCase()), `${name} appears twice`);
            seen.add(name.toLowerCase());
        }
    }
});

test("labels.json holds every label the workflows apply", () => {
    const names = new Set(labels.map((label) => label.name));
    for (const [file, name] of APPLIED) {
        assert.ok(read(file).includes(`'${name}'`) || read(file).includes(`"${name}"`), `${file} no longer applies ${name}`);
        assert.ok(names.has(name), `${name} is missing from .github/labels.json`);
    }
});

test("labels.json holds every label Dependabot applies", () => {
    const names = new Set(labels.map((label) => label.name));
    const used = dependabotNames();
    assert.ok(used.length > 0);
    for (const name of used) {
        assert.ok(names.has(name), `${name} is missing from .github/labels.json`);
    }
});
