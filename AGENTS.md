# AGENTS.md

Path-specific guidance lives in [`docs/agents/`](docs/agents) so it is loaded only when relevant: read a guide when its trigger below matches. Copilot and Claude Code pick the same guides up automatically via stubs in `.github/instructions/` and `.claude/rules/` — see [`docs/agents/README.md`](docs/agents/README.md).

## What this is

Fate/Grand Automata (FGA) — an Android app that automates farming in the game *Fate/Grand Order*. It does **not** touch the game process: it reads the screen via MediaProjection, matches templates with OpenCV (plus Tesseract OCR), and taps/swipes through an AccessibilityService. Android 7+ (minSdk 24), no root required (root screenshots are an optional path).

## Topic guides — read before touching these paths

- **Any on-screen coordinate, any file in `scripts/.../locations/`, any template image added to `app/src/main/assets/`** — [`docs/agents/coordinates.md`](docs/agents/coordinates.md). Matching runs at 720p, coordinates are 1440p, and the X origin depends on aspect ratio.
- **`gradle/libs.versions.toml`, the Gradle wrapper, any `build.gradle.kts`** — [`docs/agents/dependencies.md`](docs/agents/dependencies.md). The AGP/Gradle/Kotlin/androidx bumps only succeed in one order; it also holds the JDK, bytecode-target and `compileSdk` pins.
- **Automation logic in `scripts/`, or the Hilt wiring in `app/di/` and `app/runner/`** — [`docs/agents/automation-scripts.md`](docs/agents/automation-scripts.md). Every run gets its own Hilt component, and `script()` never returns normally.
- **Any file in `app/src/main/assets/`** — [`docs/agents/servers-and-assets.md`](docs/agents/servers-and-assets.md). Template images are per-server with an `En` fallback; server choice and app language are independent.
- **Strings under `app/src/main/res/`** — [`docs/agents/translations.md`](docs/agents/translations.md). English strings live in `values/localized.xml`. Korean and Japanese are the owner's and are edited in the repo; other languages follow upstream. The fork does not use POEditor.
- **Any user-facing feature or change, `README.md`, anything in `wiki/`, `mkdocs.yml`, `tools/docs-site/`** — [`docs/agents/docs-site.md`](docs/agents/docs-site.md). A feature is not done until its Korean manual page in `wiki/scripts/` is added or updated. `README.md` and `README.en.md` describe the latest stable release only: feature and beta changes never edit them, and the planner updates both at stable promotion. The GitHub Pages manual is republished from the stable tag when a stable Release is published.
- **Merging updates from upstream `Fate-Grand-Automata/FGA`** — [`docs/agents/upstream-sync.md`](docs/agents/upstream-sync.md). Always merge (`--no-ff`), keep the fork's workflows and translations, curate built-in CEs with the owner, and record each sync in the log table.
- **Any `adb` command, device QA run, APK install, or look at a live game screen** — [`docs/agents/devices-and-safety.md`](docs/agents/devices-and-safety.md). Agents never operate the game, never unlock appends or spend apples or quartz, and never run adb server commands; BlueStacks is `127.0.0.1:5555`.
- **`version.properties`, `docs/releases/`, `wiki/releases/`, or building Beta/Stable APKs** — [`docs/agents/releases-and-versioning.md`](docs/agents/releases-and-versioning.md). Use Semantic Versioning (`X.Y.(Z+1)` for refinements/fixes/syncs, `X.(Y+1).0` for new features) and always build/verify a Beta APK (`beta.*`, `./gradlew :app:assembleBeta`) on BlueStacks/tablet before promoting `stable.*` and publishing a GitHub Release.

## Delivery for this repository

