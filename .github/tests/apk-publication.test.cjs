// SPDX-License-Identifier: AGPL-3.0-or-later
const assert = require("node:assert/strict");
const { execFileSync } = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const workflow = fs.readFileSync(path.join(__dirname, "../workflows/ankiquest_apk.yml"), "utf8").replaceAll("\r\n", "\n");
const legacyApk = "AnkiDroid-Quest.apk";

// Run the actual publication steps against fake signed files and a local gh recorder.
function stepScript(name) {
    const start = workflow.indexOf("      - name: " + name + "\n");
    assert.notEqual(start, -1, name);
    const end = workflow.indexOf("\n      - ", start + 1);
    const step = workflow.slice(start, end < 0 ? undefined : end);
    const script = step.match(/        run: \|\n((?: {10}[^\n]*\n|\n)*)/);
    assert.ok(script, "Missing script for " + name);
    return script[1].split("\n").map(line => line.slice(10)).join("\n");
}

// A temporary directory with synthetic signed outputs and a gh stand-in that records its arguments.
function fakeBuild(t) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "ankiquest-apk-"));
    t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
    const bin = path.join(dir, "bin");
    fs.mkdirSync(bin);
    fs.writeFileSync(path.join(bin, "gh"), "#!/bin/sh\nprintf '%s\\0' \"$@\" >> \"$GH_CAPTURE\"\n", { mode: 0o755 });
    const apk = Buffer.from("synthetic signed universal APK");
    fs.writeFileSync(path.join(dir, "AnkiQuest.apk"), apk);
    for (const abi of ["arm64-v8a", "armeabi-v7a", "x86_64", "x86"]) {
        fs.writeFileSync(path.join(dir, "AnkiQuest-" + abi + ".apk"), "synthetic signed ABI APK");
    }
    fs.writeFileSync(path.join(dir, "AnkiQuest.aab"), "synthetic app bundle");
    const notes = path.join(dir, "AnkiDroid/src/main/assets/ankiquest");
    fs.mkdirSync(notes, { recursive: true });
    fs.writeFileSync(path.join(notes, "release-notes.md"), "Test release notes\n");
    return { dir, apk };
}

// The new package's download names. AnkiDroid-Quest.apk stays with the old package's final
// release, quest-24.
const names = ["AnkiQuest-arm64-v8a.apk", "AnkiQuest.apk", "AnkiQuest-armeabi-v7a.apk", "AnkiQuest-x86_64.apk", "AnkiQuest-x86.apk", "AnkiQuest.aab"];

for (const channel of ["release", "nightly"]) {
    test(channel + " publishes the new download names without the legacy alias", (t) => {
        const { dir } = fakeBuild(t);
        const bin = path.join(dir, "bin");
        const capture = path.join(dir, "published");
        const options = {
            cwd: dir,
            env: {
                ...process.env,
                PATH: bin + path.delimiter + process.env.PATH,
                GH_CAPTURE: capture,
                GITHUB_REF_NAME: "quest-25",
                GITHUB_SHA: "synthetic-commit",
                RELEASE: "25",
                NIGHTLY: "3",
                VERSION_CODE: "50025003",
            },
            encoding: "utf8",
        };
        execFileSync("bash", ["-euo", "pipefail", "-c", stepScript("Assets")], options);
        const assets = JSON.parse(fs.readFileSync(path.join(dir, "assets.json"), "utf8"));
        assert.deepEqual(assets.map(asset => asset.name), names);
        assert.ok(!fs.existsSync(path.join(dir, legacyApk)), legacyApk + " belongs to the old package");

        const publish = stepScript("Publish " + channel).replaceAll("$" + "{{ steps.release.outputs.number }}", "25");
        execFileSync("bash", ["-euo", "pipefail", "-c", publish], options);
        const args = fs.readFileSync(capture, "utf8").split("\0");
        const create = args.lastIndexOf("create");
        assert.ok(create >= 0, "A release must be created");
        for (const name of names) {
            assert.ok(args.slice(create).includes(name), name + " must be attached to the release");
        }
        assert.ok(!args.slice(create).includes(legacyApk), legacyApk + " must not be attached");
    });
}
