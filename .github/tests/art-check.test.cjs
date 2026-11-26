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

/**
 * An app with `files` (path under AnkiDroid/src to text) and a release zip with `released`
 * drawables, pinned by its hash.
 */
function fixture(t, files, released, extra = []) {
    const folder = fs.mkdtempSync(path.join(os.tmpdir(), "aq-art-check-"));
    t.after(() => fs.rmSync(folder, { recursive: true, force: true }));
    const app = path.join(folder, "app");
    for (const [name, text] of Object.entries(files)) {
        const file = path.join(app, "AnkiDroid/src", name);
        fs.mkdirSync(path.dirname(file), { recursive: true });
        fs.writeFileSync(file, text);
    }
    fs.mkdirSync(path.join(app, "AnkiDroid/src"), { recursive: true });

    // The release zip, as Art publishes it: drawable-nodpi/ and the licence.
    const release = path.join(folder, "release");
    fs.mkdirSync(path.join(release, "drawable-nodpi"), { recursive: true });
    fs.writeFileSync(path.join(release, "LICENSE-Anki-tan.md"), "licence\n");
    for (const name of released) {
        fs.writeFileSync(path.join(release, "drawable-nodpi", `${name}.webp`), name);
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

const code = {
    "main/java/Companion.kt": "val a = R.drawable.aki_face\nval b = R.drawable.bluma_streak\n",
    "main/res/layout/widget.xml": '<ImageView android:src="@drawable/anki_tan_face" />',
};

test("an app that commits no companion picture and uses only released ones passes", async t => {
    const { app, zip } = fixture(t, code, ["aki_face", "bluma_streak", "anki_tan_face", "cow_icon"]);
    assert.deepEqual(await art.check(app, zip), { tag: "art-11", count: 4, problems: [] });
});

test("a committed companion picture fails, even one the release has", async t => {
    const { app, zip } = fixture(
        t,
        { ...code, "main/res/drawable-nodpi/aki_face.webp": "aki_face", "quest/res/drawable-xxhdpi/anki_tan_icon.png": "x" },
        ["aki_face", "bluma_streak", "anki_tan_face"],
    );
    assert.deepEqual((await art.check(app, zip)).problems, [
        "main/res/drawable-nodpi/aki_face.webp: committed; the build takes it from the pinned release",
        "quest/res/drawable-xxhdpi/anki_tan_icon.png: committed; the build takes it from the pinned release",
    ]);
});

test("a companion drawable the code uses and the release lacks fails", async t => {
    const { app, zip } = fixture(t, code, ["aki_face", "anki_tan_face"]);
    assert.deepEqual((await art.check(app, zip)).problems, ["bluma_streak: used by the app, not in the pinned release"]);
});

test("a companion drawable committed as XML is not looked up in the release", async t => {
    const files = {
        "quest/res/drawable/aki_launcher_foreground.xml": "<inset />",
        "quest/res/mipmap-anydpi/ic.xml": '<foreground android:drawable="@drawable/aki_launcher_foreground" />',
    };
    const { app, zip } = fixture(t, files, ["aki_face"]);
    assert.deepEqual((await art.check(app, zip)).problems, []);
});

test("pictures that are not companions are not checked", async t => {
    const { app, zip } = fixture(t, { "main/res/drawable-nodpi/aq_flame.webp": "y", "main/res/drawable/ic_launcher.png": "x" }, ["aki_face"]);
    assert.deepEqual((await art.check(app, zip)).problems, []);
});

test("every companion prefix is checked", () => {
    for (const name of ["aki_face.webp", "ankilope_streak.png", "anki_tan_sad.webp", "bluma_happy.webp", "cow_icon.webp", "duo_friends.webp"]) {
        assert.match(name, art.COMPANION);
    }
    assert.doesNotMatch("akiface.webp", art.COMPANION);
    assert.doesNotMatch("aki_launcher_foreground.xml", art.COMPANION);
});

test("a zip without the pinned hash is refused", async t => {
    const { app, zip } = fixture(t, {}, ["aki_face"]);
    fs.appendFileSync(zip, "tampered");
    await assert.rejects(art.check(app, zip), /pinned SHA-256/);
});

test("a zip with unexpected entries is refused", async t => {
    const { app, zip } = fixture(t, {}, ["aki_face"], ["run.sh"]);
    await assert.rejects(art.check(app, zip), /Unexpected entries in android-drawables.zip: run.sh/);
});

test("a zip without drawables is refused", async t => {
    const { app, zip } = fixture(t, {}, []);
    await assert.rejects(art.check(app, zip), /no drawables/);
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

test("this repository commits no companion picture", () => {
    assert.deepEqual(art.committed(root), []);
});

test("the art check workflow runs the pinned release check, not a server exporter", () => {
    const workflow = fs.readFileSync(path.join(root, ".github/workflows/ankiquest_art_check.yml"), "utf8");
    assert.match(workflow, /node \.github\/ankiquest_art_check\.cjs check/);
    assert.doesNotMatch(workflow, /AnkiQuest-Server|export-android-art|EXPORTER/);
});

test("the app build unpacks the pinned drawables into generated resources", () => {
    const build = fs.readFileSync(path.join(root, "AnkiDroid/build.gradle.kts"), "utf8");
    assert.match(build, /tasks\.register<AnkiquestArtTask>/);
    assert.match(build, /variant\.sources\.res\?\.addGeneratedSourceDirectory\(art, AnkiquestArtTask::resDir\)/);
    assert.match(build, /gradleProperty\("ankiquestArtCache"\)/);
    assert.match(build, /environmentVariable\("ANKIQUEST_ART_CACHE"\)/);
});
