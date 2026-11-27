// SPDX-License-Identifier: AGPL-3.0-or-later
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const workflows = path.join(__dirname, "../workflows");
const action = fs.readFileSync(path.join(__dirname, "../actions/ankiquest-art-cache/action.yml"), "utf8");
const read = name => fs.readFileSync(path.join(workflows, name), "utf8");

// The workflows with a Gradle dependency cache (gradle-cache.test.cjs) build the app, so each
// unpacks the pinned companion drawables and keeps android-drawables.zip cached too.
const CACHED = [
    "ankiquest_api_contract.yml",
    "lint.yml",
    "screenshot_compare.yml",
    "screenshot_store.yml",
    "tests_emulator.yml",
    "tests_unit.yml",
];

test("every cached workflow restores the art cache once, before Gradle runs", () => {
    for (const name of CACHED) {
        const text = read(name);
        const uses = text.match(/uses: \.\/\.github\/actions\/ankiquest-art-cache/g) ?? [];
        assert.equal(uses.length, 1, name);
        const art = text.indexOf("uses: ./.github/actions/ankiquest-art-cache");
        const gradle = text.indexOf("uses: ./.github/actions/gradle-cache");
        assert.ok(gradle >= 0 && art > gradle, `${name}: the art cache follows the gradle-cache restore`);
        assert.ok(!/\.\/gradlew/.test(text.slice(gradle, art)), `${name}: Gradle runs before the art cache`);
    }
});

test("the art cache is keyed by the pinned SHA-256 of android-drawables.zip", () => {
    assert.match(action, /assets\['android-drawables\.zip'\]/);
    assert.match(action, /key: ankiquest-art-v1-\$\{\{ steps\.pin\.outputs\.sha \}\}/);
});

// Nothing a pull request runs reaches a cache that master restores, as with gradle-cache.
test("only master pushes save the art cache", () => {
    const save = action.slice(action.indexOf("actions/cache/save"));
    const condition = action.slice(action.lastIndexOf("- name:", action.indexOf("actions/cache/save")), action.indexOf("actions/cache/save"));
    assert.match(condition, /github\.event_name == 'push' && github\.ref == 'refs\/heads\/master'/);
    assert.match(condition, /cache-hit != 'true'/);
    assert.match(save, /path: \.ankiquest-art-cache/);
});

test("a download is checked against the pin before it is cached", () => {
    assert.match(action, /does not have the pinned SHA-256/);
});

test("the build reads the cache folder the action fills", () => {
    assert.match(action, /ANKIQUEST_ART_CACHE=\.ankiquest-art-cache/);
    assert.match(fs.readFileSync(path.join(__dirname, "../../.gitignore"), "utf8"), /^\/\.ankiquest-art-cache\/$/m);
});
