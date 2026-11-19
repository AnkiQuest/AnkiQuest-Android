// SPDX-License-Identifier: AGPL-3.0-only
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const workflow = fs.readFileSync(path.join(__dirname, "../workflows/ankiquest_banner.yml"), "utf8");

// The propose job runs from "  propose:" to the end of the file.
const propose = workflow.slice(workflow.indexOf("\n  propose:\n"));

test("banner pull requests are opened with the sync token so their CI runs", () => {
    assert.ok(propose.length > 1);
    assert.doesNotMatch(propose, /secrets\.GITHUB_TOKEN/);
    assert.match(propose, /token: \$\{\{ secrets\.UPSTREAM_SYNC_TOKEN \}\}/);
    assert.match(propose, /GH_TOKEN: \$\{\{ secrets\.UPSTREAM_SYNC_TOKEN \}\}/);
});

test("banner job fails when the token is missing or the pull request cannot be opened", () => {
    assert.match(propose, /if \[ -z "\$UPSTREAM_SYNC_TOKEN" \]; then[\s\S]*?exit 1/);
    assert.doesNotMatch(propose, /::warning::/);
    assert.doesNotMatch(propose, /if ! gh pr create/);
    assert.match(propose, /\n {10}gh pr create /);
});

test("a rerun for the same release reuses the open banner pull request", () => {
    assert.match(propose, /gh pr list [^\n]*--head "\$branch"/);
});

// The render job runs from "  render:" to the propose job.
const render = workflow.slice(workflow.indexOf("\n  render:\n"), workflow.indexOf("\n  propose:\n"));

test("the render job runs the documented command", () => {
    assert.match(render, /cargo run --release --manifest-path tools\/ankiquest-banner\/Cargo\.toml -- --record\n/);
});

test("a pull request fails when its committed banner is stale", () => {
    assert.match(render, /if \[ "\$EVENT" = pull_request \]; then\s+ref=HEAD/);
    assert.match(render, /if: github\.event_name == 'pull_request' && steps\.compare\.outputs\.changed == 'true'\n[\s\S]*?exit 1/);
});

test("a pull request that changes the banner runs the check", () => {
    assert.match(workflow, /pull_request:\n {4}paths:[\s\S]*?- 'docs\/graphics\/logos\/banner_readme\.png'/);
});
