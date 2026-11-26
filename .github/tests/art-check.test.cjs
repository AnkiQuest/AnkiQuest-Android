// SPDX-License-Identifier: AGPL-3.0-or-later
const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");
const { execFileSync } = require("node:child_process");

const root = path.resolve(__dirname, "../..");
const art = require("../ankiquest_art_check.cjs");

/** An app with `committed` drawables and a release zip with `released` ones, pinned by its hash. */
function fixture(t, committed, released, extra = []) {
    const folder = fs.mkdtempSync(path.join(os.tmpdir(), "aq-art-check-"));
    t.after(() => fs.rmSync(folder, { recursive: true, force: true }));
    const app = path.join(folder, "app");
    const drawables = path.join(app, "AnkiDroid/src/main/res/drawable-nodpi");
    fs.mkdirSync(drawables, { recursive: true });
    for (const [name, bytes] of Object.entries(committed)) {
        fs.writeFileSync(path.join(drawables, name), bytes);
    }

    // The release zip, as Art publishes it: drawable-nodpi/ and the licence.
    const release = path.join(folder, "release");
    fs.mkdirSync(path.join(release, "drawable-nodpi"), { recursive: true });
    fs.writeFileSync(path.join(release, "LICENSE-Anki-tan.md"), "licence\n");
    for (const [name, bytes] of Object.entries(released)) {
        fs.writeFileSync(path.join(release, "drawable-nodpi", name), bytes);
    }
    for (const name of extra) {
        fs.writeFileSync(path.join(release, name), "x");
    }
    const zip = path.join(folder, "android-drawables.zip");
    execFileSync("zip", ["-qr", zip, "."], { cwd: release });
    const sha = crypto.createHash("sha256").update(fs.readFileSync(zip)).digest("hex");
    fs.writeFileSync(
        path.join(app, "ankiquest-art.json"),
        JSON.stringify({ tag: "art-11", assets: { "android-drawables.zip": sha } }),
    );
    return { app, zip };
}

test("the exported copies pass", async t => {
    const { app, zip } = fixture(t, { "aki_face.webp": "a", "bluma_face.webp": "b" }, { "aki_face.webp": "a", "bluma_face.webp": "b", "cow_icon.webp": "c" });
    assert.deepEqual(await art.check(app, zip), { tag: "art-11", problems: [] });
});

test("a picture with other bytes than the release's fails", async t => {
    const { app, zip } = fixture(t, { "anki_tan_face.webp": "chibi" }, { "anki_tan_face.webp": "face" });
    assert.deepEqual((await art.check(app, zip)).problems, ["anki_tan_face.webp: differs from the pinned release"]);
});

test("a companion picture the release does not have fails", async t => {
    const { app, zip } = fixture(t, { "anki_tan_review.webp": "r", "aki_face.webp": "a" }, { "aki_face.webp": "a" });
    assert.deepEqual((await art.check(app, zip)).problems, ["anki_tan_review.webp: not in the pinned release"]);
});

test("drawables that are not companion pictures are not checked", async t => {
    const { app, zip } = fixture(t, { "ic_launcher.webp": "x", "aq_flame.webp": "y" }, {});
    assert.deepEqual((await art.check(app, zip)).problems, []);
});

test("every companion prefix is checked", () => {
    for (const name of ["aki_face.webp", "ankilope_streak.webp", "anki_tan_sad.webp", "bluma_happy.webp", "cow_icon.webp", "duo_friends.webp"]) {
        assert.match(name, art.COMPANION);
    }
    assert.doesNotMatch("akiface.webp", art.COMPANION);
});

test("a zip without the pinned hash is refused", async t => {
    const { app, zip } = fixture(t, {}, {});
    fs.appendFileSync(zip, "tampered");
    await assert.rejects(art.check(app, zip), /pinned SHA-256/);
});

test("a zip with unexpected entries is refused", async t => {
    const { app, zip } = fixture(t, {}, {}, ["run.sh"]);
    await assert.rejects(art.check(app, zip), /Unexpected entries in android-drawables.zip: run.sh/);
});

test("a pin without a tag or hash is refused", t => {
    const folder = fs.mkdtempSync(path.join(os.tmpdir(), "aq-art-pin-"));
    t.after(() => fs.rmSync(folder, { recursive: true, force: true }));
    fs.writeFileSync(path.join(folder, "ankiquest-art.json"), JSON.stringify({ tag: "--help", assets: {} }));
    assert.throws(() => art.pin(folder), /no art-<N> tag/);
    fs.writeFileSync(path.join(folder, "ankiquest-art.json"), JSON.stringify({ tag: "art-11", assets: {} }));
    assert.throws(() => art.pin(folder), /no SHA-256/);
});

test("this repository pins android-drawables.zip", () => {
    assert.match(art.pin(root).sha, /^[0-9a-f]{64}$/);
});

test("the art check workflow runs the pinned release check, not a server exporter", () => {
    const workflow = fs.readFileSync(path.join(root, ".github/workflows/ankiquest_art_check.yml"), "utf8");
    assert.match(workflow, /node \.github\/ankiquest_art_check\.cjs check/);
    assert.doesNotMatch(workflow, /AnkiQuest-Server|export-android-art|EXPORTER/);
});
