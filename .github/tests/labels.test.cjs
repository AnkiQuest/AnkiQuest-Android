// SPDX-License-Identifier: AGPL-3.0-only
// .github/labels.json is the label set shared with AnkiQuest-Server and
// AnkiQuest-Desktop. It must hold every label this repository's workflows,
// labeler config and Dependabot apply, spelled the way they spell it. The
// copy check in ankiquest_labels.yml compares every shared labelling file
// with AnkiQuest-Server's and runs whenever one of them changes. The pull
// request status script adds and removes "Needs Review" as a pull request
// moves between draft and ready.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const root = path.resolve(__dirname, "..", "..");
const github = path.join(root, ".github");
const read = (file) => fs.readFileSync(path.join(github, file), "utf8");
const labels = JSON.parse(read("labels.json"));

// GitHub's limits for a label.
const COLOR = /^[0-9a-f]{6}$/;
const MAX_DESCRIPTION = 100;

// The label sync workflow, which holds the copy check and its file list.
const SYNC = "workflows/ankiquest_labels.yml";

// A workflow that adds, removes or creates labels.
const LABELLING = /addLabels|removeLabel|actions\/labeler@|\$GITHUB_REPOSITORY\/labels/;

// Upstream AnkiDroid's labelling workflows, replaced by the shared ones:
// label.yml by ankiquest_pr_labels.yml, conflict.yaml by ankiquest_conflicts.yml.
const REPLACED = ["label.yml", "conflict.yaml"];

// Files the copy check compares, from SHARED_FILES in the sync workflow.
function sharedFiles() {
    const block = read(SYNC).match(/^ {2}SHARED_FILES: >-\n((?: {4}\S.*\n)+)/m);
    assert.ok(block, `${SYNC} has no SHARED_FILES list`);
    return block[1].trim().split(/\s+/);
}

// Paths that trigger the sync workflow for one event.
function triggerPaths(event) {
    const block = read(SYNC).match(new RegExp(`^ {2}${event}:\\n(?: {4}.*\\n)*? {4}paths:\\n((?: {6}- .*\\n)+)`, "m"));
    assert.ok(block, `${SYNC} has no ${event} paths`);
    return [...block[1].matchAll(/- (\S+)/g)].map((match) => match[1]);
}

// Workflows in this repository that apply labels.
function labellingWorkflows() {
    return fs
        .readdirSync(path.join(github, "workflows"))
        .filter((file) => /\.ya?ml$/.test(file) && LABELLING.test(read(path.join("workflows", file))))
        .map((file) => `.github/workflows/${file}`);
}

// Label names in the shared workflow scripts: LABEL constants and add/remove calls.
function workflowNames() {
    const names = [];
    for (const file of sharedFiles().filter((file) => file.startsWith(".github/workflows/"))) {
        for (const match of fs.readFileSync(path.join(root, file), "utf8").matchAll(/(?:LABEL = |add\(|remove\()'([^']+)'/g)) {
            names.push(match[1]);
        }
    }
    return names;
}

// Area labels are the top-level keys of the labeler config.
function labelerNames() {
    return [...read("labeler.yml").matchAll(/^([A-Za-z_][^:\n]*):$/gm)].map((match) => match[1]);
}

// Labels Dependabot adds, from the `labels:` lists in dependabot.yml.
function dependabotNames() {
    const names = new Set();
    for (const [, list] of read("dependabot.yml").matchAll(/^\s*labels:\n((?:\s*- .*\n)+)/gm)) {
        for (const [, name] of list.matchAll(/- "?([^"\n]+)"?/g)) {
            names.add(name.trim());
        }
    }
    return [...names];
}

// The github-script body of one job in a workflow, dedented.
function jobScript(file, job) {
    const text = read(path.join("workflows", file));
    const start = text.indexOf(`\n  ${job}:\n`);
    assert.ok(start >= 0, `${file} has no job ${job}`);
    const block = text.slice(start).match(/\n( +)script: \|\n((?:\1  .*\n|\s*\n)+)/);
    assert.ok(block, `${file} job ${job} has no script`);
    const indent = block[1].length + 2;
    return block[2]
        .split("\n")
        .map((line) => line.slice(indent))
        .join("\n");
}

