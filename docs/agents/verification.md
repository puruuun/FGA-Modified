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

## What it must not do

Change code, push or merge. A temporary edit for a guard check is restored before the report.

## The report

- Written in Korean, with a clear pass or send-back verdict.
- **Evidence for a pass:** a table with one row per requirement: the code location that implements it, the test that covers it, and how the verifier confirmed it (ran, read, or not confirmed).

## After the report (planner)

- The planner reviews every row marked "not confirmed" or left without a test before merging.
- The planner checks each finding in the code before sending work back, decides to merge or send back, and records the verifier's findings in the PR comment.
