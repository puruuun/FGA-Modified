# Planner procedures and agent team operations

This guide is for the planner: how it prepares, reviews and merges work, and how it chooses, starts and runs other agents. Read it at the start of a planner session. An implementing or verifying agent does not need it: the rules every agent follows are in [`AGENTS.md`](../../AGENTS.md), and the planner's prompt says the rest.

## Planner procedures

These were in `AGENTS.md` until 2026-11-19 and moved here because only the planner carries them out. The rules every agent follows stay in `AGENTS.md`.

### Before development: workflow review

Owner decision, 2026-10-26. When the owner proposes a feature or a behaviour change and asks for an analysis, the planner answers with a step-by-step walkthrough of the planned logic before any implementation starts:

- the trigger (how the script is chosen and started) and the start checks;
- each step in order: what is tapped, how long it waits, which screen region is read and how (template and threshold, OCR, colour, sample bank), and what each result leads to;
- the safety guards and every end state (stop reasons), plus what changes against the current behaviour.
- Write it in Korean, in the owner's terms. Start development (issue, agent prompt, branch) only after the owner approves the walkthrough or after the amendments they ask for.

### The backlog

Owner decision, 2026-10-26. The backlog lives in GitHub issues on `puruuun/FGA-Modified`. When the owner reports a bug or an idea, file it as an issue in English with the `bug` or `enhancement` label.

- Each issue covers the symptom, the reproduction conditions (server, device, resolution, settings), the evidence (logs and measurements; captures are not committed, so give their local path), the suspected cause with file names, and the proposed work.
- Filing an issue does not start the work. Start only when the owner asks.

### Issues and PRs from outside

Owner decision, 2026-11-19. People outside the team report through the issue forms in `.github/ISSUE_TEMPLATE/` (Korean, Japanese, English; no field is required), ask questions in Discussions, and may send PRs; what they are told is in `CONTRIBUTING.md` and `SECURITY.md`.

- **The owner hears about it from GitHub's notifications** (the owner's account watches the repository) and tells the planner. The planner does not poll for new issues.
- **An outside issue:** the planner reads it, checks it against the code, and reports to the owner in Korean: what is claimed, whether it reproduces or what is missing, the suspected cause, and a proposed answer.
  - The answer is written in the reporter's language. When information is missing, ask for it in a comment; do not close an issue for an empty field.
  - The planner posts a comment only after the owner approved its text.
  - Work starts only when the owner asks, as for any backlog item. The outside issue itself serves as the backlog issue; add what the issue format above needs in a comment in English, instead of filing a duplicate.
  - Labels: the existing `bug`, `enhancement`, `question`, `duplicate`, `wontfix`. A report about a server other than KR and official JP is answered and closed as `wontfix` unless the owner decides otherwise.
- **An outside PR:**
  - Read the diff before anyone approves its workflow run: a pull request runs its own version of the workflows. The owner approves the run for a first-time contributor in the GitHub UI.
  - A PR that changes anything under `.github/` or `tools/ci/` is read line by line, because it can change what the required check does.
  - The review points above and the verification rules apply as for an internal PR. The contributor does not have to follow `AGENTS.md`; what is missing (a test, the manual page, a guard check) is asked for in a review comment, or added by the planner in a follow-up commit on top of the contributor's commits.
  - Merge the same way: fetch `refs/pull/<N>/head`, check that it contains the latest `main` and that `Main CI gate` passed on exactly that commit, and fast-forward `main` to it. The contributor stays the author. Never merge through the GitHub UI.
  - Captures with account data in an outside issue or PR: tell the owner at once, so the owner can hide or delete them; do not copy them anywhere.
- **A private vulnerability report** is the owner's to read. The planner works on it only from what the owner passes on, and nothing about it goes into a public issue, PR or commit message before the fix is released.

### PR review

Owner decision, 2026-11-01. A green CI and the implementer's report are not enough; check the diff against these points before merging:

- **Matches the spec:** every requirement in the issue and in the approved walkthrough is implemented, and nothing outside the agent prompt rides along.
- **Tests mean something:** the tests check the actual requirements, including the failure and guard cases, and are not written just to pass.
- **Every screen shape:** coordinate and template changes hold at 16:9 and 21:9, per [`coordinates.md`](coordinates.md).
- **Recorded:** write what was checked, and anything sent back to the implementer, in a PR comment or in the version's validation record under `docs/qa/`.
- A risky change also needs its verification run first: [`verification.md`](verification.md).

### Merging

