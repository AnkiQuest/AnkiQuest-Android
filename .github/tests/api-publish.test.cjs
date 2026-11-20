// SPDX-License-Identifier: AGPL-3.0-only
const assert = require("node:assert/strict");
const { spawnSync } = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const root = path.join(__dirname, "../..");
const workflow = fs.readFileSync(path.join(root, ".github/workflows/ankiquest_api_publish.yml"), "utf8");
const released = path.join(root, ".github/ankiquest_api_released.sh");

// Returns the text of one top-level job, up to the next job.
function job(name) {
    const block = workflow.match(new RegExp(`^  ${name}:\\n((?: {4}.*\\n|\\n)*)`, "m"));
    assert.ok(block, `job ${name} exists`);
    return block[1];
}

// Runs the released-version check with fake git and curl, so no test contacts
// GitHub or Maven Central. `tag` and `pom` say what already exists.
function check({ tag = false, pom = false, gitExit = tag ? 0 : 2, curlExit = 0, http = pom ? 200 : 404 }) {
    const bin = fs.mkdtempSync(path.join(os.tmpdir(), "api-publish-"));
    const output = path.join(bin, "output");
    fs.writeFileSync(path.join(bin, "git"), `#!/bin/sh\nexit ${gitExit}\n`, { mode: 0o755 });
    fs.writeFileSync(path.join(bin, "curl"), `#!/bin/sh
if [ ${curlExit} -ne 0 ]; then exit ${curlExit}; fi
for arg in "$@"; do
  case "$arg" in
    -w|--write-out) printf '%s' '${http}' ;;
    -*f*|--fail) if [ ${http} -ge 400 ]; then exit 22; fi ;;
  esac
done
`, { mode: 0o755 });
    fs.writeFileSync(output, "");

    try {
        const result = spawnSync("bash", [released, "1.0.0-ankiquest.1"], {
            env: { ...process.env, PATH: `${bin}:${process.env.PATH}`, GITHUB_OUTPUT: output },
            encoding: "utf8",
        });
        assert.ifError(result.error);
        return { status: result.status, output: fs.readFileSync(output, "utf8").trim() };
    } finally {
        fs.rmSync(bin, { recursive: true, force: true });
    }
}

// Two release runs of the same version must not both upload it: the publish job
// waits for any other run of that version and never cancels a different release.
test("publish runs of one version are serialised, never cancelled", () => {
    const publish = job("publish");
    assert.match(publish, /^ {4}concurrency:\n {6}group: .*needs\.version\.outputs\.version.*\n {6}cancel-in-progress: false$/m);
});

// The version job's answer may be stale once the lock is held, so the publish
// job asks again before uploading and skips the upload and the tag if released.
test("publish re-checks the release after waiting", () => {
    const publish = job("publish");
    const recheck = publish.indexOf("id: released");
    const upload = publish.indexOf("name: Publish to Maven Central");
    assert.ok(recheck > 0 && recheck < upload, "re-check runs before the upload");

    for (const step of ["Publish to Maven Central", "Tag the release"]) {
        const at = publish.indexOf(`name: ${step}`);
        const next = publish.indexOf("\n      - ", at);
        const body = publish.slice(at, next === -1 ? undefined : next);
        assert.match(body, /if: steps\.released\.outputs\.publish == 'true'/, step);
    }
});

// A repeated run finds what the first one left: the tag, or the POM when the tag
// push failed after the upload. Either counts as released.
test("a version is new only without tag and POM", () => {
    assert.deepEqual(check({ tag: false, pom: false }), { status: 0, output: "publish=true" });
    assert.deepEqual(check({ tag: true, pom: false }), { status: 0, output: "publish=false" });
    assert.deepEqual(check({ tag: false, pom: true }), { status: 0, output: "publish=false" });
});

// A failed lookup does not prove a version is absent. Refuse an irreversible
// upload when GitHub or Maven Central could not answer the existence check.
for (const [name, options] of [
    ["GitHub lookup failure", { gitExit: 128 }],
    ["Maven connection failure", { curlExit: 6 }],
    ["Maven timeout", { curlExit: 28 }],
    ["Maven server error", { http: 500 }],
    ["Maven access refusal", { http: 403 }],
    ["Maven redirect", { http: 301 }],
]) {
    test(`${name} cannot authorize publishing`, () => {
        const result = check(options);
        assert.notEqual(result.status, 0, "an uncertain release check must fail");
        assert.doesNotMatch(result.output, /publish=true/);
    });
}
