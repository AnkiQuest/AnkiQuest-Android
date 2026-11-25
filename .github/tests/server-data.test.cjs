// SPDX-License-Identifier: AGPL-3.0-or-later
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const root = path.resolve(__dirname, "../..");
const resources = path.join(root, "AnkiDroid/src/test/resources");
const data = require("../ankiquest_server_data.cjs");

function fixture(t) {
    const folder = fs.mkdtempSync(path.join(os.tmpdir(), "aq-server-data-"));
    t.after(() => fs.rmSync(folder, { recursive: true, force: true }));
    const app = path.join(folder, "app");
    const server = path.join(folder, "server");
    const res = path.join(app, "AnkiDroid/src/test/resources");
    for (const directory of [
        path.join(server, "docs/api/examples"),
        path.join(server, "tests/fixtures"),
        path.join(res, "ankiquest"),
        path.join(res, "ankiquest-api"),
    ]) {
        fs.mkdirSync(directory, { recursive: true });
    }
    fs.writeFileSync(path.join(server, "docs/api/examples/profile.json"), '{"xp": 5}\n');
    fs.writeFileSync(path.join(server, "docs/api/examples/README.md"), "notes\n");
    fs.writeFileSync(path.join(server, "tests/fixtures/clients.json"), '{"days": []}\n');
    fs.writeFileSync(path.join(res, "ankiquest-api/names.json"), '{"consumed":["profile"]}\n');
    return { app, server, res };
}

test("copy takes the exact bytes and keeps the catalog", t => {
    const { app, server, res } = fixture(t);
    data.copy(app, server);
    assert.equal(fs.readFileSync(path.join(res, "ankiquest-api/profile.json"), "utf8"), '{"xp": 5}\n');
    assert.equal(fs.readFileSync(path.join(res, "ankiquest/clients.json"), "utf8"), '{"days": []}\n');
    assert.equal(
        fs.readFileSync(path.join(res, "ankiquest-api/names.json"), "utf8"),
        '{"consumed":["profile"]}\n',
    );
    assert.ok(!fs.existsSync(path.join(res, "ankiquest-api/README.md")));
});

test("the server's companion poses are not copied; Art's are pinned instead", t => {
    const { app, server, res } = fixture(t);
    fs.writeFileSync(path.join(server, "tests/fixtures/companion_poses.json"), '{"core": ["face"]}\n');
    fs.writeFileSync(path.join(res, "ankiquest/companion_poses.json"), '{"core": ["stale"]}\n');
    data.copy(app, server);
    assert.ok(!fs.existsSync(path.join(res, "ankiquest/companion_poses.json")));
});

test("the art poses are downloaded from the pinned release and checked", async t => {
    const { app, res } = fixture(t);
    const poses = '{"core": ["face", "asleep"]}\n';
    const sha = require("node:crypto").createHash("sha256").update(poses).digest("hex");
    const pin = path.join(app, "ankiquest-art.json");
    fs.writeFileSync(pin, JSON.stringify({ "tag": "art-11", "assets": { "poses.json": sha } }));
    const downloads = [];
    const fetched = url => {
        downloads.push(url);
        return poses;
    };
    await data.pinnedPoses(app, fetched);
    assert.deepEqual(downloads, ["https://github.com/AnkiQuest/AnkiQuest-Art/releases/download/art-11/poses.json"]);
    assert.equal(fs.readFileSync(path.join(res, "ankiquest/poses.json"), "utf8"), poses);

    fs.writeFileSync(pin, JSON.stringify({ "tag": "art-11", "assets": { "poses.json": "0".repeat(64) } }));
    fs.unlinkSync(path.join(res, "ankiquest/poses.json"));
    await assert.rejects(() => data.pinnedPoses(app, fetched), /SHA-256/);
    assert.ok(!fs.existsSync(path.join(res, "ankiquest/poses.json")));

    fs.writeFileSync(pin, JSON.stringify({ "tag": null, "assets": {} }));
    await assert.rejects(() => data.pinnedPoses(app, fetched), /AnkiQuest-Art release/);
});

