# Verification of risky changes

Read this when you verify a PR, when you implement a risky change (the guard-check rule applies to you too), and when the planner prepares a verification run. Owner decision, 2026-11-11: before the planner merges a PR with a risky change, a separate verification agent reviews it.

## Risky changes

- automation logic, screen reading, taps, coordinates or templates (`scripts/`, `libautomata/`, `app/` runner and imaging);
- capture, logging and stored-data formats (including migrations of saved files);
- file storage and export, certificates and security;
- services, permissions and the manifest;
- build, signing and versioning;
- upstream syncs;
- anything under `native/` (the Rust core) and its Kotlin bindings.

**Not risky:** UI layout, strings, the manual and test-only changes. The planner reviews these alone.

## Who verifies

- A separate agent run with a fresh context, preferably a different model from the implementer. It may be the same model as the implementer, but never the same session. Which model verifies whose work is the planner's choice.
- If no agent is available, the planner does a full line-by-line review and says so in the PR record.
- **Two verifiers for the highest-risk changes:** changes to automation logic (battle flow, screen reading, taps, coordinates, templates) and to stored-data formats or their migrations get two independent verification runs on two different models, chosen so that at least one differs from the implementer. Both must pass. In `native/` this also covers `fga-ffi` and moving a unit to its next lifecycle stage (see [`rust-native.md`](rust-native.md)).

## What the verifier does

- Compares the diff with every requirement in the issue.
- Checks that the tests cover the requirements and the failure and guard cases.
- Looks for missed edge cases and effects outside the stated scope.
- Re-runs the full unit tests and re-records the screenshots itself instead of trusting the implementer's report.
- Takes evidence from the code, not from comments (owner decision, 2026-11-17, #458). It never accepts a behaviour because a comment claims it. It traces the code, and reports a comment that the code contradicts.
- Checks that the fakes and mocks in the tests return only values, states and exceptions that the real implementation can return in that situation (#455 slipped through a fake that did not).
- Checks that each new guard or regression test fails without its guard. It briefly disables or reverts the protected line, runs only that test, confirms that it fails, and restores the line. The report names the tests checked this way. A test that still passes protects nothing and is a finding. **The implementer does the same check before it reports.**
- For capture and log work, checks memory: what stays resident, what is loaded per response or per screen, and whether anything grows without bound (the log feature once ran out of memory by loading everything). It reports this even when it passes.

## Merges between `main` and `rust`

Owner decision, 2026-11-19: both directions are verified, because the two branches are developed apart and a merge is where they meet.

**`main` into `rust` (a sync).** One verifier for every sync, also when git merged everything without a conflict. It checks:

- the PR is one merge commit of `main`'s tip on top of `rust`;
- the result differs from an automatic merge (redo it with `git merge-tree` or a throwaway worktree) only in the files where a conflict was resolved by hand, and each of those is the smallest change that keeps both sides' intent;
- nothing that lives only on `rust` was lost: `native/`, the Kotlin seams and bindings of design 0025, and the Rust-side records are what they were before the merge, except where `main` changed the same file;
- everything from `main` arrived: the full difference from `main` is the Rust work and nothing else;
- when `main` changed a file that the Rust work also changed (a reader behind a seam, a workflow, a Gradle file), the combined file still does what both sides meant, traced in the code;
- the full unit tests and the Rust checks, re-run by the verifier.
- The planner also replays the device QA suites of an area when both sides changed it.

**`rust` into `main` (a finished unit).** This is the highest-risk kind of change: two verifiers on different models, and both must pass. On top of everything above in this guide they check:

- the PR holds that unit and nothing else from `rust`: list every file and say why it belongs to the unit;
- the unit's switch criteria in design 0025 are met, from the records under `docs/qa/` and not from the PR description: the accuracy table on the evaluation samples, the comparison record against the Kotlin implementation, zero native crashes;
- with the unit's engine setting on its Kotlin value, and with `fga.native` off, the app behaves as `main` did before the PR: the affected device QA suites give the same result case by case;
- the Kotlin implementation is still there and still selectable until the unit's lifecycle says it is removed.
- The planner runs the full device QA (every suite) before this merge, and the owner approves it.

## What it must not do

Change code, push or merge. A temporary edit for a guard check is restored before the report.

## The report

- Written in Korean, with a clear pass or send-back verdict.
- **Evidence for a pass:** a table with one row per requirement: the code location that implements it, the test that covers it, and how the verifier confirmed it (ran, read, or not confirmed).

## After the report (planner)

- The planner reviews every row marked "not confirmed" or left without a test before merging.
- The planner checks each finding in the code before sending work back, decides to merge or send back, and records the verifier's findings in the PR comment.
