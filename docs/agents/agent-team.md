# Agent team operations (planner)

This guide is for the planner when it chooses, starts or runs other agents. An implementing or verifying agent does not need it: the rules every agent follows are in [`AGENTS.md`](../../AGENTS.md), and the planner's prompt says the rest. Model choices change often, so they live here and not in `AGENTS.md` (owner decision, 2026-11-17, #458).

## Models and tiers

Owner decision, 2026-11-13, which replaces the 2026-11-12 order. Codex is not used.

- **Tiers**, by capability and cost (owner, 2026-11-13):
  - **Gemini 4 Argon** is the strongest and much the most expensive.
  - **Gemini 4 Hydrogen** and **Sonnet 5.5** are the same tier.
  - **Haiku 5.5** is one tier lower and cheap.
- **Gemini first:** Gemini is the default implementer. It is also a verifier or reviewer when a different model helps. The planner runs it itself (see "Gemini runs") with two models only:
  - `gemini-4-hydrogen-high`: the normal choice for implementation, fixes and checks;
  - `gemini-4-argon-medium`: only for hard tasks, or when a model different from the other agents is wanted, for example a verifier that should differ from the implementer.
- **Sonnet 5.5 (medium):** the same tier as Hydrogen. Use it in two cases:
  - when Gemini's work on a task is sent back twice by review, verification or device QA; Sonnet continues on the same branch;
  - as the usual verifier of Gemini's work.
- **Where Sonnet runs** (owner decision, 2026-11-13):
  - First choice is Antigravity Sonnet: the same runner with the model `claude-sonnet-5-5-medium`, in the same role and under the same rules as the Claude subagent. Implementation and fixes use `accept-edits`; review and verification use `--readonly`.
  - Use the Claude Code Sonnet subagent when an Antigravity Sonnet run ends with `usage_limit`.
  - After the owner says the Antigravity weekly limit is used up, use the Claude Code subagent directly. Go back to Antigravity Sonnet only when the owner says the limit is available again.
  - Tell the owner which of the two ran each Sonnet task.
- **Haiku 5.5 (medium, Claude subagent):**
  - light or simple work, such as small edits, lookups and mechanical checks;
  - a second model next to Sonnet or Gemini, for example as the second verifier.
- **When Gemini hits its usage limit**, do not switch to Sonnet automatically. Pick Sonnet, Haiku, or waiting for the limit, by the task's difficulty and risk.
- **Claude subagents** start with the Agent tool at **medium** effort.
- Pick the agent for each task by its difficulty and risk. The same choice applies to implementers, verifiers, design reviewers and researchers.
- Tell the owner which agent and model did each task, and why when it was not the usual choice.

## Gemini runs

Owner decision, 2026-11-13, which replaces the owner-run Antigravity hand-off of the same day.

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
- An implementer works only inside its own worktree.

## Design stays with the planner

Owner decision, 2026-11-11.

- The planner designs in the conversation with the owner. Design is not handed to a separate agent.
- **Code research:** the planner may give read-only code research to a research agent and work from its conclusions.
- **Design review for large features:** before the agent prompt for a large feature goes to the implementer, a subagent with a fresh context (usually Sonnet) reviews the issue and the prompt for gaps.
  - Large features are a new automation flow, a change to the battle or screen-reading flow, or a new stored-data format.
  - The reviewer looks for missing end states and guards, unclear requirements, and effects on existing OCR and tap behaviour. It changes nothing.
- The planner amends the prompt and tells the owner about anything that changes the approved walkthrough.
