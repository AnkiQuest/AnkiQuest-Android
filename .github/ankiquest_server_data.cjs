// SPDX-License-Identifier: AGPL-3.0-or-later
// Copies AnkiQuest-Server's API examples and shared fixtures into the unit test resources, from a
// shallow, sparse clone of a server branch (master unless --ref names another) or from a server
// checkout. Nothing is committed or pinned: the tests run against server master. Server files are
// read as JSON data only; no server code is executed.
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const crypto = require("node:crypto");
const { execFileSync } = require("node:child_process");

const RESOURCES = "AnkiDroid/src/test/resources";
const SERVER = "https://github.com/AnkiQuest/AnkiQuest-Server.git";
const BRANCH = "master";
// Server folders and the resource folders they are copied to.
const PAIRS = [
    ["docs/api/examples", "ankiquest-api"],
    ["tests/fixtures", "ankiquest"],
];
// Server files the tests read elsewhere: the pose list comes from the pinned AnkiQuest-Art
// release (AnkiQuest/AnkiQuest-Art#10), copied there by [pinnedPoses].
const SKIP = { ankiquest: ["companion_poses.json"] };
// The AnkiQuest-Art release this repository pins, and its pose list.
const PIN = "ankiquest-art.json";
const ART_RELEASES = "https://github.com/AnkiQuest/AnkiQuest-Art/releases/download";
const ART_POSES = "poses.json";
// Art's own folder in the test resources, so the pinned release's file never mixes with the
// server data that `copy` replaces on every fetch.
const ART_DIR = "ankiquest-art";
// Every unit-test, contract, screenshot and banner run downloads the pose list; one retry
// survives a transient 5xx or a dropped connection, while a 4xx (a wrong pin) fails at once.
const DOWNLOAD_ATTEMPTS = 2;
// Android's own example catalog, kept next to the copied examples.
const CATALOG = "names.json";
const CONTENT = Object.freeze({ SERVER: "server", CATALOG: "catalog" });

// A branch, tag or commit; anything else could be read as a git option.
function serverRef(ref = BRANCH) {
    if (!/^[A-Za-z0-9][A-Za-z0-9._/-]*$/.test(ref)) {
        throw new Error("Invalid server ref");
    }
    return ref;
}

function contentFor(target) {
    return target === "ankiquest-api" ? CONTENT.CATALOG : CONTENT.SERVER;
}

// The JSON files directly in `directory`, parsed once to fail early on broken data.
function jsonFiles(directory, skip = []) {
    const files = new Map();
    for (const name of fs.readdirSync(directory).sort()) {
        if (!name.endsWith(".json")) {
            continue;
        }
        if (name === CATALOG) {
            throw new Error(`Server data must not replace Android's ${CATALOG}`);
        }
        if (skip.includes(name)) {
            continue;
        }
        const file = path.join(directory, name);
        const stat = fs.lstatSync(file);
        if (!stat.isFile() || stat.isSymbolicLink()) {
            throw new Error(`Expected a regular JSON file: ${file}`);
        }
        const bytes = fs.readFileSync(file);
        JSON.parse(bytes.toString("utf8"));
        files.set(name, bytes);
    }
    if (!files.size) {
        throw new Error(`No JSON data in ${directory}`);
    }
    return files;
}

// Replaces the JSON files of `directory`, keeping the catalog in the examples folder.
function replaceJson(directory, files, content) {
    fs.mkdirSync(directory, { recursive: true });
    for (const name of fs.readdirSync(directory)) {
        if (fs.lstatSync(path.join(directory, name)).isSymbolicLink()) {
            throw new Error(`Refusing a resource symlink: ${name}`);
        }
        const keep = content === CONTENT.CATALOG && name === CATALOG;
        if (name.endsWith(".json") && !keep && !files.has(name)) {
            fs.unlinkSync(path.join(directory, name));
        }
    }
    for (const [name, bytes] of files) {
        fs.writeFileSync(path.join(directory, name), bytes);
    }
}

function writableResources(root) {
    let current = path.resolve(root);
    for (const part of RESOURCES.split("/")) {
        current = path.join(current, part);
        if (fs.lstatSync(current, { throwIfNoEntry: false })?.isSymbolicLink()) {
            throw new Error("Resource directories cannot be symlinks");
        }
    }
    for (const [, target] of PAIRS) {
        if (fs.lstatSync(path.join(current, target), { throwIfNoEntry: false })?.isSymbolicLink()) {
            throw new Error("Server data folders cannot be symlinks");
        }
    }
}