- Deliver **code changes** through a pull request (owner decision, 2026-10-23). Branch from the latest remote `main` (for example `feat/…`, `fix/…`), commit there, push the branch, and open a PR against `main`. A change is a code change if it touches anything other than documentation: source, tests, resources, assets, build files, version files or workflows.
- **Documentation-only changes** — design docs and validation records under `docs/`, the manual under `wiki/`, `AGENTS.md` and other agent-instruction files, and other `*.md` files — are pushed directly to `main` without a PR (owner decision, 2026-10-23). Commit them with explicit pathspecs so no code rides along; if a commit would mix documentation and code, it goes through a PR.
- The local checkout can be shared with another agent at the same time. Never stash, reset, rebase or check out over changes you did not make, stage only your own paths, and check `git show --stat HEAD` before pushing.
- The planner (Claude) reviews each code PR and merges it once `Main CI` passes on the PR. Merge by fast-forwarding `main` to the PR branch locally (`git merge --ff-only`) and pushing `main`, so the commits keep `puruuun` as committer; GitHub then marks the PR merged. Rebase the branch on `main` first if needed. Implementing agents open the PR and stop; they do not merge.
- What the planner's PR review checks (owner decision, 2026-11-01). A green CI and the implementer's report are not enough; check the diff against these points before merging:
  - **Matches the spec:** every requirement in the issue and in the approved walkthrough is implemented, and nothing outside the agent prompt rides along.
  - **Tests mean something:** the tests check the actual requirements, including the failure and guard cases, and are not written just to pass.
  - **Every screen shape:** coordinate and template changes hold at 16:9 and 21:9, per [`docs/agents/coordinates.md`](docs/agents/coordinates.md).
  - **Recorded:** write what was checked, and anything sent back to the implementer, in a PR comment or in the version's validation record under `docs/qa/`.
- After merging and confirming `Main CI` on `main`, delete the PR branch on GitHub (`git push origin --delete <branch>`) and locally (owner decision, 2026-10-26). The fast-forward merge does not trigger GitHub's automatic branch deletion. Delete only a branch whose commits are all in `main` (`git merge-base --is-ancestor origin/<branch> origin/main`). The remote branch list should show only `main` and work that is still in progress, for example a feature kept open on purpose to be merged later.
- Write PR titles and descriptions in English (owner request, 2026-10-23).
- The backlog lives in GitHub issues on `puruuun/FGA-Modified` (owner decision, 2026-10-26). When the owner reports a bug or an idea, file it as an issue in English with the `bug` or `enhancement` label.
  - Each issue covers the symptom, the reproduction conditions (server, device, resolution, settings), the evidence (logs and measurements; captures are not committed, so give their local path), the suspected cause with file names, and the proposed work.
  - Filing an issue does not start the work. Start only when the owner asks.
  - A PR that resolves an issue says `Closes #N` in its description.
- Workflow review before development (owner decision, 2026-10-26). When the owner proposes a feature or a behaviour change and asks for an analysis, the planner answers with a step-by-step walkthrough of the planned logic before any implementation starts:
  - the trigger (how the script is chosen and started) and the start checks;
  - each step in order: what is tapped, how long it waits, which screen region is read and how (template and threshold, OCR, colour, sample bank), and what each result leads to;
  - the safety guards and every end state (stop reasons), plus what changes against the current behaviour.
  - Write it in Korean, in the owner's terms. Start development (issue, agent prompt, branch) only after the owner approves the walkthrough or after the amendments they ask for.
- For new commits, use the owner-approved identity `puruuun <188972188+puruuun@users.noreply.github.com>` for both author and committer. Do not use Codex as author, committer, or co-author unless the user requests it. Do not add `Co-Authored-By` trailers or "Generated with" lines for any AI agent (Codex, Claude, Copilot, …) to commits or PR descriptions; GitHub should show only `puruuun`. Preserve authorship on existing published commits.
- Preserve existing `main` changes and skip already applied changes. Never force push or bypass branch protection or execution-environment restrictions. Stop and report a rejected push or a conflict that cannot be resolved safely.
- Before pushing a branch, run the full unit test command from Build & test locally and confirm it passes. Commits that change only documentation are exempt.
- After opening or updating a PR, check its `Main CI` run. If it fails, fix it on the branch before any other work. After merging, check the `Main CI` run on `main` too.
- Device QA scope (owner decision, 2026-10-26) for `tools/device-qa/run.py` on BlueStacks:
  - **Beta:** run only the suites that cover the changed area, for example `--suite skillLevel,skillLevelPanel` for a skill-level reader change. The full unit tests and PR `Main CI` still run every time.
  - **Full device QA (every suite, including `skillLevelPanel`):** before promoting `stable.*`, and also on a beta that changes shared code: `libautomata`, coordinate or wide-screen transforms, the battle flow (`AutoBattle`, `Battle`), templates used by several scripts, or an upstream sync.
  - The planner names the suites in each agent prompt; report the suites that ran and their counts.
