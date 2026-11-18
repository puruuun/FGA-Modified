# Agent team operations (planner)

This guide is for the planner when it chooses, starts or runs other agents. An implementing or verifying agent does not need it: the rules every agent follows are in [`AGENTS.md`](../../AGENTS.md), and the planner's prompt says the rest. Model choices change often, so they live here and not in `AGENTS.md` (owner decision, 2026-11-17, #458).

## Models and tiers

Owner decision, 2026-11-18, which replaces the 2026-11-13 "Gemini first" order. Codex is not used.

- **Claude Code models first.** The planner starts them as Claude subagents with the Agent tool, at **medium** effort.
- **Sonnet 5.5 (medium): the main worker.** It is the default implementer, and the default for fixes, verification, design review and research.
- **Haiku 5.5 (medium): one tier lower and cheap.**
  - light or simple work, such as small edits, lookups and mechanical checks;
  - a second model next to Sonnet, for example as the verifier of Sonnet's work or the second verifier.
- **Opus 5.5 (medium): the top tier, used sparingly.**
  - very hard tasks;
  - a task that keeps failing review, verification or device QA: after Sonnet's work is sent back twice, Opus continues on the same branch.
- **Gemini: still available, but not the first choice.** The planner runs it itself (see "Gemini runs") with two models only: `gemini-4-hydrogen-high` (Sonnet's tier) and `gemini-4-argon-medium` (strong and much the most expensive). Use it when:
  - a model from outside the Claude family helps, for example as a verifier that should differ from the implementer;
  - Claude Code's usage limit is reached and the task should not wait.
- **Sonnet through Antigravity** (the runner model `claude-sonnet-5-5-medium`) is no longer the first choice. Sonnet runs as a Claude Code subagent.
- **Verifiers:** Sonnet's work is usually verified by Haiku. When two verifiers are needed, the usual pair is a fresh Sonnet session and Haiku; Opus or Gemini replaces one of them when the change is hard or an outside model is wanted.
- Pick the agent for each task by its difficulty and risk. The same choice applies to implementers, verifiers, design reviewers and researchers.
- Tell the owner which agent and model did each task, and why when it was not the usual choice.

## Rust work (`native/`)

Planner decision under the owner's delegation, 2026-11-18 ([design 0025](../design/0025-rust-engine-migration.md) §10). The owner does not read Rust, so the choices lean on stronger models and an outside verifier.

- **Foundation and `fga-ffi`:** Opus. The build setup and the Kotlin boundary are hard to change later and everything depends on them.
- **Readers, capture features, automation flow:** Sonnet, as for Kotlin work.
- **Verification:** at least one verifier of every Rust PR is Gemini, a model from outside the Claude family. Same-family reviewers can miss the same thing, and the owner cannot catch it.
- **Gemini runner:** its allow-list needs the Rust commands from [`rust-native.md`](rust-native.md) before Gemini can implement Rust work. Read-only verification works without them, but then Gemini cannot run the tests, so a Claude verifier runs them.
- The planner reads the first Rust PRs line by line, whoever verified them.

## Two planner sessions

Planner decision under the owner's delegation, 2026-11-18. The owner runs two planner sessions so that Kotlin work and Rust work are easy to follow separately. The Kotlin session works on `main`; the Rust session works on the `rust` branch and brings a unit to `main` only when it is finished and the owner approved it (owner decision, 2026-11-18; see `AGENTS.md`, "Exception: the `rust` branch"). Each owns different things.

- **Kotlin session:** bug fixes, small features, upstream syncs, and everything about releases: `version.properties`, beta and stable builds, GitHub Releases, validation records for a version.
- **Rust session:** design 0025: everything under `native/`, the Kotlin interfaces and bindings that 0025 adds, the Rust CI job, and the lifecycle records of each unit.
- **The Rust session never bumps a version, builds a release APK or publishes a Release.** A unit that was merged into `main` ships in the Kotlin session's next beta. Test builds from `rust` are debug or device-test builds, not betas.
- **Tell the other session before it is affected** (owner decision, 2026-11-18). The sessions do not see each other's conversations, so send a message (`SendMessage` to the session by name) in these cases, and say what you will do and when:
  - the Kotlin session is about to build a beta or a stable release, or to cut a release branch;
  - either session is about to change a shared file: `AGENTS.md`, a workflow under `.github/workflows/`, a Gradle or version file, or a design document the other session maintains;
  - either session needs a change in the other's area (a Kotlin file for the Rust session, anything under `native/` for the Kotlin session);
  - the Rust session opens a per-unit PR to `main`, or merges `main` into `rust` and finds a conflict in a file the Kotlin session changed.
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
- **Planner's part:** it opens the PR (there is no `gh` there), and checks the commit identity and the trailers.
- **Review or verification by Gemini:** use the same runner with `--readonly`, and ask for the same evidence table as for any verifier.
  - Read-only Gemini can read files only inside its own worktree, and it cannot run commands, git included.
  - Put the prompt, the specs, the diff (`git diff base...HEAD > TASK-<issue>-diff.patch`) and the old versions of the main files in that worktree as untracked `TASK-*` files.
  - Tell it that it cannot run Gradle.

## Agents that only read

- An agent that only reads (review, research) is told not to change files.
- A read-only reviewer does not share a worktree with an agent that runs guard checks or edits files (planner decision, 2026-11-18, #514: a reviewer saw another verifier's temporary guard-check edit and reported it as an unexplained change). Give it its own worktree at the same commit, or run it after the others have finished.
- An implementer works only inside its own worktree.

## Design stays with the planner

Owner decision, 2026-11-11.

- The planner designs in the conversation with the owner. Design is not handed to a separate agent.
- **Code research:** the planner may give read-only code research to a research agent and work from its conclusions.
- **Design review for large features:** before the agent prompt for a large feature goes to the implementer, a subagent with a fresh context (usually Sonnet) reviews the issue and the prompt for gaps.
  - Large features are a new automation flow, a change to the battle or screen-reading flow, or a new stored-data format.
  - The reviewer looks for missing end states and guards, unclear requirements, and effects on existing OCR and tap behaviour. It changes nothing.
- The planner amends the prompt and tells the owner about anything that changes the approved walkthrough.
