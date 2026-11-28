// SPDX-License-Identifier: AGPL-3.0-or-later
const assert = require("node:assert/strict");
const { execFileSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

// F-Droid publishes the APK the release workflow uploads only if its own build from the
// recipe matches it. Both must build with the same inputs and agree on the file name.
const root = path.join(__dirname, "../..");
const recipe = fs.readFileSync(path.join(root, "docs/fdroid/recipe.yml"), "utf8");
const workflow = fs.readFileSync(path.join(root, ".github/workflows/ankiquest_apk.yml"), "utf8");

// A step of the workflow, from its name to the next step.
function step(name) {
    const start = workflow.indexOf(`- name: ${name}\n`);
    assert.notEqual(start, -1, `ankiquest_apk.yml must have a "${name}" step`);
    const next = workflow.indexOf("\n      - ", start + 1);
    return workflow.slice(start, next === -1 ? undefined : next);
}

// The step that builds the F-Droid APK.
const fdroidStep = () => step("F-Droid APK");

// The entries of a top-level list in the last build of the recipe, e.g. gradleprops.
function lastBuildList(key) {
    const builds = recipe.split(/\n  - versionName: /);
    const lines = builds[builds.length - 1].split("\n");
    const start = lines.findIndex((line) => line.trim() === `${key}:`);
    assert.notEqual(start, -1, `the last build must have ${key}`);
    const items = [];
    for (const line of lines.slice(start + 1)) {
        // Comments between entries belong to the list.
        if (/^ {6}#/.test(line)) {
            continue;
        }
        const item = line.match(/^ {6}- (.*)$/);
        if (!item) {
            break;
        }
        items.push(item[1].trim());
    }
    return items;
}

test("the F-Droid APK step passes every gradle property of the recipe", () => {
    const step = fdroidStep();
    const props = lastBuildList("gradleprops");
    assert.ok(props.length > 0);
    for (const prop of props) {
        assert.ok(step.includes(`-P${prop}`), `missing -P${prop}`);
    }
    assert.ok(step.includes("-Duniversal-apk=true"), "the recipe builds the universal APK");
    assert.ok(recipe.includes('echo "systemProp.universal-apk=true"'));
});

test("the F-Droid APK step sets the release inputs the recipe's prebuild sets", () => {
    const step = fdroidStep();
    for (const prop of ["ankiquestRelease", "upstreamCommit"]) {
        assert.ok(recipe.includes(`echo "${prop}=`), `recipe sets ${prop}`);
        assert.ok(step.includes(`-P${prop}=`), `step sets ${prop}`);
    }
    assert.ok(step.includes("SOURCE_DATE_EPOCH"), "build time from the commit, as on F-Droid");
});

test("the F-Droid APK is checked and signed with the GitHub APKs", () => {
    const build = workflow.indexOf("      - name: F-Droid APK\n");
    for (const name of ["Package", "Sign with the AnkiQuest release key"]) {
        assert.ok(build < workflow.indexOf(`      - name: ${name}\n`), `the F-Droid APK is built before ${name}`);
        assert.match(step(name), /for apk in [^\n]*AnkiQuest-\*\.apk fdroid\.apk; do\n\s*\[ -f "\$apk" \] \|\| continue\n/);
    }
    assert.ok(step("Sign with the AnkiQuest release key").includes("--ks-key-alias ankiquest"));
    assert.ok(!fdroidStep().includes("apksigner"), "the F-Droid build leaves signing to the Sign step");
    assert.match(recipe, /^AllowedAPKSigningKeys: b397a61ecfaf0fe354da611772515e683ccb6fd0e952d477b8b3bca49e6c490d$/m);
});

test("Binaries names the file the step uploads", () => {
    const binaries = recipe.match(/^Binaries: (.*)$/m);
    assert.ok(binaries, "the recipe must set Binaries");
    assert.equal(
        binaries[1],
        "https://github.com/AnkiQuest/AnkiQuest-Android/releases/download/fdroid/AnkiQuest-%c.apk",
    );
    const publish = step("Publish F-Droid APK");
    assert.ok(publish.includes('gh release upload fdroid "AnkiQuest-$code.apk"'));
    for (const body of [fdroidStep(), publish]) {
        assert.ok(body.includes("startsWith(github.ref, 'refs/tags/quest-')"), "only releases go to F-Droid");
    }
});

test("the fdroid release never becomes the latest release the updater reads", () => {
    assert.ok(step("Publish F-Droid APK").includes("--latest=false"));
});

// The art the build bundles comes from the AnkiQuest-Art release ankiquest-art.json pins. F-Droid
// fetches it in prebuild and checks it against the pin before the build reads it from the cache.
test("the recipe prefetches every pinned pack and checks its SHA-256", () => {
    const prebuild = lastBuildList("prebuild");
    const list = prebuild.find((line) => line.startsWith("sed -n") && line.includes("SHA256SUMS"));
    assert.ok(list, "the prebuild lists the pinned assets");

    // The listing, run on the pin, names packs.json, every pack and the launcher icons with their hashes.
    const script = list.match(/^sed -n '([^']*)' \.\.\/ankiquest-art\.json > /);
    assert.ok(script, list);
    const listed = execFileSync("sed", ["-n", script[1], path.join(root, "ankiquest-art.json")], { encoding: "utf8" });
    const pin = JSON.parse(fs.readFileSync(path.join(root, "ankiquest-art.json"), "utf8"));
    const wanted = Object.entries(pin.assets)
        .filter(([name]) => /^(packs\.json|pack-[a-z0-9_]+\.zip|android-drawables\.zip)$/.test(name))
        .map(([name, sha]) => `${sha}  ${name}`);
    assert.ok(wanted.length > 0);
    assert.deepEqual(listed.trim().split("\n").sort(), wanted.sort());

    // Each is downloaded from the pinned tag, checked, then stored by hash as the build's cache expects.
    const steps = prebuild.join("\n");
    assert.ok(steps.includes("https://github.com/AnkiQuest/AnkiQuest-Art/releases/download/$tag/$name"));
    assert.match(steps, /sha256sum -c SHA256SUMS && while read -r sum name; do mv "\$name" "\$sum"; done/);
    assert.ok(lastBuildList("gradleprops").includes("ankiquestArtCache=ankiquest-art"));
    assert.ok(steps.includes("mkdir -p ../ankiquest-art"));
    assert.deepEqual(lastBuildList("scanignore"), ["ankiquest-art"]);
});
