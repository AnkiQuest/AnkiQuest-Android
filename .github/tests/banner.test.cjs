// SPDX-License-Identifier: AGPL-3.0-or-later
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const root = path.join(__dirname, "../..");
const workflow = fs.readFileSync(path.join(root, ".github/workflows/ankiquest_banner.yml"), "utf8");
const config = fs.readFileSync(path.join(root, "docs/graphics/banner.toml"), "utf8");

// Jobs, each from its "  <name>:" line to the next job.
const job = (name, next) =>
    workflow.slice(workflow.indexOf(`\n  ${name}:\n`), next ? workflow.indexOf(`\n  ${next}:\n`) : undefined);
const record = job("record", "banner");
const banner = job("banner", "propose");
const propose = job("propose");

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

test("the record job runs the record command of banner.toml", () => {
    const command = config.match(/^record = \[(.*)\]$/m)[1]
        .split(", ")
        .map(part => JSON.parse(part))
        .map(part => (part.includes("*") ? `'${part}'` : part))
        .join(" ");
    assert.match(record, new RegExp(`\\n {8}run: ${command.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}\\n`));
    assert.match(record, /name: banner-screens\n {10}path: AnkiDroid\/build\/outputs\/roborazzi\/AnkiquestBannerScreenshots\/\*\.png/);
});

test("the shared workflow draws and checks the images from the recorded screens", () => {
    assert.match(banner, /uses: AnkiQuest\/AnkiQuest-Dev\/\.github\/workflows\/banner\.yml@master/);
    assert.match(banner, /server-sha-file: AnkiDroid\/src\/test\/resources\/ankiquest-server\.sha/);
    assert.match(banner, /screens-artifact: banner-screens/);
    assert.ok(config.includes("AnkiDroid/build/outputs/roborazzi/AnkiquestBannerScreenshots/"));
});

test("a pull request fails when a committed image is stale", () => {
    assert.match(banner, /on-stale: \$\{\{ github\.event_name == 'pull_request' && 'fail' \|\| 'report' \}\}/);
    assert.match(propose, /if: github\.event_name != 'pull_request' && needs\.banner\.outputs\.stale == 'true'/);
});

test("a pull request that changes the images or their inputs runs the check", () => {
    for (const file of [
        "docs/graphics/banner.toml",
        "docs/graphics/logos/banner_readme.png",
        "docs/graphics/logos/social_preview.png",
        "AnkiDroid/src/test/resources/ankiquest-server.sha",
    ]) {
        assert.match(workflow, new RegExp(`pull_request:\\n {4}paths:[\\s\\S]*?- '${file.replace(/\./g, "\\.")}'`));
    }
});
