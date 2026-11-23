// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from wait_for_screenshot_baseline.js for the fork's workflow and manual recordings.

const WORKFLOW = "ankiquest_screenshot_store.yml";
const ATTEMPTS = 60;
const RETRY_MS = 30_000;

// The commit whose baseline a run compares against: a pull request's base, the master commit a
// merge queue group was built on, or the pushed commit itself.
async function baseCommit({ github, context }) {
    if (context.payload.pull_request) {
        return context.payload.pull_request.base;
    }

    // A group's parent can be the group queued ahead of it, which never gets a baseline.
    // Its merge base with the target branch is the branch commit the queue started from.
    const group = context.payload.merge_group;
    if (group) {
        const ref = group.base_ref.replace(/^refs\/heads\//, "");
        const {
            data: { merge_base_commit: mergeBase },
        } = await github.rest.repos.compareCommitsWithBasehead({
            ...context.repo,
            basehead: `${ref}...${group.head_sha}`,
            per_page: 1,
        });
        return { sha: mergeBase.sha, ref };
    }

    return { sha: context.sha, ref: context.ref.replace(/^refs\/heads\//, "") };
}

module.exports = async function waitForScreenshotBaseline({
    github,
    context,
    core,
    sleep = delay => new Promise(resolve => setTimeout(resolve, delay)),
}) {
    const base = await baseCommit({ github, context });

    // The workflow names this commit when no baseline can be downloaded.
    core.setOutput("base", base.sha);

    // Store jobs can overlap or outlive the branch tip. Only this commit's artifact is safe.
    for (let attempt = 0; attempt < ATTEMPTS; attempt++) {
        const {
            data: { workflow_runs: runs },
        } = await github.rest.actions.listWorkflowRuns({
            ...context.repo,
            workflow_id: WORKFLOW,
            // No status filter: GitHub's status=success can omit a finished run.
            // The checks below pick a completed, successful run themselves.
            head_sha: base.sha,
            per_page: 100,
        });
        for (const run of runs) {
            if (
                run.head_sha !== base.sha ||
                run.status !== "completed" ||
                run.conclusion !== "success" ||
                !["push", "workflow_dispatch"].includes(run.event)
            ) {
                continue;
            }
            const {
                data: { artifacts },
            } = await github.rest.actions.listWorkflowRunArtifacts({
                ...context.repo,
                run_id: run.id,
                per_page: 100,
            });
            if (artifacts.some(artifact => artifact.name === "screenshot" && !artifact.expired)) {
                core.info(`Using screenshot baseline from ${base.sha}: run ${run.id}`);
                return run.id;
            }
        }
        core.info(`Waiting for screenshot baseline ${base.sha} (${base.ref})`);
        await sleep(RETRY_MS);
    }
    throw new Error(
        `No successful screenshot baseline for ${base.sha} after 30 minutes. ` +
            `Run ${WORKFLOW} on a branch at that commit, then rerun this check.`,
    );
};
