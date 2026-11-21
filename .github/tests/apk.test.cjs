// SPDX-License-Identifier: AGPL-3.0-only
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const workflow = fs
    .readFileSync(path.join(__dirname, "../workflows/ankiquest_apk.yml"), "utf8")
    .replaceAll("\r\n", "\n");
const COMPAT_APK = "AnkiQuest-ankidroid-compatible.apk";

function step(name) {
    const start = workflow.indexOf(`      - name: ${name}\n`);
    assert.notEqual(start, -1, `Missing workflow step: ${name}`);
    const end = workflow.indexOf("\n      - ", start + 1);
    return workflow.slice(start, end === -1 ? undefined : end);
}

// The Gradle invocations of a step, with continuation lines joined.
function gradleRuns(text) {
    return text
        .replaceAll("\\\n", " ")
        .split("\n")
        .filter(line => line.includes("./gradlew"));
}

test("the AnkiDroid-compatible APK keeps AnkiDroid's package and does not self-update", () => {
    const runs = gradleRuns(step("Assemble")).filter(line => line.includes("-PankiquestPackage=ankidroid"));
    assert.equal(runs.length, 1);
    const [compat] = runs;
    assert.doesNotMatch(compat, /customSuffix|"\$\{quest\[@\]\}"/);
    assert.match(compat, /-PankiquestUpdates=none/);
    assert.match(compat, /-PcustomName="AnkiQuest"/);
    assert.match(compat, /-PankiquestRelease="\$RELEASE"/);
    assert.match(compat, /-PankiquestNightly="\$NIGHTLY"/);
});

test("the quest outputs are copied before the AnkiDroid-compatible build replaces them", () => {
    const assemble = step("Assemble");
    const compatBuild = assemble.indexOf("-PankiquestPackage=ankidroid");
    for (const copy of ["AnkiQuest.apk", '"AnkiQuest-$abi.apk"', "AnkiQuest.aab"]) {
        const at = assemble.indexOf(` ${copy}\n`);
        assert.notEqual(at, -1, `Missing copy to ${copy}`);
        assert.ok(at < compatBuild, `${copy} is copied after the AnkiDroid-compatible build`);
    }
    assert.ok(assemble.indexOf(` ${COMPAT_APK}\n`) > compatBuild);
});

test("the AnkiDroid-compatible APK is signed, checked and published", () => {
    assert.match(step("Sign with the AnkiQuest release key"), /for apk in AnkiQuest\.apk AnkiQuest-\*\.apk; do/);
    assert.ok(step("Version code").includes(`code_of ${COMPAT_APK}`));
    const assets = step("Assets");
    assert.match(assets, new RegExp(`files=\\([^)]*${COMPAT_APK.replaceAll(".", "\\.")}`));
    assert.ok(assets.includes(`echo "- \\\`${COMPAT_APK}\\\`:`));
});