- Do not build beta or stable APKs from a commit whose `Main CI` run failed. Build release and beta APKs from `main` after the merge.

- Follow the **Beta-First QA & Semantic Versioning** workflow in [`docs/agents/releases-and-versioning.md`](docs/agents/releases-and-versioning.md) (owner decision, 2026-10-24): bump `beta.versionName`/`beta.versionCode` first (`X.Y.(Z+1)-beta.N` for refinements/fixes/syncs, `X.(Y+1).0-beta.N` for new features), build and install the Beta APK (`./gradlew :app:assembleBeta`) for BlueStacks/tablet verification, and only bump `stable.*` and publish a GitHub Release after the owner approves.

## Agent team

- Owner decision, 2026-10-23: the planner (Claude) writes specs and agent prompts, reviews and merges PRs, and builds, installs and releases. An implementing agent implements, tests and opens the PR.
- Agent models and order (owner decision, 2026-11-11; replaces the 2026-10-30 order). The planner chooses the agent and model for each task by its difficulty and risk, within this order of usage pools:
  1. **Codex** (`gpt-6.1-sol`, via `build/orchestration/codex-run.sh`): first choice.
  2. **Gemini** through the Antigravity CLI `agy` (via `build/orchestration/gemini-run.sh`), when Codex is at its usage limit or when the planner judges Gemini the better fit for the task:
     - **Gemini 3.8 Flash: always `high`** (`gemini-3.8-flash-high`). For simple, well-bounded tasks and code research.
     - **Gemini 4 Argon: `medium` by default** (`gemini-4-argon-medium`), **`high` for hard tasks** (`gemini-4-argon-high`).
  3. **Sonnet 5.5 (medium)**: only when both Codex and Gemini are out of usage. It shares the planner's own usage, so it is the last resort.
- The same order applies to verification agents, design reviews and research agents. A Gemini agent used only for reading (review, research) runs with the read-only settings (`~/.claude/agents/gemini-readonly.settings.json`); a Gemini implementer runs with the implementer settings and only inside its own worktree.
- Every agent follows the same rules: its own worktree and branch, the PR flow, the `puruuun` commit identity, no AI trailers, no device work unless the planner assigns it. When a task moves to another agent, it continues from where the previous one stopped on the same branch. The planner tells the owner which agent and model did each task, and why when it was not the first choice.
- Verification agent for risky changes (owner decision, 2026-11-11). Before the planner merges a PR with a risky change, a separate verification agent reviews it.
  - **Risky changes:**
    - automation logic, screen reading, taps, coordinates or templates (`scripts/`, `libautomata/`, `app/` runner and imaging);
    - capture, logging and stored-data formats (including migrations of saved files);
    - file storage and export, certificates and security;
    - services, permissions and the manifest;
    - build, signing and versioning;
    - upstream syncs.
  - **Not risky:** UI layout, strings, the manual and test-only changes. The planner reviews these alone, as before.
  - **Who verifies:** a separate agent run with a fresh context, on the same agent order as implementation (Codex, then Gemini, then Sonnet). It may be the same model as the implementer, but never the same session. If no agent is available, the planner does a full line-by-line review and says so in the PR record.
  - **What the verifier does:**
    - compares the diff with every requirement in the issue;
    - checks that the tests cover the requirements and the failure and guard cases;
    - looks for missed edge cases and effects outside the stated scope;
    - re-runs the full unit tests and re-records the screenshots itself instead of trusting the implementer's report.
  - **What it must not do:** change code, push or merge. It reports findings in Korean with a clear pass or send-back verdict.
  - **After the report:** the planner decides to merge or send back, and records the verifier's findings in the PR comment.
