// SPDX-License-Identifier: AGPL-3.0-or-later
const assert = require("node:assert/strict");
const { spawnSync } = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const apkWorkflow = fs.readFileSync(path.join(__dirname, "../workflows/ankiquest_apk.yml"), "utf8").replaceAll("\r\n", "\n");

// The body of one step, from its name to the next step.
function step(name) {
    const body = apkWorkflow.split(`      - name: ${name}\n`)[1]?.split("\n      - ")[0];
    assert.ok(body, `the build must have a "${name}" step`);
    return body;
}

test("every APK uses the release key alone on every supported Android version", () => {
    const signing = step("Sign with the AnkiQuest release key");
    assert.match(signing, /for apk in AnkiQuest\.apk AnkiQuest-\*\.apk; do/);
    assert.match(signing, /--ks-key-alias ankiquest/);
    assert.doesNotMatch(signing, /fallback-release-keystore\.jks|--next-signer|--lineage/);
    assert.match(signing, /verify --min-sdk-version 24 "\$apk"/);
    assert.match(signing, /for api in 24 28 33/);
    assert.match(signing, /verify --min-sdk-version "\$api" --max-sdk-version "\$api" --print-certs "\$apk"/);
    assert.match(signing, /b397a61ecfaf0fe354da611772515e683ccb6fd0e952d477b8b3bca49e6c490d/);
});

test("only org.ankiquest.ankidroid APKs reach the single-key signing", () => {
    const check = apkWorkflow.indexOf("      - name: Package\n");
    const signing = apkWorkflow.indexOf("      - name: Sign with the AnkiQuest release key\n");
    assert.ok(check >= 0 && check < signing, "the package check must run before signing");
    const body = step("Package");
    assert.match(body, /PACKAGE: org\.ankiquest\.ankidroid\n/);
    assert.match(body, /for apk in AnkiQuest\.apk AnkiQuest-\*\.apk; do/);
    assert.match(body, /if \[ "\$package" != "\$PACKAGE" \]; then[\s\S]*?exit 1/);
});

test("publishing the new package waits for the final old-package release", () => {
    const bridge = apkWorkflow.indexOf("      - name: Check the migration release");
    const release = apkWorkflow.indexOf("      - name: Publish release");
    assert.ok(bridge >= 0 && bridge < release, "the bridge check must run before either publication step");
    const guard = apkWorkflow.slice(bridge, release);
    // Pull requests squash-merge, so the guard reads the tag's tree, never commit ancestry.
    assert.doesNotMatch(guard, /merge-base/);
    assert.match(guard, /gh release view quest-24/);
    assert.match(guard, /AnkiQuest\.apk/);
    assert.doesNotMatch(apkWorkflow, /AnkiDroid-Quest\.apk/);
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
    const guard = step("Check the migration release").split("        run: |\n")[1]?.replace(/^          /gm, "");
    assert.ok(guard, "the bridge check must include a release guard");

    const shell = `gh() { printf '%s\\n' "$BRIDGE_JSON"; }
${guard}`;
    const without = (name) => Object.fromEntries(Object.entries(FINAL_SOURCE).filter(([file]) => !file.endsWith(name)));
    const stable = { isDraft: false, isPrerelease: false, assets: [{name: "AnkiQuest.apk"}, {name: "nightly.json"}] };
    const cases = [
        { name: "complete final release", info: stable, files: FINAL_SOURCE, expected: 0 },
        { name: "earlier export-only source", info: stable, files: EXPORT_ONLY_SOURCE, expected: 1 },
        { name: "missing export provider", info: stable, files: without("AnkiquestMoveProvider.kt"), expected: 1 },
        { name: "missing updater notice", info: stable, files: {...FINAL_SOURCE, [`${BRIDGE}/AnkiquestUpdater.kt`]: EXPORT_ONLY_SOURCE[`${BRIDGE}/AnkiquestUpdater.kt`]}, expected: 1 },
        { name: "missing signer check", info: stable, files: {...FINAL_SOURCE, [`${BRIDGE}/AnkiquestMove.kt`]: EXPORT_ONLY_SOURCE[`${BRIDGE}/AnkiquestMove.kt`]}, expected: 1 },
        { name: "tag built from the new package", info: stable, files: {...FINAL_SOURCE, "AnkiDroid/build.gradle.kts": 'applicationId = "org.ankiquest.ankidroid"\n'}, expected: 1 },
        { name: "missing AnkiQuest.apk", info: {...stable, assets: [{name: "AnkiDroid-Quest.apk"}]}, files: FINAL_SOURCE, expected: 1 },
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