// Runs the pull request status script for one event and returns the label
// changes it makes. The script reads the pull request and its reviews from
// the API, so the fakes below answer those calls.
async function status(action, draft, names = [], reviews = []) {
    const calls = [];
    const issues = {
        addLabels: async (args) => {
            calls.push({ action: "add", labels: args.labels });
        },
        removeLabel: async (args) => {
            calls.push({ action: "remove", labels: [args.name] });
        },
    };
    const pr = {
        number: 1,
        state: action === "closed" ? "closed" : "open",
        draft,
        user: { login: "author" },
        labels: names.map((name) => ({ name })),
        head: { repo: { full_name: "AnkiQuest/test" } },
    };
    const pulls = {
        get: async () => ({ data: pr }),
        listReviews: async () => ({ data: reviews }),
    };
    const github = {
        rest: { issues, pulls },
        paginate: async (method, args) => (await method(args)).data,
    };
    const context = {
        eventName: "pull_request_target",
        repo: { owner: "AnkiQuest", repo: "test" },
        payload: { action, pull_request: pr },
    };
    const core = { info: () => {}, notice: () => {} };
    const AsyncFunction = Object.getPrototypeOf(async () => {}).constructor;
    await new AsyncFunction("github", "context", "core", jobScript("ankiquest_pr_labels.yml", "status"))(github, context, core);
    return calls;
}

test("labels.json entries are well formed and unique ignoring case", () => {
    const seen = new Set();
    for (const label of labels) {
        assert.deepEqual(Object.keys(label).sort(), ["aliases", "color", "description", "name"], label.name);
        assert.match(label.color, COLOR, label.name);
        assert.ok(label.description.length <= MAX_DESCRIPTION, label.name);
        for (const name of [label.name, ...label.aliases]) {
            assert.ok(!seen.has(name.toLowerCase()), `${name} appears twice`);
            seen.add(name.toLowerCase());
        }
    }
});

test("upstream's labelling workflows are replaced by the shared ones", () => {
    for (const file of REPLACED) {
        assert.ok(!fs.existsSync(path.join(github, "workflows", file)), file);
    }
});

test("labels.json holds every label the workflows apply, spelled the same", () => {
    const names = new Set(labels.map((label) => label.name));
    const used = [...workflowNames(), ...labelerNames(), ...dependabotNames()];
    assert.ok(workflowNames().length > 0);
    assert.ok(dependabotNames().length > 0);
    for (const name of used) {
        assert.ok(names.has(name), `${name} is missing from .github/labels.json`);
    }
});

test("the copy check compares labels.json and every labelling workflow", () => {
    const expected = [".github/labels.json", ...labellingWorkflows()].sort();
    assert.deepEqual([...sharedFiles()].sort(), expected);
    for (const file of sharedFiles()) {
        assert.ok(fs.existsSync(path.join(root, file)), `${file} does not exist`);
    }
});

test("the copy check runs when any shared file changes", () => {
    const shared = [...sharedFiles()].sort();
    for (const event of ["push", "pull_request"]) {
        assert.deepEqual([...triggerPaths(event)].sort(), shared, event);
    }
});

test("pull request labels run on every draft and ready change", () => {
    const types = read("workflows/ankiquest_pr_labels.yml").match(/pull_request_target:\n\s+types: \[([^\]]+)\]/);
    assert.ok(types);
    for (const type of ["opened", "reopened", "ready_for_review", "converted_to_draft", "closed"]) {
        assert.ok(types[1].split(/,\s*/).includes(type), type);
    }
});

test("Needs Review follows the draft state", async () => {
    const review = (action) => ({ action, labels: ["Needs Review"] });
    assert.deepEqual(await status("opened", false), [review("add")]);
    assert.deepEqual(await status("opened", true), []);

    // A pull request opened as a draft gets the label once it is ready (#323).
    assert.deepEqual(await status("ready_for_review", false), [review("add")]);
    assert.deepEqual(await status("ready_for_review", false, ["needs review"]), []);
    assert.deepEqual(await status("converted_to_draft", true, ["Needs Review"]), [review("remove")]);
    assert.deepEqual(await status("closed", false, ["Needs Review", "Has Conflicts"]), [
        review("remove"),
        { action: "remove", labels: ["Has Conflicts"] },
    ]);
});

test("Needs Review goes once a member approves", async () => {
    const review = (login, state, association = "MEMBER") => ({ id: 1, user: { login }, state, author_association: association });
    const remove = [{ action: "remove", labels: ["Needs Review"] }];
    assert.deepEqual(await status("synchronize", false, ["Needs Review"], [review("member", "APPROVED")]), remove);
    // The author's own review and approvals from outside the org don't count.
    assert.deepEqual(await status("synchronize", false, [], [review("author", "APPROVED")]), [{ action: "add", labels: ["Needs Review"] }]);
    assert.deepEqual(await status("synchronize", false, [], [review("guest", "APPROVED", "NONE")]), [{ action: "add", labels: ["Needs Review"] }]);
    // A later change request outweighs an earlier approval.
    assert.deepEqual(
        await status("synchronize", false, [], [review("member", "APPROVED"), review("other", "CHANGES_REQUESTED")]),
        [{ action: "add", labels: ["Needs Review"] }],
    );
});
