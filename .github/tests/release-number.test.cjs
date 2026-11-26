// SPDX-License-Identifier: AGPL-3.0-or-later
const assert = require("node:assert/strict");
const { spawnSync } = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const workflow = fs.readFileSync(path.join(__dirname, "../workflows/ankiquest_apk.yml"), "utf8");

/** The `run:` script of the step named `name` in ankiquest_apk.yml. */
function script(name) {
    const lines = workflow.split("\n");
    const start = lines.findIndex(line => line.trim() === `- name: ${name}`);
    assert.ok(start >= 0, `no step ${name}`);
    const run = lines.findIndex((line, i) => i > start && line.trim() === "run: |");
    const indent = lines[run + 1].match(/^ */)[0];
    const body = [];
    for (const line of lines.slice(run + 1)) {
        if (line.trim() !== "" && !line.startsWith(indent)) {
            break;
        }
        body.push(line.slice(indent.length));
    }
    return body.join("\n");
}

/** Runs `name`'s script in a temporary folder with `env`; answers its exit status, stderr and outputs. */
function run(t, name, env, setup = () => {}) {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), "ankiquest-release-"));
    t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
    setup(directory);
    const output = path.join(directory, "output");
    fs.writeFileSync(output, "");
    const result = spawnSync("bash", ["-eo", "pipefail", "-c", script(name)], {
        cwd: directory,
        env: { ...process.env, PATH: directory + path.delimiter + process.env.PATH, GITHUB_OUTPUT: output, ...env },
        encoding: "utf8",
    });
    const outputs = Object.fromEntries(
        fs.readFileSync(output, "utf8").split("\n").filter(Boolean).map(line => line.split("=", 2)),
    );
    return { ...result, outputs, directory };
}

test("quest-<n> is release n with its own version code", t => {
    const result = run(t, "Release number", { GITHUB_REF: "refs/tags/quest-24" });
    assert.equal(result.status, 0, result.stderr);
    assert.deepEqual(result.outputs, { number: "24", point: "0", nightly: "0", version: "24", code: "50024000" });
});

test("quest-<n>.<p> is point release p of n, above n's nightlies and below n+1", t => {
    const result = run(t, "Release number", { GITHUB_REF: "refs/tags/quest-24.1" });
    assert.equal(result.status, 0, result.stderr);
    assert.deepEqual(result.outputs, { number: "24", point: "1", nightly: "0", version: "24.1", code: "50024901" });
    assert.ok(Number(result.outputs.code) > 50024000 && Number(result.outputs.code) < 50025000);
});

test("tags that are not quest-<n> or quest-<n>.<p> fail", t => {
    for (const tag of ["quest-24.0", "quest-24.10", "quest-24.1.1", "quest-24-hotfix", "quest-x", "quest-24."]) {
        const result = run(t, "Release number", { GITHUB_REF: `refs/tags/${tag}` });
        assert.notEqual(result.status, 0, tag);
        assert.match(result.stdout + result.stderr, /quest-<number>/, tag);
    }
});

test("the build gets the point release and checks its version code", () => {
    assert.match(workflow, /-PankiquestPoint="\$POINT"/);
    assert.match(script("Version code"), /"\$version_code" -ne "\$EXPECTED"/);
});

/** Runs the publish step with a fake gh that records its arguments. */
function publish(t, env) {
    return run(t, "Publish release", { GITHUB_REF_NAME: "quest-test", ...env }, directory => {
        fs.writeFileSync(path.join(directory, "AnkiQuest.apk"), "apk");
        fs.writeFileSync(path.join(directory, "gh"), '#!/usr/bin/env bash\nprintf "%s\\n" "$@" > gh-args\n', { mode: 0o755 });
    });
}

test("a release is published as the latest, named AnkiQuest <n>, with both APK names", t => {
    const result = publish(t, { VERSION: "25", POINT: "0" });
    assert.equal(result.status, 0, result.stderr);
    const args = fs.readFileSync(path.join(result.directory, "gh-args"), "utf8").split("\n");
    assert.deepEqual(args.slice(0, 5), ["release", "create", "quest-test", "AnkiQuest.apk", "AnkiDroid-Quest.apk"]);
    assert.equal(args[args.indexOf("--title") + 1], "AnkiQuest 25");
    assert.ok(!args.includes("--latest=false"));
});

// Updaters up to quest-24 read only releases/latest and parse its tag as quest-<n>: a point
// release there would hide every update from them.
test("a point release is not published as the latest", t => {
    const result = publish(t, { VERSION: "24.1", POINT: "1" });
    assert.equal(result.status, 0, result.stderr);
    const args = fs.readFileSync(path.join(result.directory, "gh-args"), "utf8").split("\n");
    assert.equal(args[args.indexOf("--title") + 1], "AnkiQuest 24.1");
    assert.ok(args.includes("--latest=false"));
    assert.ok(args.includes("AnkiDroid-Quest.apk"));
});
