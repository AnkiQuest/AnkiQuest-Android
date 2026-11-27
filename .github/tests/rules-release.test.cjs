// SPDX-License-Identifier: AGPL-3.0-or-later
const assert = require("node:assert/strict");
const { spawnSync } = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

// The F-Droid build reads the shared rules tag a quest-<n> APK was built with from the
// rules-release.json of the GitHub release (docs/ankiquest-server-data.md, "F-Droid").

/** The `run: |` script of the step named [name] in [workflow]. */
function stepScript(workflow, name) {
    const lines = fs.readFileSync(path.join(__dirname, "../workflows", workflow), "utf8").split("\n");
    const start = lines.findIndex((line) => line.trim() === `- name: ${name}`);
    assert.ok(start >= 0, `${workflow} has no step ${name}`);
    const run = lines.findIndex((line, index) => index > start && line.trim() === "run: |");
    const indent = lines[run + 1].search(/\S/);
    const body = [];
    for (const line of lines.slice(run + 1)) {
        if (line.trim() !== "" && line.search(/\S/) < indent) {
            break;
        }
        body.push(line.slice(indent));
    }
    return body.join("\n");
}

test("a release carries the rules record of its APK", (t) => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), "ankiquest-rules-release-"));
    t.after(() => fs.rmSync(directory, { recursive: true, force: true }));

    // An APK with the record, as the build writes it.
    const record = '{\n  "tag": "rules-7",\n  "assets": {}\n}\n';
    fs.mkdirSync(path.join(directory, "apk/assets/ankiquest"), { recursive: true });
    fs.writeFileSync(path.join(directory, "apk/assets/ankiquest/rules-release.json"), record);
    const zip = spawnSync("zip", ["-qr", "../AnkiQuest.apk", "assets"], { cwd: path.join(directory, "apk") });
    assert.equal(zip.status, 0, String(zip.stderr));

    // gh records what it would upload.
    fs.writeFileSync(path.join(directory, "gh"), `#!/usr/bin/env node
const fs = require("node:fs");
fs.writeFileSync("gh-args.json", JSON.stringify(process.argv.slice(2)));
`, { mode: 0o755 });
    fs.mkdirSync(path.join(directory, "AnkiDroid/src/main/assets/ankiquest"), { recursive: true });

    const result = spawnSync("bash", ["-euo", "pipefail", "-c", stepScript("ankiquest_apk.yml", "Publish release")], {
        cwd: directory,
        env: { ...process.env, PATH: directory + path.delimiter + process.env.PATH, GITHUB_REF_NAME: "quest-30", RELEASE: "30" },
        encoding: "utf8",
    });
    assert.equal(result.status, 0, result.stderr);

    const args = JSON.parse(fs.readFileSync(path.join(directory, "gh-args.json"), "utf8"));
    assert.deepEqual(args.slice(0, 3), ["release", "create", "quest-30"]);
    assert.ok(args.includes("rules-release.json"), args.join(" "));
    assert.equal(fs.readFileSync(path.join(directory, "rules-release.json"), "utf8"), record);
});

test("the F-Droid recipe reads the record from the release of its tag", () => {
    const docs = fs.readFileSync(path.join(__dirname, "../../docs/ankiquest-server-data.md"), "utf8");
    assert.match(
        docs,
        /releases\/download\/\$\(git describe --tags --exact-match --match 'quest-\[0-9\]\*'\)\/rules-release\.json/,
    );
    assert.match(docs, /- ankiquestRulesDir=ankiquest-rules\n/);
});
