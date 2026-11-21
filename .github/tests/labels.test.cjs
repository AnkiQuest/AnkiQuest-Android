// SPDX-License-Identifier: AGPL-3.0-only
// .github/labels.json is the label set shared with AnkiQuest-Server and
// AnkiQuest-Desktop. It must hold every label this repository's workflows,
// labeler config and Dependabot apply, spelled the way they spell it. The
// copy check in ankiquest_labels.yml compares every shared labelling file
// with AnkiQuest-Server's and runs whenever one of them changes. The pull
// request status script keeps "Needs Review" on open, ready pull requests
// that no other member has approved.
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

const REPO = { owner: "AnkiQuest", repo: "test" };
const AUTHOR = "author";

// Runs one job's github-script for one event against a fake GitHub API and
// returns the label changes it makes. `pull` is the event that ran it and
// the pull request and reviews the GitHub API returns.
async function run(job, pull, association = "MEMBER") {
    const calls = [];
    const labels = (pull.labels ?? []).map(name => ({ name }));
    const head = { repo: { full_name: pull.fork ? "someone/test" : "AnkiQuest/test" } };
    const pr = {
        number: 1,
        state: pull.state ?? "open",
        draft: pull.draft ?? false,
        labels,
        head,
        user: { login: AUTHOR },
        author_association: association,
    };
    const reviews = (pull.reviews ?? []).map((review, index) => ({
        id: index,
        user: { login: review.login },
        state: review.state,
        author_association: review.association ?? "MEMBER",
    }));
    const issues = {
        addLabels: async args => {
            calls.push({ action: "add", labels: args.labels });
        },
        removeLabel: async args => {
            calls.push({ action: "remove", labels: [args.name] });
        },
    };
    const pulls = {
        get: async () => {
            assert.ok(!(pull.event === "pull_request_review" && pull.fork), "read-only run called the API");
            return { data: pr };
        },
        listReviews: async () => ({ data: reviews }),
    };
    const paginate = async method => (await method()).data;
    const context = {
        repo: REPO,
        eventName: pull.event ?? "pull_request_target",
        payload: { action: pull.action, pull_request: pr },
    };
    const core = { info: () => {}, notice: () => {} };
    const AsyncFunction = Object.getPrototypeOf(async () => {}).constructor;
    const script = jobScript("ankiquest_pr_labels.yml", job);
    await new AsyncFunction("github", "context", "core", script)({ rest: { issues, pulls }, paginate }, context, core);
    return calls;
}

const status = pull => run("status", pull);

// The `on:` types of one trigger in the pull request labels workflow.
function eventTypes(event) {
    const types = read("workflows/ankiquest_pr_labels.yml").match(new RegExp(`${event}:\\n\\s+types: \\[([^\\]]+)\\]`));
    assert.ok(types, `no ${event} types`);
    return types[1].split(/,\s*/);
}

// The settings of one job's concurrency block.
function jobConcurrency(job) {
    const text = read("workflows/ankiquest_pr_labels.yml");
    const block = text.slice(text.indexOf(`\n  ${job}:\n`)).match(/\n {4}concurrency:\n((?: {6}.*\n)+)/);
    assert.ok(block, `job ${job} has no concurrency`);
    const value = key => block[1].match(new RegExp(`^ {6}${key}: (.*)$`, "m"))?.[1];
    return { group: value("group"), cancel: value("cancel-in-progress") };
}

const add = { action: "add", labels: ["Needs Review"] };
const remove = { action: "remove", labels: ["Needs Review"] };
const approved = { login: "reviewer", state: "APPROVED" };

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

test("pull request labels run on every draft, ready, push and review change", () => {
    for (const type of ["opened", "reopened", "synchronize", "ready_for_review", "converted_to_draft", "closed"]) {
        assert.ok(eventTypes("pull_request_target").includes(type), type);
    }
    for (const type of ["submitted", "dismissed"]) {
        assert.ok(eventTypes("pull_request_review").includes(type), type);
    }
});

test("Needs Review follows the draft state", async () => {
    assert.deepEqual(await status({ action: "opened" }), [add]);
    assert.deepEqual(await status({ action: "opened", draft: true }), []);

    // A pull request opened as a draft gets the label once it is ready (#323).
    assert.deepEqual(await status({ action: "ready_for_review" }), [add]);
    assert.deepEqual(await status({ action: "ready_for_review", labels: ["needs review"] }), []);
    assert.deepEqual(await status({ action: "converted_to_draft", draft: true, labels: ["Needs Review"] }), [remove]);
    assert.deepEqual(await status({ action: "closed", state: "closed", labels: ["Needs Review", "Has Conflicts"] }), [
        remove,
        { action: "remove", labels: ["Has Conflicts"] },
    ]);
});