// Copies a server checkout's data into the resources of the Android checkout at `root`.
function copy(root, server) {
    // Read and validate all data before changing either folder.
    const data = PAIRS.map(([source, target]) => [target, jsonFiles(path.join(server, source), SKIP[target] ?? [])]);
    writableResources(root);
    for (const [target, files] of data) {
        replaceJson(path.join(root, RESOURCES, target), files, contentFor(target));
    }
}

// Downloads `url`'s bytes, retrying a transient failure once; overridable for tests.
async function downloadBytes(url, attempts = DOWNLOAD_ATTEMPTS) {
    let lastError;
    for (let attempt = 1; attempt <= attempts; attempt++) {
        let response;
        try {
            response = await globalThis.fetch(url);
        } catch (error) {
            lastError = error;
            continue;
        }
        if (response.ok) {
            return Buffer.from(await response.arrayBuffer());
        }
        lastError = new Error(`download failed: HTTP ${response.status} ${url}`);
        if (Math.floor(response.status / 100) !== 5) {
            throw lastError;
        }
    }
    throw lastError;
}

// Copies the pinned AnkiQuest-Art release's poses.json into the test resources' Art folder: the
// pose list the tests pin the pose enum to, replacing the server fixture they used to read. The
// bytes are checked against the SHA-256 `ankiquest-art.json` pins, as the other Art assets are.
// Returns the release tag.
async function pinnedPoses(root, download = downloadBytes) {
    const pin = JSON.parse(fs.readFileSync(path.join(root, PIN), "utf8"));
    const tag = pin.tag;
    if (typeof tag !== "string" || !tag) {
        throw new Error(`${PIN} names no AnkiQuest-Art release`);
    }
    const expected = pin.assets?.[ART_POSES];
    if (typeof expected !== "string" || !/^[0-9a-f]{64}$/.test(expected)) {
        throw new Error(`${PIN} pins no SHA-256 for ${ART_POSES}`);
    }
    const bytes = Buffer.from(await download(`${ART_RELEASES}/${tag}/${ART_POSES}`));
    const actual = crypto.createHash("sha256").update(bytes).digest("hex");
    if (actual !== expected) {
        throw new Error(`${ART_POSES}: SHA-256 ${actual}, but ${PIN} pins ${expected}`);
    }
    JSON.parse(bytes.toString("utf8"));
    const target = path.join(root, RESOURCES, ART_DIR, ART_POSES);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, bytes);
    return tag;
}

function git(args) {
    return execFileSync("git", args, { encoding: "utf8", stdio: ["ignore", "pipe", "inherit"] }).trim();
}

// Clones `ref` shallow and sparse, copies its data and removes the clone.
function fetch(root, ref = BRANCH) {
    const clone = fs.mkdtempSync(path.join(os.tmpdir(), "ankiquest-server-"));
    try {
        git(["clone", "--quiet", "--depth", "1", "--filter=blob:none", "--no-checkout", SERVER, clone]);
        git(["-C", clone, "fetch", "--quiet", "--depth", "1", "--filter=blob:none", "origin", serverRef(ref)]);
        git(["-C", clone, "sparse-checkout", "set", ...PAIRS.map(([source]) => source)]);
        git(["-C", clone, "checkout", "--quiet", "FETCH_HEAD"]);
        copy(root, clone);
        return git(["-C", clone, "rev-parse", "HEAD"]);
    } finally {
        fs.rmSync(clone, { recursive: true, force: true });
    }
}

// Reads `[--ref <ref>] [<server checkout>]`.
function parse(args) {
    const options = { ref: BRANCH, server: undefined };
    for (let index = 0; index < args.length; index++) {
        if (args[index] === "--ref") {
            options.ref = serverRef(args[++index] || BRANCH);
        } else if (options.server === undefined) {
            options.server = args[index];
        } else {
            throw new Error("Use fetch [--ref <ref>] [<server checkout>]");
        }
    }
    return options;
}

if (require.main === module) {
    (async () => {
        try {
            const [command, ...rest] = process.argv.slice(2);
            if (command !== "fetch") {
                throw new Error("Use fetch [--ref <ref>] [<server checkout>]");
            }
            const options = parse(rest);
            const root = process.cwd();
            if (options.server !== undefined) {
                copy(root, options.server);
                console.log(`Copied server data from ${options.server}`);
            } else {
                console.log(`AnkiQuest-Server ${options.ref} at ${fetch(root, options.ref)}`);
            }
            console.log(`AnkiQuest-Art ${await pinnedPoses(root)}`);
        } catch (error) {
            console.error(error.message);
            process.exitCode = 1;
        }
    })();
}

module.exports = { copy, fetch, parse, pinnedPoses, serverRef };
