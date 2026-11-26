// SPDX-License-Identifier: AGPL-3.0-or-later
// Copies the pinned AnkiQuest-Art release's poses.json into the unit test resources, so the pose
// list is named by the Art release the repository pins (ankiquest-art.json), not by
// AnkiQuest-Server's companion_poses.json fixture. The asset's SHA-256 is checked against the pin
// before the file lands. ANKIQUEST_ART_DIR names a folder holding the release's assets to use
// instead of downloading them, for a release not published yet.
const fs = require("node:fs");
const path = require("node:path");
const { createHash } = require("node:crypto");
const { execFileSync } = require("node:child_process");

const RESOURCES = path.join("AnkiDroid", "src", "test", "resources", "ankiquest-art");
const ASSET = "poses.json";
const PIN = "ankiquest-art.json";
const RELEASES = "https://github.com/AnkiQuest/AnkiQuest-Art/releases/download";
const LOCAL_DIR = process.env.ANKIQUEST_ART_DIR;

// The pin: the release tag and its assets' SHA-256s.
const pin = JSON.parse(fs.readFileSync(PIN, "utf8"));
const expected = pin.assets[ASSET];
if (!/^[0-9a-f]{64}$/.test(expected || "")) {
    throw new Error(`${PIN} pins no valid SHA-256 for ${ASSET}`);
}
if (!pin.tag && !LOCAL_DIR) {
    throw new Error(`${PIN} names no AnkiQuest-Art release yet; set ANKIQUEST_ART_DIR`);
}

// The asset's bytes, from the pinned release or ANKIQUEST_ART_DIR, checked against the pin.
const bytes = LOCAL_DIR
    ? fs.readFileSync(path.join(LOCAL_DIR, ASSET))
    : execFileSync("curl", ["--silent", "--fail", "--location", `${RELEASES}/${pin.tag}/${ASSET}`]);
const sha256 = createHash("sha256").update(bytes).digest("hex");
if (sha256 !== expected.toLowerCase()) {
    throw new Error(`${ASSET}: SHA-256 ${sha256}, but ${PIN} pins ${expected}`);
}

// The file must name the pose groups, so a broken release fails here, not in a test run.
const data = JSON.parse(bytes.toString("utf8"));
for (const key of ["core", "extensions", "core_fallbacks", "pictures", "icons"]) {
    if (!data[key] || (Array.isArray(data[key]) ? data[key].length === 0 : Object.keys(data[key]).length === 0)) {
        throw new Error(`${ASSET}: no ${key}`);
    }
}

fs.mkdirSync(RESOURCES, { recursive: true });
fs.writeFileSync(path.join(RESOURCES, ASSET), bytes);
console.log(`${ASSET} of ${pin.tag}`);
