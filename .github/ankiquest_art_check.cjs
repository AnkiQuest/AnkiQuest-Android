// SPDX-License-Identifier: AGPL-3.0-or-later
// Checks that every companion picture committed under AnkiDroid's drawables is, byte for byte, the
// exported copy the pinned AnkiQuest-Art release publishes in android-drawables.zip. A picture the
// release does not have, or one with other bytes, fails: the app shows only Art's exported copies.
// The zip is checked against the SHA-256 in ankiquest-art.json before it is read.
const crypto = require("node:crypto");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { execFileSync } = require("node:child_process");

const PIN = "ankiquest-art.json";
const ASSET = "android-drawables.zip";
const RELEASES = "https://github.com/AnkiQuest/AnkiQuest-Art/releases/download";
const DRAWABLES = "drawable-nodpi";
const APP_DRAWABLES = path.join("AnkiDroid/src/main/res", DRAWABLES);

// Companion pictures by name: one prefix per companion as the Art exporter names them (Bluma's poses
// are bluma_*, her launcher icons cow_*), and the duo picture.
const COMPANION = /^(aki|ankilope|anki_tan|bluma|cow|duo)_[a-z0-9_]+\.webp$/;

// The zip holds the drawables and the Anki-tan licence, nothing else.
const ZIP_ENTRY = /^(drawable-nodpi\/|drawable-nodpi\/[a-z0-9_]+\.webp|LICENSE-[A-Za-z-]+\.md)$/;

/** The pinned tag and the zip's SHA-256 from `root`'s ankiquest-art.json. */
function pin(root) {
    const json = JSON.parse(fs.readFileSync(path.join(root, PIN), "utf8"));
    if (!/^art-[0-9]+$/.test(json.tag || "")) {
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

/** Unpacks `zip` into a new folder after checking its hash and entry names; answers the drawables folder. */
function unpack(zip, sha) {
    const bytes = fs.readFileSync(zip);
    if (sha256(bytes) !== sha) {
        throw new Error(`${ASSET} does not have the pinned SHA-256`);
    }

    // Only flat, known entries: nothing outside the folder, no other files.
    const entries = execFileSync("unzip", ["-Z1", zip], { encoding: "utf8" }).split("\n").filter(Boolean);
    const unsafe = entries.filter((entry) => !ZIP_ENTRY.test(entry));
    if (unsafe.length) {
        throw new Error(`Unexpected entries in ${ASSET}: ${unsafe.join(", ")}`);
    }
    const target = fs.mkdtempSync(path.join(os.tmpdir(), "ankiquest-art-"));
    execFileSync("unzip", ["-q", zip, "-d", target]);
    return path.join(target, DRAWABLES);
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

/**
 * The problems of `root`'s committed companion pictures against the exported copies in `released`:
 * one line per picture the release lacks or whose bytes differ.
 */
function compare(root, released) {
    const problems = [];
    const folder = path.join(root, APP_DRAWABLES);
    for (const name of fs.readdirSync(folder).sort()) {
        if (!COMPANION.test(name)) {
            continue;
        }
        const exported = path.join(released, name);
        if (!fs.existsSync(exported)) {
            problems.push(`${name}: not in the pinned release`);
            continue;
        }
        if (!fs.readFileSync(exported).equals(fs.readFileSync(path.join(folder, name)))) {
            problems.push(`${name}: differs from the pinned release`);
        }
    }
    return problems;
}

/** Checks `root` against `zip`, or against the pinned release downloaded when no zip is given. */
async function check(root, zip) {
    const { tag, sha } = pin(root);
    const released = unpack(zip || (await download(tag)), sha);
    try {
        return { tag, problems: compare(root, released) };
    } finally {
        fs.rmSync(path.dirname(released), { recursive: true, force: true });
    }
}

if (require.main === module) {
    const [command, zip] = process.argv.slice(2);
    if (command !== "check") {
        console.error("Use check [<android-drawables.zip>]");
        process.exitCode = 1;
    } else {
        check(process.cwd(), zip)
            .then(({ tag, problems }) => {
                if (problems.length) {
                    console.error(`Companion pictures that are not ${tag}'s exported copies:\n${problems.join("\n")}`);
                    process.exitCode = 1;
                    return;
                }
                console.log(`Every companion picture is ${tag}'s exported copy`);
            })
            .catch((error) => {
                console.error(error.message);
                process.exitCode = 1;
            });
    }
}

module.exports = { check, compare, pin, unpack, COMPANION };
