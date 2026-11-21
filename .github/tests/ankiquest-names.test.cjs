// SPDX-License-Identifier: AGPL-3.0-only
// hill decided that AnkiQuest-Android produces nothing named com.ichi2.anki: no application id,
// provider authority, declared permission or Maven group, and no package of AnkiQuest's own code.
// Upstream AnkiDroid's packages and the api/ library (AnkiDroid's code, which other apps compile
// against) keep their names (#461). tools/ankiquest-packages does the package move.
const assert = require("node:assert/strict");
const { execFileSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const root = path.join(__dirname, "../..");
const read = (file) => fs.readFileSync(path.join(root, file), "utf8");

// Joined, so tools/ankiquest-packages does not rewrite the name it guards against.
const OLD_PACKAGE = ["com", "ichi2", "anki", "ankiquest"].join(".");
const OLD_DIRECTORY = OLD_PACKAGE.replaceAll(".", "/");
const UPSTREAM = /com\.ichi2\.anki/;
const KEEP_MARKER = "ankiquest-packages: keep";

// Names of the old com.ichi2.anki.quest app that the new app needs to take over its settings
// (#432). Remove them when the move from com.ichi2.anki.quest is retired.
const MOVE_PERMISSION = "com.ichi2.anki.quest.permission.ANKIQUEST_MOVE";

// Not ours: AnkiDroid's library, the generated instructions and the rename tool itself.
const SKIPPED = [/^api\//, /^AGENTS\.md$/, /^CLAUDE\.md$/, /^tools\/ankiquest-packages\//];

const tracked = execFileSync("git", ["ls-files", "-z"], { cwd: root, encoding: "utf8" })
    .split("\0")
    .filter((file) => file && !SKIPPED.some((pattern) => pattern.test(file)))
    .filter((file) => fs.existsSync(path.join(root, file)));
const byName = (pattern) => tracked.filter((file) => pattern.test(file));

const gradleFiles = byName(/(^|\/)build\.gradle\.kts$/);
const manifests = byName(/(^|\/)AndroidManifest\.xml$/).filter((file) => !/\/src\/(test|androidTest)\//.test(file));
const sources = byName(/\.(kt|java)$/);

test("no application id starts with com.ichi2.anki", () => {
    for (const file of gradleFiles) {
        assert.doesNotMatch(read(file), /\bapplicationId\s*=\s*"com\.ichi2\.anki/, file);
    }
});

test("no Maven group starts with com.ichi2.anki", () => {
    for (const file of [...gradleFiles, "api/build.gradle.kts"]) {
        const text = read(file);
        assert.doesNotMatch(text, /^\s*group\s*=\s*"com\.ichi2\.anki/m, file);
        assert.doesNotMatch(text, /\bgroupId\s*=\s*"com\.ichi2\.anki/, file);
        assert.doesNotMatch(text, /coordinates\(\s*"com\.ichi2\.anki/, file);
    }
});

test("no provider authority or declared permission starts with com.ichi2.anki", () => {
    for (const file of manifests) {
        const text = read(file);
        for (const [, authorities] of text.matchAll(/android:authorities="([^"]*)"/g)) {
            assert.doesNotMatch(authorities, UPSTREAM, `${file}: ${authorities}`);
        }
        for (const [, name] of text.matchAll(/<permission\b[^>]*?android:name="([^"]*)"/g)) {
            if (name === MOVE_PERMISSION) {
                continue;
            }
            assert.doesNotMatch(name, UPSTREAM, `${file}: ${name}`);
        }
    }
});

test("AnkiQuest code is in org.ankiquest", () => {
    for (const file of sources) {
        const declared = read(file).match(/^package\s+([\w.]+)/m)?.[1] ?? "";
        assert.ok(!declared.startsWith(OLD_PACKAGE), `${file}: package ${declared}`);
        assert.ok(!file.includes(`/${OLD_DIRECTORY}/`), file);

        // Modules of AnkiQuest's own (ankiquest-core, ankiquest-ui) hold only org.ankiquest code.
        if (/^ankiquest-[^/]+\/src\//.test(file)) {
            assert.match(declared, /^org\.ankiquest(\.|$)/, file);
        }
    }
});

test("nothing names the old AnkiQuest package except lines kept for installed apps", () => {
    const text = byName(/\.(kt|kts|java|xml|pro|cjs|js|yml|yaml|md)$|META-INF\/services\//);
    for (const file of text) {
        read(file)
            .split("\n")
            .forEach((line, index) => {
                if (line.includes(OLD_PACKAGE) || line.includes(OLD_DIRECTORY)) {
                    assert.ok(line.includes(KEEP_MARKER), `${file}:${index + 1}: ${line.trim()}`);
                }
            });
    }
});
