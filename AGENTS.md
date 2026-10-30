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

- Owner decision, 2026-10-23: the planner (Claude) writes specs and agent prompts, reviews and merges PRs, and builds, installs and releases. The implementing agent (Codex, or the Sonnet fallback below) implements, tests and opens the PR.
- Implementer order (owner decision, 2026-10-30): **Codex first; Sonnet 5.5 at medium effort is the fallback.** When Codex is unavailable (usage limit or not reachable), the planner hands the work to Sonnet 5.5 (medium), which continues from where Codex stopped on the same branch. No other model is used.
- The fallback follows the same rules as Codex: same branch and PR flow, same commit identity, no device work unless the planner assigns it. The planner tells the owner when a task moved to Sonnet and why, and switches back to Codex for new work once its limit is refilled.
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

- Kotlin official code style; `kotlin.time.Duration` is used everywhere for waits.
- Hilt + KSP for DI; Compose (BOM-managed) with several `ExperimentalX` opt-ins already enabled in `app/build.gradle.kts`.
- Dependencies live in `gradle/libs.versions.toml` — always add/bump there, never inline coordinates.
- `wiki/` is the source of the GitHub Pages manual (`.github/workflows/pages.yml`); the GitHub wiki is not used — detailed user-facing docs go there, while `README.md` (Korean) and `README.en.md` (English) are separate files that describe the latest stable release. Their feature tables and release links are updated together, at stable promotion only.
- Agent guidance: keep this file to what's true repo-wide and put path-specific rules in [`docs/agents/`](docs/agents), following [`docs/agents/README.md`](docs/agents/README.md).
