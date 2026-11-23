// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-License-Identifier: GPL-3.0-or-later
// Accepts manual recordings (workflow_dispatch) and checks that the artifact still exists.

const WORKFLOW = "screenshot_store.yml";
const PREVIOUS_WORKFLOW = "ankiquest_screenshot_store.yml";
const ATTEMPTS = 60;
const RETRY_MS = 30_000;

// The baseline belongs to the PR base, the master commit behind a queue group, or the push itself.
async function baseCommit({ github, context }) {
    if (context.payload.pull_request) {
        return context.payload.pull_request.base;
    }

    // A group's parent can be an earlier queue group with no recorded screenshots.
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

async function runsAt(github, repo, workflow, sha) {
    try {
        // No status filter: status=success can omit a finished run.
        const {
            data: { workflow_runs: runs },
        } = await github.rest.actions.listWorkflowRuns({
            ...repo,
            workflow_id: workflow,
            head_sha: sha,
            per_page: 100,
        });
        return runs;
    } catch (error) {
        // Once the old file is removed, the API may no longer accept its name.
        if (workflow === PREVIOUS_WORKFLOW && error.status === 404) {
            return [];
        }
        throw error;
    }
}

module.exports = async function waitForScreenshotBaseline({
    github,
    context,
    core,
    sleep = delay => new Promise(resolve => setTimeout(resolve, delay)),
}) {
    const base = await baseCommit({ github, context });
    core.setOutput("base", base.sha);

    // Store jobs can overlap or outlive the branch tip. Only this commit's artifact is safe.
    for (let attempt = 0; attempt < ATTEMPTS; attempt++) {
        // Earlier base commits were recorded by the workflow's former file name.
        for (const workflow of [WORKFLOW, PREVIOUS_WORKFLOW]) {
            for (const run of await runsAt(github, context.repo, workflow, base.sha)) {
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
                if (
                    artifacts.some(artifact => artifact.name === "screenshot" && !artifact.expired)
                ) {
                    core.info(`Using screenshot baseline from ${base.sha}: run ${run.id}`);
                    return run.id;
                }
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
