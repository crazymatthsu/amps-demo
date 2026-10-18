# Restrict pull requests into main to release and hotfix branches

## Goal and behavior

Allow only source branches starting with `release/` or `hotfix/` to merge into `main`.

GitHub can enforce this at merge time using a required status check and a branch ruleset. Users can still open pull requests from other branches; those pull requests fail the check and cannot merge. GitHub does not provide a built-in PR source-branch allowlist in ordinary branch protection rules.

| Pull request | Outcome |
| --- | --- |
| `release/1.5.0` → `main` | Allowed, subject to other required checks and reviews |
| `hotfix/fix-login` → `main` | Allowed, subject to other required checks and reviews |
| `feature/new-api` → `main` | Blocked |
| `develop` → `main` | Blocked |
| Any branch → another target branch | Unaffected by this workflow |

This document is an implementation guide. Adding it does not install the workflow or enable repository rules.

## 1. Add a source-branch check

Create `.github/workflows/check-main-source.yml` at the **repository root**, not under `amps-ha-demo/`, and merge it into the default branch before configuring it as required.

```yaml
name: Main source branch policy

on:
  pull_request_target:
    branches: [main]
    types: [opened, synchronize, reopened, edited]

permissions: {}

jobs:
  check-source:
    name: Allowed source branch
    runs-on: ubuntu-latest
    steps:
      - name: Validate source branch
        env:
          SOURCE_BRANCH: ${{ github.head_ref }}
        shell: bash
        run: |
          case "$SOURCE_BRANCH" in
            release/?*|hotfix/?*)
              echo "Allowed source branch: $SOURCE_BRANCH"
              ;;
            *)
              echo "::error::Only release/* or hotfix/* may merge into main."
              exit 1
              ;;
          esac
```

- `branches: [main]` filters the **destination** branch.
- `github.head_ref` contains the **source** branch name.
- `edited` covers changes such as retargeting an existing PR to `main`.
- The Bash patterns require at least one character after the prefix and also accept nested names such as `release/team/1.5.0`. These are Bash patterns, not GitHub ruleset glob patterns.
- Branch names are passed through an environment variable rather than interpolated directly into shell code.
- Invalid branches must produce a failed check. Do not replace this with a job-level condition that skips invalid branches: skipped jobs can satisfy required checks.

The `pull_request_target` event uses the trusted base repository workflow rather than the PR's proposed workflow. Keep this workflow limited to PR metadata validation: do not check out or execute PR code, and do not add secrets or write permissions.

## 2. Make the check required on main

In **Settings → Rules → Rulesets**, create a branch ruleset:

1. Set enforcement to **Active**.
2. Target the `main` branch.
3. Enable **Require a pull request before merging**.
4. Enable **Require status checks to pass**, and select **Allowed source branch**. Trigger the workflow with a PR first if the check is not listed.
5. Keep the bypass list empty if everyone must follow the policy.
6. Enable **Block force pushes**.

Existing CI and review requirements continue to apply. A passing branch-name check does not replace those requirements.

Adding the workflow alone does not prevent merges: it must be required by the ruleset. The pull-request requirement also prevents ordinary direct pushes from avoiding the check. Users or apps granted bypass permissions can bypass the corresponding rules.

## 3. Verify enforcement

After installing the workflow and activating the ruleset:

1. Open a PR from `release/test-policy` to `main`; confirm the check passes.
2. Open a PR from `hotfix/test-policy` to `main`; confirm the check passes.
3. Open a PR from `feature/test-policy` to `main`; confirm the check fails and merging is blocked.
4. Retarget a PR from another destination to `main`; confirm the check runs.
5. Close the test PRs without merging.

## Optional: require the source branch to belong to this repository

The basic check validates the branch name only. A fork can also have a branch named `release/1.5.0`.

To reject forks, add these variables to the validation step's `env`:

```yaml
SOURCE_REPOSITORY: ${{ github.event.pull_request.head.repo.full_name }}
TARGET_REPOSITORY: ${{ github.repository }}
```

Then insert this check before the `case` statement:

```bash
if [[ "$SOURCE_REPOSITORY" != "$TARGET_REPOSITORY" ]]; then
  echo "::error::The source branch must belong to this repository."
  exit 1
fi
```

This is a naming policy, not a restriction on who may create release or hotfix branches. Use separate rules targeting those branches if their creation or updates also need to be controlled.

## Merge queue consideration

The example above handles PR events only. If the repository uses a merge queue, account for required checks on `merge_group` events before adopting it. Merge-group refs are synthetic refs, not the original release or hotfix branch names; do not apply this source-name check directly to the queue ref. Validate PR eligibility before queue entry and provide an appropriate queue-compatible check strategy.

## References

- [Available rules for rulesets](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/available-rules-for-rulesets)
- [About protected branches](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches)
- [Events that trigger workflows](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows)
- [Securely using pull_request_target](https://docs.github.com/en/actions/reference/security/securely-using-pull_request_target)
