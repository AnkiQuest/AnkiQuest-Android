// SPDX-License-Identifier: AGPL-3.0-only
const assert = require("node:assert/strict");
const { execFileSync } = require("node:child_process");
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
function check({ tag, pom }) {
    const bin = fs.mkdtempSync(path.join(os.tmpdir(), "api-publish-"));
    const output = path.join(bin, "output");
    fs.writeFileSync(path.join(bin, "git"), `#!/bin/sh\nexit ${tag ? 0 : 2}\n`, { mode: 0o755 });
    fs.writeFileSync(path.join(bin, "curl"), `#!/bin/sh\nexit ${pom ? 0 : 22}\n`, { mode: 0o755 });
    fs.writeFileSync(output, "");

    execFileSync("bash", [released, "1.0.0-ankiquest.1"], {
        env: { ...process.env, PATH: `${bin}:${process.env.PATH}`, GITHUB_OUTPUT: output },
        stdio: "ignore",
    });
    return fs.readFileSync(output, "utf8").trim();
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
    assert.equal(check({ tag: false, pom: false }), "publish=true");
    assert.equal(check({ tag: true, pom: false }), "publish=false");
    assert.equal(check({ tag: false, pom: true }), "publish=false");
});