test("an approval removes Needs Review", async () => {
    const review = { event: "pull_request_review", action: "submitted", labels: ["Needs Review"] };
    assert.deepEqual(await status({ ...review, reviews: [approved] }), [remove]);
    assert.deepEqual(await status({ ...review, reviews: [{ login: "reviewer", state: "COMMENTED" }] }), []);

    // A comment after an approval keeps it.
    const comment = { login: "reviewer", state: "COMMENTED" };
    assert.deepEqual(await status({ ...review, reviews: [approved, comment] }), [remove]);

    // An approved pull request opened or reopened gets no label.
    assert.deepEqual(await status({ action: "reopened", reviews: [approved] }), []);
});

test("only an approval by another member counts", async () => {
    const review = { event: "pull_request_review", action: "submitted", labels: ["Needs Review"] };
    const self = { login: AUTHOR, state: "APPROVED" };
    const visitor = { login: "visitor", state: "APPROVED", association: "NONE" };
    const collaborator = { login: "reviewer", state: "APPROVED", association: "COLLABORATOR" };
    assert.deepEqual(await status({ ...review, reviews: [self] }), []);
    assert.deepEqual(await status({ ...review, reviews: [visitor] }), []);
    assert.deepEqual(await status({ ...review, reviews: [collaborator] }), [remove]);
});

test("a push keeps an approval that was not dismissed", async () => {
    assert.deepEqual(await status({ action: "synchronize", reviews: [approved] }), []);
    assert.deepEqual(await status({ action: "synchronize", labels: ["Needs Review"], reviews: [approved] }), [remove]);

    // Branch protection dismisses stale approvals by setting their state.
    const dismissed = { login: "reviewer", state: "DISMISSED" };
    assert.deepEqual(await status({ action: "synchronize", reviews: [dismissed] }), [add]);
    assert.deepEqual(await status({ event: "pull_request_review", action: "dismissed", reviews: [dismissed] }), [add]);
    assert.deepEqual(await status({ action: "synchronize" }), [add]);
});

test("changes requested after the last approval need review", async () => {
    const review = { event: "pull_request_review", action: "submitted" };
    const changes = { login: "other", state: "CHANGES_REQUESTED" };
    assert.deepEqual(await status({ ...review, reviews: [approved, changes] }), [add]);
    assert.deepEqual(await status({ ...review, labels: ["Needs Review"], reviews: [changes, approved] }), [remove]);

    // A reviewer who approves after asking for changes approves.
    const asked = { login: "reviewer", state: "CHANGES_REQUESTED" };
    assert.deepEqual(await status({ ...review, labels: ["Needs Review"], reviews: [asked, approved] }), [remove]);
});

test("draft and closed pull requests never keep Needs Review", async () => {
    assert.deepEqual(await status({ action: "synchronize", draft: true, labels: ["Needs Review"] }), [remove]);
    const closed = { event: "pull_request_review", action: "submitted", state: "closed", labels: ["Needs Review"] };
    assert.deepEqual(await status(closed), [remove]);
});

test("reviews of pull requests from forks are skipped", async () => {
    // Their token is read-only, so the script only logs.
    const review = { event: "pull_request_review", action: "submitted", fork: true, labels: ["Needs Review"] };
    assert.deepEqual(await status({ ...review, reviews: [approved] }), []);

    // pull_request_target runs for forks have a token that can label.
    const push = { action: "synchronize", fork: true, labels: ["Needs Review"], reviews: [approved] };
    assert.deepEqual(await status(push), [remove]);
});

test("New contributor goes on a newcomer's first pull request", async () => {
    const label = [{ action: "add", labels: ["New contributor"] }];
    assert.deepEqual(await run("newcomer", { action: "opened" }, "FIRST_TIME_CONTRIBUTOR"), label);
    assert.deepEqual(await run("newcomer", { action: "opened" }, "MEMBER"), []);
});

test("a newer event cancels an older status run of the same pull request", () => {
    for (const job of ["area", "status"]) {
        const { group, cancel } = jobConcurrency(job);
        assert.equal(cancel, "true", job);
        assert.match(group ?? "", /github\.event\.pull_request\.number/, job);
    }

    // Skipped fork review runs must not cancel a run that can label.
    assert.match(jobConcurrency("status").group ?? "", /pull_request_review/);
});