test("a second copy removes examples the server dropped and adds new ones", t => {
    const { app, server, res } = fixture(t);
    data.copy(app, server);
    fs.writeFileSync(path.join(server, "docs/api/examples/new_feature.json"), "{}\n");
    fs.unlinkSync(path.join(server, "docs/api/examples/profile.json"));
    data.copy(app, server);
    assert.deepEqual(fs.readdirSync(path.join(res, "ankiquest-api")).sort(), ["names.json", "new_feature.json"]);
});

test("bad or empty server data changes nothing", t => {
    const { app, server, res } = fixture(t);
    data.copy(app, server);
    fs.writeFileSync(path.join(server, "docs/api/examples/profile.json"), '{"xp": 8}\n');
    fs.writeFileSync(path.join(server, "tests/fixtures/clients.json"), "not json");
    assert.throws(() => data.copy(app, server));
    assert.equal(fs.readFileSync(path.join(res, "ankiquest-api/profile.json"), "utf8"), '{"xp": 5}\n');
    fs.writeFileSync(path.join(server, "tests/fixtures/clients.json"), "{}");
    fs.unlinkSync(path.join(server, "docs/api/examples/profile.json"));
    assert.throws(() => data.copy(app, server), /No JSON data/);
    fs.writeFileSync(path.join(server, "docs/api/examples/names.json"), "{}");
    assert.throws(() => data.copy(app, server), /must not replace/);
});

test("refs and arguments are checked", () => {
    assert.equal(data.serverRef(), "master");
    assert.equal(data.serverRef("feature/coordinated-change"), "feature/coordinated-change");
    assert.throws(() => data.serverRef("--upload-pack=x"), /Invalid server ref/);
    assert.throws(() => data.serverRef("master\nref=other"), /Invalid server ref/);
    assert.deepEqual(data.parse([]), { ref: "master", server: undefined });
    assert.deepEqual(data.parse(["--ref", "topic", "../server"]), { ref: "topic", server: "../server" });
    assert.throws(() => data.parse(["a", "b"]), /Use fetch/);
});

test("server data is not committed; Android's catalog is", () => {
    const ignore = fs.readFileSync(path.join(resources, ".gitignore"), "utf8");
    assert.match(ignore, /^\/ankiquest\/$/m);
    assert.match(ignore, /^\/ankiquest-api\/\*\.json$/m);
    assert.match(ignore, /^!\/ankiquest-api\/names\.json$/m);
    assert.ok(!fs.existsSync(path.join(resources, "ankiquest-server.sha")));
    const names = JSON.parse(fs.readFileSync(path.join(resources, "ankiquest-api/names.json"), "utf8"));
    assert.deepEqual(Object.keys(names).sort(), ["boards", "challenges", "consumed", "not_yet", "unused"]);
});

test("every workflow that runs the unit tests fetches server data first", () => {
    for (const name of [
        "ankiquest_api_contract.yml",
        "ankiquest_banner.yml",
        "screenshot_compare.yml",
        "screenshot_store.yml",
        "tests_unit.yml",
    ]) {
        const workflow = fs.readFileSync(path.join(root, ".github/workflows", name), "utf8");
        const fetch = workflow.indexOf("node .github/ankiquest_server_data.cjs fetch");
        assert.ok(fetch > 0, `${name} does not fetch server data`);
        assert.ok(fetch < workflow.indexOf("./gradlew"), `${name} runs Gradle before fetching server data`);
    }
});

test("the art check runs a fixed exporter commit, never server master", () => {
    const art = fs.readFileSync(path.join(root, ".github/workflows", "ankiquest_art_check.yml"), "utf8");
    assert.match(art, /\n  EXPORTER: [0-9a-f]{40}\n/);
    assert.match(art, /ref: \$\{\{ env\.EXPORTER \}\}/);
    assert.doesNotMatch(art, /\bref: master\b|workflow_dispatch|server_ref/);
});