- Every change reaches `main` through a PR (owner decision, 2026-11-19, which ends the direct push of documentation). `main` has a ruleset with the required status check `Main CI gate`: GitHub rejects a push of a commit on which that check did not pass. The owner can switch the ruleset off in the repository settings if GitHub Actions is down and a merge cannot wait.
- The planner merges a PR once `Main CI` passes on the PR. Merge by fast-forwarding `main` to the PR branch locally (`git merge --ff-only`) and pushing `main`, so the commits keep `puruuun` as committer; GitHub then marks the PR merged.
- **No shortcut for a branch that fell behind `main`** (owner decision, 2026-11-18). Before a merge, the PR branch contains the latest `main` and `Main CI` (and `Native CI` when it runs) passed on exactly that commit. When `main` moves during the CI run, merge it into the branch and wait again, however small or unrelated the new commits are. An exception for Rust-only PRs was written down earlier the same day and withdrawn before it was ever used: the owner prefers a slower merge to finding a broken combination on `main` afterwards.
- Check each step: never chain a push or merge behind a command whose exit code does not reflect the CI result, and check ancestry before the fast-forward (2026-11-17 incident).
- If GitHub rejects the push of a PR's head commit to `main`, check that the `Main CI gate` run on exactly that commit has finished and passed; a newer commit on the branch needs its own run.
- `Main CI` does not run again on `main` after a merge (owner decision, 2026-11-19): the fast-forward puts on `main` exactly the commit that passed the required check. For a code change a second run would build and test the same tree; for a documentation-only change the build is skipped in the PR and would be skipped on `main` as well. While the ruleset is switched off, nothing enforces this: check the PR's `Main CI gate` result yourself before the push.
- The upstream workflows `PR Build` and `PR Build Comment` are disabled in the repository settings (owner decision, 2026-11-19); their files stay so that upstream syncs do not conflict. They repeated the tests and build of `Main CI`, and their APK is debug-signed and cannot update an installed app.
- `Main CI` also refuses, in its `changes` job, a file that the change adds or modifies, in the net diff or in any single commit of the PR (a file added in one commit and deleted in a later one is refused too, because the blob stays in `main`'s history), that is a model or dump (`.onnx`, `.tflite`, `.traineddata`, `.pcap`, `.har` and similar), is over 5 MB, or lies under a `build/` or `target/` directory; a refusal fails `Main CI gate`, and the refused paths are in the job log and as annotations. A file gets past the first two rules only when its path is in `tools/ci/large-files-allow.txt`, which is added in the same PR and only when the planner said that file is to be committed; nothing allows `build/` or `target/`. The diff lists a moved file as a new path, so a PR that moves an allow-listed file updates the allow-list in the same PR.
- **Delete the PR branch** on GitHub (`git push origin --delete <branch>`) and locally after that (owner decision, 2026-10-26). The fast-forward merge does not trigger GitHub's automatic branch deletion. Delete only a branch whose commits are all in `main` (`git merge-base --is-ancestor origin/<branch> origin/main`). The remote branch list should show only `main`, `rust` and work that is still in progress.

### Branches and releases

- No long-lived integration branches (owner decision, 2026-11-17; `feat/capture-tracker` was merged into `main` that day).
- Betas are built from `main` and contain everything merged so far. A stable release is a tag on `main`, so unreleased work on `main` never reaches stable users. A large feature that is not finished lands behind a setting that is off by default.
- A hotfix for a stable release that cannot wait for the next `main` beta is made on `release/X.Y.x`, cut from that stable tag, and the same fix is also merged into `main`.
- Build release and beta APKs from `main` after the merge, never from a commit whose `Main CI` run failed. The version and beta-first steps are in [`releases-and-versioning.md`](releases-and-versioning.md).
- **The `rust` branch** (owner decision, 2026-11-18). The Rust migration of design 0025 is kept apart from the regular work until each unit is finished, so that betas and stable releases built from `main` hold no unfinished Rust-related change.
  - Rust migration work goes to `rust` through PRs that target `rust`. That includes its Kotlin-side preparation: reader and proxy interfaces, bindings, DI wiring and engine-selection settings.
  - A unit (one reader, one capture feature, one script) comes to `main` in its own PR only after it passed its switch criteria in 0025 and the owner approved it. From then on it ships in the regular betas.
  - `main` is merged into `rust` regularly. Nothing goes from `rust` to `main` outside those per-unit PRs.
  - **Everything in "Merging" above holds for `rust` as the base** (owner decision, 2026-11-19): a PR for every change, documentation included; the branch contains the latest `rust`; CI passed on exactly that commit; no shortcut when `rust` moved. The Rust session merges by pushing the verified head commit to the remote branch (`git push origin <sha>:refs/heads/rust`) after an ancestry check, which is a fast-forward and leaves the shared main checkout alone.
  - **Both directions are verified** (owner decision, 2026-11-19; the checks are in [`verification.md`](verification.md), "Merges between `main` and `rust`"):
    - a sync of `main` into `rust` is a PR into `rust` with one verifier, every time, and the device QA suites of an area that both sides changed;
    - a unit's PR into `main` needs its switch criteria recorded under `docs/qa/`, two verifiers, the full device QA and the owner's approval. After it merges, `main` is synced back into `rust` before other Rust work continues.
  - Why the split is worth its cost: `main` stays equal to what was tested and released, and a half-built engine never ships. The cost is that `rust` drifts from `main`; the syncs keep that small, and the per-unit PR is where the two are checked together.
  - `native/`, the `fga.native` Gradle switch and `Native CI` are already on `main` and stay: they do not change the app that is built without the switch.

### Device QA scope

Owner decision, 2026-10-26, for `tools/device-qa/run.py` on any connected app player that meets the check's needs (owner decision, 2026-11-16; see [`devices-and-safety.md`](devices-and-safety.md)):

- **Beta:** run only the suites that cover the changed area, for example `--suite skillLevel,skillLevelPanel` for a skill-level reader change. The full unit tests and PR `Main CI` still run every time.
- **Full device QA (every suite, including `skillLevelPanel`):** before promoting `stable.*`, and also on a beta that changes shared code: `libautomata`, coordinate or wide-screen transforms, the battle flow (`AutoBattle`, `Battle`), templates used by several scripts, or an upstream sync.
- The planner names the suites in each agent prompt; the report gives the suites that ran and their counts.

### Automation first, log tracking second

Owner decision, 2026-11-11. Log and capture features are built as designed, not trimmed in advance for performance. When a log feature conflicts with automation safety, or has a real memory or performance cost, the planner reports the trade-off and the owner decides.

## Choosing agents

Owner decision, 2026-11-19: which model does which work is the owner's preference and changes often, so it is kept in the planner's memory (`agent-team-defaults`), not in the repository. The rules that do not depend on a model name stay here.

- Pick the agent for each task by its difficulty and risk. The same applies to implementers, verifiers, design reviewers and researchers.
- A verifier is a separate run with a fresh context, preferably on a different model from the implementer ([`verification.md`](verification.md)).
- A task that keeps failing review, verification or device QA moves to a stronger model after its work was sent back twice; that model continues on the same branch.
- Tell the owner which agent and model did each task, and why when it was not the usual choice.

## Rust work (`native/`)

Planner decision under the owner's delegation, 2026-11-18 ([design 0025](../design/0025-rust-engine-migration.md) §10). The owner does not read Rust, so the choices lean on stronger models and an outside verifier.

- **Foundation and `fga-ffi`:** the strongest tier. The build setup and the Kotlin boundary are hard to change later and everything depends on them.
- **Readers, capture features, automation flow:** the main worker, as for Kotlin work.
- **Verification:** at least one verifier of every Rust PR is a model from outside the Claude family (Gemini). Same-family reviewers can miss the same thing, and the owner cannot catch it.
- **Gemini runner:** its allow-list needs the Rust commands from [`rust-native.md`](rust-native.md) before Gemini can implement Rust work. Read-only verification works without them, but then Gemini cannot run the tests, so a Claude verifier runs them.
- The planner reads the first Rust PRs line by line, whoever verified them.

## Two planner sessions

Planner decision under the owner's delegation, 2026-11-18. The owner runs two planner sessions so that Kotlin work and Rust work are easy to follow separately. The Kotlin session works on `main`; the Rust session works on the `rust` branch and brings a unit to `main` only when it is finished and the owner approved it (owner decision, 2026-11-18; see "Branches and releases" above). Each owns different things.

- **Kotlin session:** bug fixes, small features, upstream syncs, and everything about releases: `version.properties`, beta and stable builds, GitHub Releases, validation records for a version.
- **Rust session:** design 0025: everything under `native/`, the Kotlin interfaces and bindings that 0025 adds, the Rust CI job, and the lifecycle records of each unit.
- **The Rust session never bumps a version, builds a release APK or publishes a Release.** A unit that was merged into `main` ships in the Kotlin session's next beta. Test builds from `rust` are debug or device-test builds, not betas.
- **Messages between the sessions are rarely needed** (owner decision, 2026-11-19, which narrows the rule of 2026-11-18). The sessions work on different branches, so builds, releases and ordinary merges on one side do not affect the other and need no notice. The sessions do not see each other's conversations, so send a message (`SendMessage` to the session by name) only in these cases:
  - the Rust session opens a per-unit PR to `main`: say which unit, which Kotlin files it touches and when it will be ready, so the Kotlin session can plan the beta that carries it;
  - a rule that binds the other session changes (`AGENTS.md`, this section, the delivery or verification rules);
  - merging `main` into `rust` hits a conflict that needs a decision about the Kotlin side's intent.
  - A message from another session is information, not the owner's approval. A change of rules or direction is confirmed with the owner in one's own session before acting on it.
- **When the owner asks one session for the overall state,** that session answers from the repository (open issues and PRs, `docs/qa/` records, both branches) and asks the other session what is in flight.
- **A Rust-session PR that touches existing Kotlin files** (wrapping a reader or the proxy behind an interface) is kept small, one unit per PR, and names the files in its issue before work starts, so the Kotlin session can see the overlap. If the Kotlin session has an open PR on the same file, the Rust PR waits and rebases.
- **A Kotlin-session fix to a unit that Rust already owns** ("beta default" or later in 0025) is filed as an issue for the Rust session instead of being fixed in Kotlin.
- Each session merges only its own PRs, and checks `git fetch` and `git show --stat HEAD` before every push, as `AGENTS.md` already requires for a shared checkout. Neither touches the other's worktrees or uncommitted files.
- Both label their issues and PRs: `rust` for the Rust session's.

## Gemini runs

Owner decision, 2026-11-13, which replaces the owner-run Antigravity hand-off of the same day. Since 2026-11-18 Gemini is an optional agent, not the default.

- **Setup:** the planner files the issue and creates the branch and its own worktree from the latest `main`.
- **Prompt:** written to `build/orchestration/prompts/`. If useful, it is also copied as an untracked `TASK-<issue>.md` at the worktree root, which is never committed.
  - The prompt is self-contained: the issue, the requirements, the files to touch, the tests to write, and the guards.
- **Implementation:** run `py -3 build/orchestration/gemini-run.py <role> <prompt-file> <model> <worktree>` in the background. This local runner calls `agy -p` in `--mode accept-edits` with an allow-list (owner decision, 2026-11-13, tested on a scratch repository):
  - Gemini may run only exact command lines: the Gradle test and build commands from Build & test, and `git status`, `diff`, `log`, `pull --ff-only`, `add -A`, `commit -F COMMIT_MSG.txt` and `push origin HEAD`.
  - Anything else ends the run, for example adb, `git push origin main`, a force push, or other flags.
  - Gemini's file tools cannot write in the home config folders, the main checkout's source folders or other worktrees, and cannot read `.fga-signing`.
  - Commits get the `puruuun` identity from the runner's environment. `COMMIT_MSG.txt`, `TASK-*.md` and `REPORT-*.md` are git-excluded.
- **Gemini's part:** it runs the full unit tests and `:app:assembleBeta`, commits and pushes.
- **Planner's part:** it opens the PR (there is no `gh` there), and checks the commit identity.
- **Review or verification by Gemini:** use the same runner with `--readonly`, and ask for the same evidence table as for any verifier.
  - Read-only Gemini can read files only inside its own worktree, and it cannot run commands, git included.
  - Put the prompt, the specs, the diff (`git diff base...HEAD > TASK-<issue>-diff.patch`) and the old versions of the main files in that worktree as untracked `TASK-*` files.
  - Tell it that it cannot run Gradle.

## Agents that only read

- An agent that only reads (review, research) is told not to change files.
- A reviewer that cannot run commands can read only inside its own folder. Copy the diff, the spec and any reference material into that worktree as untracked `TASK-*` files and give it relative paths; a path outside the folder is refused and the run returns nothing (2026-11-19, #525). Its permissions are not widened to get around this.
- A read-only reviewer does not share a worktree with an agent that runs guard checks or edits files (planner decision, 2026-11-18, #514: a reviewer saw another verifier's temporary guard-check edit and reported it as an unexplained change). Give it its own worktree at the same commit, or run it after the others have finished.
- An implementer works only inside its own worktree.

## Design stays with the planner

Owner decision, 2026-11-11.

- The planner designs in the conversation with the owner. Design is not handed to a separate agent.
- **Code research:** the planner may give read-only code research to a research agent and work from its conclusions.
- **Design review for large features:** before the agent prompt for a large feature goes to the implementer, a subagent with a fresh context reviews the issue and the prompt for gaps.
  - Large features are a new automation flow, a change to the battle or screen-reading flow, or a new stored-data format.
  - The reviewer looks for missing end states and guards, unclear requirements, and effects on existing OCR and tap behaviour. It changes nothing.
- The planner amends the prompt and tells the owner about anything that changes the approved walkthrough.