- Design stays with the planner (owner decision, 2026-11-11). The planner designs in the conversation with the owner; design is not handed to a separate agent.
  - **Code research:** the planner may give read-only code research to a research agent and work from its conclusions.
  - **Design review for large features:** before the agent prompt for a large feature goes to the implementer, a different model from the planner (by the agent order above) reviews the issue and the prompt for gaps. Large features are a new automation flow, a change to the battle or screen-reading flow, or a new stored-data format. The reviewer looks for missing end states and guards, unclear requirements, and effects on existing OCR and tap behaviour. It changes nothing.
  - The planner amends the prompt and tells the owner about anything that changes the approved walkthrough.
- Talk to the owner, and write agent reports and validation records, in Korean. PR titles and descriptions and GitHub issues stay in English.

## Build & test

```bash
./gradlew :app:compileDebugKotlin # Compiles the kotlin code for the android app.
./gradlew :app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test # Full unit test suite, same as Main CI.
./gradlew :scripts:test          # the only unit tests in the repo (JUnit 5 + assertk + mockk)
./gradlew :scripts:test --tests '*SupportSelectionTest'          # single test class
./gradlew :scripts:test --tests '*SupportSelectionTest.someName' # single test method
./gradlew lint                   # abortOnError = false, so lint never fails the build
./gradlew dependencyUpdates      # ben-manes versions plugin
```

Device QA and APK builds need the Android SDK. If the shell has no `ANDROID_HOME` and there is no `local.properties`, set `ANDROID_HOME` to the SDK, which is `%LOCALAPPDATA%\Android\Sdk` on the owner's PC. Without it, Gradle stops with "SDK location not found".

Everything builds on **JDK 21** via a Gradle toolchain pin; emitted bytecode stays at **Java 11**.

## Module layout and dependency direction

```
app  ──▶ scripts ──▶ libautomata
 └──▶ prefs ──▶ scripts
```

- **`libautomata/`** — pure JVM Kotlin (no Android deps). Game-agnostic automation primitives: `Region`, `Location`, `Size`, `Pattern`, `AutomataApi`/`StandardAutomataApi`, `ScreenshotManager`, `ImageMatcher`, `Clicker`, `Swiper`, `Waiter`, `Scale`, `GameAreaManager`, `EntryPoint`, `ExitManager`. Everything platform-specific is an interface (`ScreenshotService`, `GestureService`, `OcrService`, `PlatformImpl`, `PlatformPrefs`) implemented in `app`.
- **`scripts/`** — pure JVM Kotlin. All FGO knowledge: entry points, per-screen modules, coordinates, image names, prefs *interfaces*. This is where automation logic belongs, and the only module with tests (it's testable precisely because it has no Android dependency — keep it that way).
- **`prefs/`** — Android library implementing the `I*Preferences` interfaces from `scripts` on top of `flow-preferences`. `PrefsCore` holds the raw `Pref<T>` delegates; `PreferencesImpl` adapts them to the script-facing interfaces.
- **`app/`** — Compose UI, Hilt wiring, the two services, and the Android implementations of the `libautomata` interfaces (`imaging/`, `accessibility/`, `root/`, `util/`).

## Repo conventions

- Target servers (owner decision, 2026-11-08): new features and fixes are designed, specified and tested for **KR and official JP (`GameServer.Jp.Original`) only**. Do not add branches, tests or walkthrough steps for BetterFGO, NA, CN or TW, and do not ask the owner about them. Existing upstream code for those servers stays, and upstream syncs keep merging it.
- Kotlin official code style; `kotlin.time.Duration` is used everywhere for waits.
- Hilt + KSP for DI; Compose (BOM-managed) with several `ExperimentalX` opt-ins already enabled in `app/build.gradle.kts`.
- Dependencies live in `gradle/libs.versions.toml` — always add/bump there, never inline coordinates.
- `wiki/` is the source of the GitHub Pages manual (`.github/workflows/pages.yml`); the GitHub wiki is not used — detailed user-facing docs go there, while `README.md` (Korean) and `README.en.md` (English) are separate files that describe the latest stable release. Their feature tables and release links are updated together, at stable promotion only.
- Agent guidance: keep this file to what's true repo-wide and put path-specific rules in [`docs/agents/`](docs/agents), following [`docs/agents/README.md`](docs/agents/README.md).
