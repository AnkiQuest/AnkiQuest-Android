// SPDX-License-Identifier: AGPL-3.0-or-later
// The app commits no companion picture: the build unpacks them from the android-drawables.zip of the
// AnkiQuest-Art release ankiquest-art.json pins (buildSrc AnkiquestArt.kt). This checks that the
// pinned zip has the pinned SHA-256 and only flat drawable entries, that no companion picture is
// committed under AnkiDroid/src, and that every companion drawable the app names is in the release
// (or is one of the app's own XML drawables, such as the launcher foregrounds).
const crypto = require("node:crypto");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { execFileSync } = require("node:child_process");

const PIN = "ankiquest-art.json";
const ASSET = "android-drawables.zip";
const RELEASES = "https://github.com/AnkiQuest/AnkiQuest-Art/releases/download";
const SOURCES = "AnkiDroid/src";

// Companion pictures by name: one prefix per companion as the Art exporter names them (Bluma's poses
// are bluma_*, her launcher icons cow_*), and the duo picture.
const PREFIX = "(?:aki|ankilope|anki_tan|bluma|cow|duo)_[a-z0-9_]+";
const COMPANION = new RegExp(`^${PREFIX}\\.(?:webp|png|jpe?g)$`);

// A companion drawable named in code or resources.
const REFERENCE = new RegExp(`(?:R\\.drawable\\.|@drawable/)(${PREFIX})`, "g");

// The zip holds flat drawables and licences, nothing else.
const DRAWABLE = /^drawable-nodpi\/([a-z][a-z0-9_]*)\.webp$/;
const OTHER = /^(drawable-nodpi\/|LICENSE-[A-Za-z-]+\.md)$/;

/** The pinned tag and the zip's SHA-256 from `root`'s ankiquest-art.json. */
function pin(root) {
    const json = JSON.parse(fs.readFileSync(path.join(root, PIN), "utf8"));
    if (!/^art-[1-9][0-9]*$/.test(json.tag || "")) {
        throw new Error(`${PIN} names no art-<N> tag`);
    }
    const sha = (json.assets || {})[ASSET];
    if (!/^[0-9a-f]{64}$/.test(sha || "")) {
        throw new Error(`${PIN} pins no SHA-256 for ${ASSET}`);
    }
    return { tag: json.tag, sha };
}

function sha256(bytes) {
    return crypto.createHash("sha256").update(bytes).digest("hex");
}

/** The drawable names in `zip`, after checking its hash and entry names. */
function released(zip, sha) {
    if (sha256(fs.readFileSync(zip)) !== sha) {
        throw new Error(`${ASSET} does not have the pinned SHA-256`);
    }
    const entries = execFileSync("unzip", ["-Z1", zip], { encoding: "utf8" }).split("\n").filter(Boolean);
    const unexpected = entries.filter((entry) => !DRAWABLE.test(entry) && !OTHER.test(entry));
    if (unexpected.length) {
        throw new Error(`Unexpected entries in ${ASSET}: ${unexpected.join(", ")}`);
    }
    const names = entries.map((entry) => DRAWABLE.exec(entry)?.[1]).filter(Boolean);
    if (!names.length) {
        throw new Error(`${ASSET} has no drawables`);
    }
    return new Set(names);
}

/** Every file under `folder`, relative to it, sorted. */
function files(folder) {
    if (!fs.existsSync(folder)) {
        return [];
    }
    return fs
        .readdirSync(folder, { recursive: true, withFileTypes: true })
        .filter((entry) => entry.isFile())
        .map((entry) => path.relative(folder, path.join(entry.parentPath, entry.name)).split(path.sep).join("/"))
        .sort();
}

/** The committed companion pictures under `root`'s AnkiDroid/src, relative to it. */
function committed(root) {
    return files(path.join(root, SOURCES)).filter((file) => COMPANION.test(path.posix.basename(file)));
}

/**
 * The companion drawables `root`'s code and resources name that are neither in `names` (the
 * release's) nor committed as the app's own XML drawables.
 */
function missing(root, names) {
    const sources = path.join(root, SOURCES);
    const all = files(sources);
    const own = new Set(all.filter((file) => /\/res\/drawable[^/]*\/[^/]+\.xml$/.test(file)).map((file) => path.posix.basename(file, ".xml")));
    const used = new Set();
    for (const file of all.filter((name) => /\.(kt|java|xml)$/.test(name))) {
        for (const match of fs.readFileSync(path.join(sources, file), "utf8").matchAll(REFERENCE)) {
            used.add(match[1]);
        }
    }
    return [...used].filter((name) => !names.has(name) && !own.has(name)).sort();
}

/** Downloads the pinned zip into a temporary file and answers its path. */
async function download(tag) {
    const response = await fetch(`${RELEASES}/${tag}/${ASSET}`);
    if (!response.ok) {
        throw new Error(`Downloading ${tag}/${ASSET} failed: HTTP ${response.status}`);
    }
    const file = path.join(fs.mkdtempSync(path.join(os.tmpdir(), "ankiquest-art-zip-")), ASSET);
    fs.writeFileSync(file, Buffer.from(await response.arrayBuffer()));
    return file;
}

/** Checks `root` against `zip`, or against the pinned release downloaded when no zip is given. */
async function check(root, zip) {
    const { tag, sha } = pin(root);
    const names = released(zip || (await download(tag)), sha);
    const problems = [
        ...committed(root).map((file) => `${file}: committed; the build takes it from the pinned release`),
        ...missing(root, names).map((name) => `${name}: used by the app, not in the pinned release`),
    ];
    return { tag, count: names.size, problems };
}

if (require.main === module) {
    const [command, zip] = process.argv.slice(2);
    if (command !== "check") {
        console.error("Use check [<android-drawables.zip>]");
        process.exitCode = 1;
    } else {
        check(process.cwd(), zip)
            .then(({ tag, count, problems }) => {
                if (problems.length) {
                    console.error(`Companion art that does not come from ${tag}:\n${problems.join("\n")}`);
                    process.exitCode = 1;
                    return;
                }
                console.log(`No companion picture is committed; ${tag} has the ${count} drawables the build unpacks`);
            })
            .catch((error) => {
                console.error(error.message);
                process.exitCode = 1;
            });
    }
}

module.exports = { check, committed, missing, pin, released, COMPANION };
