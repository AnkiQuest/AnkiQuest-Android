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

const apkWorkflow = fs.readFileSync(path.join(__dirname, "../workflows/ankiquest_apk.yml"), "utf8");

test("the new package uses the release key alone on every supported Android version", () => {
    const signing = apkWorkflow.split("      - name: Sign with the AnkiQuest release key\n")[1]?.split("\n      - name:")[0];
    assert.ok(signing, "the build must have a signing step");
    assert.match(signing, /--ks-key-alias ankiquest/);
    assert.doesNotMatch(signing, /fallback-release-keystore\.jks|--next-signer|--lineage/);
    assert.match(signing, /verify --min-sdk-version 24 AnkiQuest\.apk/);
    assert.match(signing, /for api in 24 28 33/);
    assert.match(signing, /verify --min-sdk-version "\$api" --max-sdk-version "\$api" --print-certs/);
    assert.match(signing, /b397a61ecfaf0fe354da611772515e683ccb6fd0e952d477b8b3bca49e6c490d/);
});

test("publishing the new package waits for the final legacy APK release", () => {
    const bridge = apkWorkflow.indexOf("      - name: Check the migration release");
    const release = apkWorkflow.indexOf("      - name: Publish release");
    assert.ok(bridge >= 0 && bridge < release, "the bridge check must run before either publication step");
    // Pull requests squash-merge, so the guard reads the tag's tree, never commit ancestry.
    assert.doesNotMatch(apkWorkflow.slice(bridge, release), /merge-base/);
    assert.match(apkWorkflow.slice(bridge, release), /gh release view quest-24/);
    assert.match(apkWorkflow.slice(bridge, release), /AnkiDroid-Quest\.apk/);
    assert.doesNotMatch(apkWorkflow.slice(release), /AnkiDroid-Quest\.apk/);
});

// Source files of a quest-24 tag, as the guard sees them. Only the final old-package release has
// the updater's move notice and the current-signer check; the earlier source has only the provider.
const BRIDGE = "AnkiDroid/src/main/java/com/ichi2/anki/ankiquest";
const SIGNER = "b397a61ecfaf0fe354da611772515e683ccb6fd0e952d477b8b3bca49e6c490d";
const FINAL_SOURCE = {
    "AnkiDroid/build.gradle.kts": 'applicationId = "com.ichi2.anki"\n',
    [`${BRIDGE}/AnkiquestMoveProvider.kt`]: "class AnkiquestMoveProvider\n",
    [`${BRIDGE}/AnkiquestUpdater.kt`]: "else -> Download.MOVED\n",
    [`${BRIDGE}/AnkiquestMove.kt`]: `private const val RELEASE_SHA256 = "${SIGNER}"\n`,
};
const EXPORT_ONLY_SOURCE = {
    "AnkiDroid/build.gradle.kts": 'applicationId = "com.ichi2.anki"\n',
    [`${BRIDGE}/AnkiquestMoveProvider.kt`]: "class AnkiquestMoveProvider\n",
    [`${BRIDGE}/AnkiquestUpdater.kt`]: "installed = true\n",
    [`${BRIDGE}/AnkiquestMove.kt`]: "checkSignatures(it, context.packageName)\n",
};

// A repository whose quest-24 tag is a root commit with these files: no history to inherit from.
function tagRepository(t, files) {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), "ankiquest-bridge-"));
    t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
    for (const [name, contents] of Object.entries(files)) {
        fs.mkdirSync(path.join(directory, path.dirname(name)), { recursive: true });
        fs.writeFileSync(path.join(directory, name), contents);
    }
    const git = (...args) => {
        const result = spawnSync("git", ["-c", "user.name=test", "-c", "user.email=test@example.invalid", ...args], { cwd: directory, encoding: "utf8" });
        assert.equal(result.status, 0, result.stderr);
    };
    git("init", "-q");
    git("add", "-A");
    git("commit", "-q", "-m", "source");
    git("tag", "quest-24");
    return directory;
}

test("the publication guard accepts only the complete stable bridge", (t) => {
    const step = apkWorkflow.split("      - name: Check the migration release\n")[1]?.split("\n      - name:")[0];
    const guard = step?.split("        run: |\n")[1]?.replace(/^          /gm, "");
    assert.ok(guard, "the bridge check must include a release guard");

    const shell = `gh() { printf '%s\\n' "$BRIDGE_JSON"; }
${guard}`;
    const without = (name) => Object.fromEntries(Object.entries(FINAL_SOURCE).filter(([file]) => !file.endsWith(name)));
    const stable = { isDraft: false, isPrerelease: false, assets: [{name: "AnkiQuest.apk"}, {name: "AnkiDroid-Quest.apk"}] };
    const cases = [
        { name: "complete legacy release", info: stable, files: FINAL_SOURCE, expected: 0 },
        { name: "earlier export-only source", info: stable, files: EXPORT_ONLY_SOURCE, expected: 1 },
        { name: "missing export provider", info: stable, files: without("AnkiquestMoveProvider.kt"), expected: 1 },
        { name: "missing updater notice", info: stable, files: {...FINAL_SOURCE, [`${BRIDGE}/AnkiquestUpdater.kt`]: EXPORT_ONLY_SOURCE[`${BRIDGE}/AnkiquestUpdater.kt`]}, expected: 1 },
        { name: "missing signer check", info: stable, files: {...FINAL_SOURCE, [`${BRIDGE}/AnkiquestMove.kt`]: EXPORT_ONLY_SOURCE[`${BRIDGE}/AnkiquestMove.kt`]}, expected: 1 },
        { name: "tag built from the new package", info: stable, files: {...FINAL_SOURCE, "AnkiDroid/build.gradle.kts": 'applicationId = "org.ankiquest.ankidroid"\n'}, expected: 1 },
        { name: "missing legacy name", info: {...stable, assets: stable.assets.slice(0, 1)}, files: FINAL_SOURCE, expected: 1 },
        { name: "draft", info: {...stable, isDraft: true}, files: FINAL_SOURCE, expected: 1 },
        { name: "prerelease", info: {...stable, isPrerelease: true}, files: FINAL_SOURCE, expected: 1 },
    ];
    for (const { name, info, files, expected } of cases) {
        const result = spawnSync("bash", ["-euo", "pipefail", "-c", shell], {
            cwd: tagRepository(t, files),
            env: { ...process.env, GITHUB_REPOSITORY: "AnkiQuest/AnkiQuest-Android", BRIDGE_JSON: JSON.stringify(info) },
            encoding: "utf8",
        });
        assert.equal(result.status, expected, `${name}\n${result.stderr}`);
        assert.doesNotMatch(result.stderr, /command not found|not a git repository/);
    }
});
