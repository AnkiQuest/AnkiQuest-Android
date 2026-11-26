// SPDX-License-Identifier: AGPL-3.0-or-later
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const workflow = fs.readFileSync(path.join(__dirname, "../workflows/ankiquest_apk.yml"), "utf8");
const lines = workflow.split("\n");

// The files listed under the `files: |` block of the nightly publish step.
function nightlyFiles() {
    const start = lines.findIndex(line => line.trim() === "files: |");
    assert.notEqual(start, -1, "the nightly step must list its files");
    const indent = lines[start + 1].search(/\S/);
    const files = [];
    for (const line of lines.slice(start + 1)) {
        if (line.trim() === "" || line.search(/\S/) < indent) {
            break;
        }
        files.push(line.trim());
    }
    return files;
}

test("a release publishes AnkiQuest.apk alone", () => {
    const create = lines.find(line => line.trim().startsWith("gh release create "));
    assert.ok(create, "the release step must create the release");
    const apks = create.trim().split(/\s+/).filter(word => word.endsWith(".apk"));
    assert.deepEqual(apks, ["AnkiQuest.apk"]);
});

test("the nightly publishes AnkiQuest.apk and its manifest alone", () => {
    assert.deepEqual(nightlyFiles(), ["AnkiQuest.apk", "nightly.json"]);
});

test("no workflow copies the APK under another name", () => {
    const copies = lines.filter(line => /\bcp AnkiQuest\.apk\b/.test(line));
    assert.deepEqual(copies, []);
});
