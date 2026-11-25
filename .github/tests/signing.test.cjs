// SPDX-License-Identifier: AGPL-3.0-or-later
const assert = require("node:assert/strict");
const { spawnSync } = require("node:child_process");
const fs = require("node:fs");
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

test("publishing the new package waits for the final legacy APK release", () => {
    const bridge = apkWorkflow.indexOf("      - name: Check the migration release");
    const release = apkWorkflow.indexOf("      - name: Publish release");
    assert.ok(bridge >= 0 && bridge < release, "the bridge check must run before either publication step");
    const guard = apkWorkflow.slice(bridge, release);
    // Pull requests squash-merge, so a branch commit is never in the tag; its files are.
    assert.match(guard, /git cat-file -e refs\/tags\/quest-24:AnkiDroid\/src\/main\/java\/com\/ichi2\/anki\/ankiquest\/AnkiquestMoveProvider\.kt/);
    assert.match(guard, /gh release view quest-24/);
    assert.match(guard, /AnkiDroid-Quest\.apk/);
    assert.doesNotMatch(apkWorkflow.slice(release), /AnkiDroid-Quest\.apk/);
});

test("the publication guard accepts only the complete stable bridge", () => {
    const guard = step("Check the migration release").split("        run: |\n")[1]?.replace(/^          /gm, "");
    assert.ok(guard, "the bridge check must include a release guard");

    const shell = `git() {
    case "$1" in
        cat-file) test "$BRIDGE_COMMIT" = present ;;
        grep) test "$BRIDGE_PACKAGE" = legacy ;;
        *) return 127 ;;
    esac
}
gh() { printf '%s\\n' "$BRIDGE_JSON"; }
${guard}`;
    const stable = { isDraft: false, isPrerelease: false, assets: [{name: "AnkiQuest.apk"}, {name: "AnkiDroid-Quest.apk"}] };
    const cases = [
        { name: "complete legacy release", info: stable, commit: "present", expected: 0 },
        { name: "missing migration files", info: stable, commit: "absent", expected: 1 },
        { name: "tag built from the new package", info: stable, commit: "present", pkg: "new", expected: 1 },
        { name: "missing legacy name", info: {...stable, assets: stable.assets.slice(0, 1)}, commit: "present", expected: 1 },
        { name: "draft", info: {...stable, isDraft: true}, commit: "present", expected: 1 },
        { name: "prerelease", info: {...stable, isPrerelease: true}, commit: "present", expected: 1 },
    ];
    for (const { name, info, commit, pkg = "legacy", expected } of cases) {
        const result = spawnSync("bash", ["-euo", "pipefail", "-c", shell], {
            env: { ...process.env, GITHUB_REPOSITORY: "AnkiQuest/AnkiQuest-Android", BRIDGE_COMMIT: commit, BRIDGE_PACKAGE: pkg, BRIDGE_JSON: JSON.stringify(info) },
            encoding: "utf8",
        });
        assert.equal(result.status, expected, `${name}\n${result.stderr}`);
        assert.doesNotMatch(result.stderr, /command not found/);
    }
});
