// SPDX-License-Identifier: AGPL-3.0-or-later
const assert = require("node:assert/strict");
const { spawnSync } = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

// Run the workflow's real download command against a release with synthetic APKs.
// The fake CLI enforces gh's one-match requirement for --output, without network access.
function download(t, assets) {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), "ankiquest-signing-"));
    t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
    const release = path.join(directory, "release");
    fs.mkdirSync(release);
    for (const [name, contents] of Object.entries(assets)) {
        fs.writeFileSync(path.join(release, name), contents);
    }
    fs.writeFileSync(path.join(directory, "gh"), `#!/usr/bin/env node
const fs = require("node:fs");
const path = require("node:path");
const args = process.argv.slice(2);
const pattern = args[args.indexOf("--pattern") + 1];
const output = args[args.indexOf("--output") + 1];
const files = fs.globSync(pattern, { cwd: process.env.RELEASE_FIXTURE });
if (files.length !== 1) {
    console.error("Expected one APK, found " + files.length);
    process.exit(1);
}
fs.copyFileSync(path.join(process.env.RELEASE_FIXTURE, files[0]), output);
`, { mode: 0o755 });

    const workflow = fs.readFileSync(path.join(__dirname, "../workflows/ankiquest_signing.yml"), "utf8");
    const command = workflow.split("\n").find(line => line.trim().startsWith("gh release download "));
    assert.ok(command, "the signing workflow must download the released APK");
    const result = spawnSync("bash", ["-euo", "pipefail", "-c", command.trim()], {
        cwd: directory,
        env: {
            ...process.env,
            PATH: directory + path.delimiter + process.env.PATH,
            GITHUB_REPOSITORY: "AnkiQuest/AnkiQuest-Android",
            RELEASE_FIXTURE: release,
        },
        encoding: "utf8",
    });
    const output = path.join(directory, "base.apk");
    return { ...result, apk: fs.existsSync(output) ? fs.readFileSync(output, "utf8") : null };
}

test("key rotation selects the current APK when the legacy name is also published", t => {
    const result = download(t, { "AnkiQuest.apk": "current", "AnkiDroid-Quest.apk": "legacy" });
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.apk, "current");
});

test("key rotation also accepts a release with only the current APK", t => {
    const result = download(t, { "AnkiQuest.apk": "current" });
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.apk, "current");
});

test("key rotation fails when the current APK is absent", t => {
    const result = download(t, { "AnkiDroid-Quest.apk": "legacy" });
    assert.notEqual(result.status, 0);
    assert.equal(result.apk, null);
});

const apkWorkflow = fs.readFileSync(path.join(__dirname, "../workflows/ankiquest_apk.yml"), "utf8").replaceAll("\r\n", "\n");

// The body of one step, from its name to the next step.
function step(name) {
    const body = apkWorkflow.split(`      - name: ${name}\n`)[1]?.split("\n      - ")[0];
    assert.ok(body, `the build must have a "${name}" step`);
    return body;
}

test("every APK is signed with the rotation to the release key", () => {
    const signing = step("Sign with the AnkiQuest release key");
    assert.match(signing, /for apk in AnkiQuest\.apk AnkiQuest-\*\.apk; do/);
    assert.match(signing, /--next-signer --ks "\$keystore" --ks-key-alias ankiquest/);
    assert.match(signing, /--lineage tools\/ankiquest-signing-lineage\.bin --rotation-min-sdk-version 28/);
    assert.match(signing, /verify --min-sdk-version 28 --print-certs "\$apk" \| grep -q "CN=AnkiQuest"/);
});

test("every APK has the universal APK's package before signing", () => {
    const check = apkWorkflow.indexOf("      - name: Package\n");
    const signing = apkWorkflow.indexOf("      - name: Sign with the AnkiQuest release key\n");
    assert.ok(check >= 0 && check < signing, "the package check must run before signing");
    const body = step("Package");
    assert.match(body, /expected=\$\(package_of AnkiQuest\.apk\)/);
    assert.match(body, /for apk in AnkiQuest-\*\.apk; do/);
    assert.match(body, /if \[ "\$package" != "\$expected" \]; then[\s\S]*?exit 1/);
});
