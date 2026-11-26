// SPDX-License-Identifier: AGPL-3.0-or-later
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const workflows = path.join(__dirname, "../workflows");
const action = fs.readFileSync(path.join(__dirname, "../actions/gradle-cache/action.yml"), "utf8");

// Workflows of ours that build with Gradle and keep a dependency cache.
const CACHED = [
    "ankiquest_api_contract.yml",
    "lint.yml",
    "screenshot_compare.yml",
    "screenshot_store.yml",
    "tests_emulator.yml",
    "tests_unit.yml",
];

const read = name => fs.readFileSync(path.join(workflows, name), "utf8");

// Returns the `with:` values of every step that uses the gradle-cache action.
function cacheSteps(text) {
    const steps = [];
    const pattern = /uses: \.\/\.github\/actions\/gradle-cache\n((?: {8,}.*\n)*)/g;
    for (const match of text.matchAll(pattern)) {
        const value = key => match[1].match(new RegExp(`^ +${key}: (\\S+)`, "m"))?.[1];
        steps.push({ mode: value("mode"), group: value("group") });
    }
    return steps;
}

// setup-gradle's basic cache shares one entry between all jobs, which the first job to
// save fills; the others then download what it lacks on every run (HTTP 429).
test("cached workflows turn setup-gradle's own cache off", () => {
    for (const name of CACHED) {
        const setups = read(name).match(/uses: gradle\/actions\/setup-gradle@.*\n(?: {8,}.*\n)*/g) ?? [];
        assert.ok(setups.length > 0, `${name} has no setup-gradle step`);
        for (const setup of setups) {
            assert.match(setup, /cache-disabled: true/, name);
        }
    }
});

test("every cached workflow restores its group", () => {
    for (const name of CACHED) {
        const restores = cacheSteps(read(name)).filter(step => step.mode === "restore");
        assert.equal(restores.length, 1, name);
    }
});

// Two writers would race for the same key, and whichever saved first would decide the
// entry again. Every group read must also have a writer.
test("each cache group has exactly one writing job", () => {
    const writers = new Map();
    const readers = new Set();
    for (const name of CACHED) {
        for (const step of cacheSteps(read(name))) {
            if (step.mode === "save") {
                assert.ok(!writers.has(step.group), `${step.group} saved in ${writers.get(step.group)} and ${name}`);
                writers.set(step.group, name);
            } else {
                readers.add(step.group);
            }
        }
    }
    assert.deepEqual([...readers].filter(group => !writers.has(group)), []);
});

// Cache poisoning: nothing a pull request runs may reach a cache that master restores.
test("only master pushes save the cache", () => {
    const save = action.match(/- name: Save the Gradle cache\n((?: {6,}.*\n)*)/)[1];
    assert.match(save, /github\.event_name == 'push' && github\.ref == 'refs\/heads\/master'/);
    assert.match(save, /uses: actions\/cache\/save@/);
    assert.doesNotMatch(action, /uses: actions\/cache@/);
});

// The paths are part of the cache version: a mismatch saves entries nothing restores.
test("restore and save use the same paths", () => {
    const paths = [...action.matchAll(/path: \|\n((?: {10}.*\n)*)/g)].map(match => match[1]);
    assert.equal(paths.length, 2);
    assert.equal(paths[0], paths[1]);
});
