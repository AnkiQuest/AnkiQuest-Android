// SPDX-License-Identifier: AGPL-3.0-only
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

// AnkiQuest-Android uses only org.ankiquest backend artifacts, never upstream's (hill, 2026-11-21).
const UPSTREAM_BACKEND_GROUP = "io.github.david-allison";

// Files that name the group to reject it: this test and the Gradle resolution guard.
const GUARDS = [
    ".github/tests/upstream-backend.test.cjs",
    "buildSrc/src/main/kotlin/com/ichi2/anki/gradle/UpstreamBackendGuard.kt",
];

// Generated and tool directories hold no committed build configuration.
const SKIPPED_DIRECTORIES = new Set([".git", ".gradle", ".idea", "build", "node_modules"]);

/** Paths (relative to root, with "/") of files that mention the upstream backend group. */
function upstreamBackendFiles(root) {
    const found = [];
    const visit = (directory) => {
        for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
            const file = path.join(directory, entry.name);
            if (entry.isDirectory()) {
                if (!SKIPPED_DIRECTORIES.has(entry.name)) {
                    visit(file);
                }
                continue;
            }
            if (!entry.isFile()) {
                continue;
            }
            const relative = path.relative(root, file).split(path.sep).join("/");
            if (GUARDS.includes(relative)) {
                continue;
            }
            if (fs.readFileSync(file).includes(UPSTREAM_BACKEND_GROUP)) {
                found.push(relative);
            }
        }
    };
    visit(root);
    return found.sort();
}

function tree(t, files) {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), "aq-upstream-backend-"));
    t.after(() => fs.rmSync(root, { recursive: true, force: true }));
    for (const [name, contents] of Object.entries(files)) {
        fs.mkdirSync(path.dirname(path.join(root, name)), { recursive: true });
        fs.writeFileSync(path.join(root, name), contents);
    }
    return root;
}

test("a build file naming the upstream backend group is found", (t) => {
    const root = tree(t, {
        "gradle/libs.versions.toml": `ankiBackend-core = { module = "${UPSTREAM_BACKEND_GROUP}:anki-backend-core" }\n`,
        "gradle/verification-metadata.xml": `<component group="${UPSTREAM_BACKEND_GROUP}" name="anki-backend-android"/>\n`,
        "docs/build.md": "Uses org.ankiquest:anki-backend-core.\n",
        "build/generated.txt": UPSTREAM_BACKEND_GROUP,
    });
    assert.deepEqual(upstreamBackendFiles(root), ["gradle/libs.versions.toml", "gradle/verification-metadata.xml"]);
});

test("the guards may name the group", (t) => {
    const root = tree(t, Object.fromEntries(GUARDS.map((guard) => [guard, UPSTREAM_BACKEND_GROUP])));
    assert.deepEqual(upstreamBackendFiles(root), []);
});

test("no file in the repository names the upstream backend group", () => {
    assert.deepEqual(upstreamBackendFiles(path.resolve(__dirname, "../..")), []);
});
