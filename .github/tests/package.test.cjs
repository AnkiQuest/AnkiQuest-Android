// SPDX-License-Identifier: AGPL-3.0-or-later
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const PACKAGE = "org.ankiquest.ankidroid";

const read = (file) => fs.readFileSync(path.join(__dirname, "../..", file), "utf8");
const gradle = read("AnkiDroid/build.gradle.kts");
const workflows = [".github/workflows/ankiquest_apk.yml", ".github/workflows/ankiquest_reproducible.yml"];

test("every build installs as org.ankiquest.ankidroid and keeps the com.ichi2.anki namespace", () => {
    // The app id is org.ankiquest.ankidroid; no build keeps com.ichi2.anki (#461).
    const ids = [...gradle.matchAll(/\bapplicationId = "([^"]*)"/g)].map((match) => match[1]);
    assert.deepEqual(ids, [PACKAGE]);
    assert.match(gradle, /defaultConfig \{\n\s*applicationId = "org\.ankiquest\.ankidroid"\n/);
    assert.doesNotMatch(gradle, /ankiquestApplicationId/);
    assert.match(gradle, /namespace = "com\.ichi2\.anki"\n/);
});

test("the sync action is AnkiQuest's, not AnkiDroid's", () => {
    const manifest = read("AnkiDroid/src/main/AndroidManifest.xml");
    const handler = read("AnkiDroid/src/main/java/com/ichi2/anki/IntentHandler.kt");
    assert.match(manifest, /<action android:name="org\.ankiquest\.ankidroid\.DO_SYNC" \/>/);
    assert.doesNotMatch(manifest, /com\.ichi2\.anki\.DO_SYNC/);
    assert.match(handler, /ACTION_DO_SYNC = "org\.ankiquest\.ankidroid\.DO_SYNC"/);
});

test("the AnkiQuest build no longer comes from customSuffix=quest", () => {
    assert.match(gradle, /-PcustomSuffix=quest is no longer the AnkiQuest build/);
    for (const file of workflows) {
        const workflow = read(file);
        assert.doesNotMatch(workflow, /customSuffix=quest/, file);
        assert.match(workflow, /quest=\((--no-build-cache )?-Pankiquest -PcustomName="AnkiQuest"/, file);
        assert.match(workflow, /:AnkiDroid:assembleFullRelease [^\n]*"\$\{quest\[@\]\}"/, file);
        assert.match(workflow, /:AnkiDroid:bundlePlayRelease [^\n]*"\$\{quest\[@\]\}"/, file);
    }
});

test("the launcher icon test selects the AnkiQuest build", () => {
    const workflow = read(".github/workflows/tests_emulator.yml");
    assert.ok(!workflow.includes("-PcustomSuffix=quest"), "the emulator workflow uses the retired AnkiQuest flag");
    assert.match(workflow, /:AnkiDroid:connectedPlayDebugAndroidTest -Pankiquest [^\n]*AnkiquestIconAliasTest\b/);
});

test("the APK workflow fails unless every built APK has the AnkiQuest package", () => {
    const workflow = read(".github/workflows/ankiquest_apk.yml");
    const step = workflow.slice(workflow.indexOf("- name: Package\n"));
    assert.match(step, new RegExp(`PACKAGE: ${PACKAGE.replaceAll(".", "\\.")}\n`));
    assert.match(step, /for apk in AnkiQuest\.apk AnkiQuest-\*\.apk fdroid\.apk; do\n\s*\[ -f "\$apk" \] \|\| continue\n/);
    assert.match(step, /aapt2" dump badging "\$apk" \| sed -n "s\/\^package: name=/);
    assert.match(step, /if \[ "\$package" != "\$PACKAGE" \]; then[\s\S]*?exit 1/);
});

test("the build and release do not target the old package", () => {
    const files = [
        ...workflows,
        "AnkiDroid/build.gradle.kts",
        "AnkiDroid/src/test/java/com/ichi2/anki/ankiquest/AnkiquestLinksTest.kt",
    ];
    for (const file of files) {
        assert.doesNotMatch(read(file), /com\.ichi2\.anki\.quest\b/, file);
    }
});

test("the new manifest names the old package only for the one-time import", () => {
    let manifest = read("AnkiDroid/src/quest/AndroidManifest.xml");
    const bridge = [
        '<uses-permission android:name="com.ichi2.anki.quest.permission.ANKIQUEST_MOVE" />',
        '<package android:name="com.ichi2.anki.quest" />',
    ];
    for (const entry of bridge) {
        assert.ok(manifest.includes(entry), entry);
        manifest = manifest.replace(entry, "");
    }
    assert.doesNotMatch(manifest, /com\.ichi2\.anki\.quest\b/);
    assert.doesNotMatch(manifest, /AnkiquestMoveProvider/);
});
