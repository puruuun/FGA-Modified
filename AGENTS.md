# AGENTS.md

This file holds what every agent in this repository must follow. Path-specific and role-specific guidance lives in [`docs/agents/`](docs/agents) so it is loaded only when relevant: read a guide when its trigger below matches. Copilot and Claude Code pick the path-specific guides up automatically via stubs in `.github/instructions/` and `.claude/rules/` — see [`docs/agents/README.md`](docs/agents/README.md).

## What this is

Fate/Grand Automata (FGA) — an Android app that automates farming in the game *Fate/Grand Order*. It does **not** touch the game process: it reads the screen via MediaProjection, matches templates with OpenCV (plus Tesseract OCR), and taps/swipes through an AccessibilityService. Android 7+ (minSdk 24), no root required (root screenshots are an optional path).

## Topic guides — read before touching these paths

- **Any on-screen coordinate, any file in `scripts/.../locations/`, any template image added to `app/src/main/assets/`** — [`docs/agents/coordinates.md`](docs/agents/coordinates.md). Matching runs at 720p, coordinates are 1440p, and the X origin depends on aspect ratio.
- **`gradle/libs.versions.toml`, the Gradle wrapper, any `build.gradle.kts`** — [`docs/agents/dependencies.md`](docs/agents/dependencies.md). The AGP/Gradle/Kotlin/androidx bumps only succeed in one order; it also holds the JDK, bytecode-target and `compileSdk` pins.
- **Automation logic in `scripts/`, or the Hilt wiring in `app/di/` and `app/runner/`** — [`docs/agents/automation-scripts.md`](docs/agents/automation-scripts.md). Every run gets its own Hilt component, and `script()` never returns normally.
- **Any file in `app/src/main/assets/`** — [`docs/agents/servers-and-assets.md`](docs/agents/servers-and-assets.md). Template images are per-server with an `En` fallback; server choice and app language are independent.
- **Strings under `app/src/main/res/`** — [`docs/agents/translations.md`](docs/agents/translations.md). English strings live in `values/localized.xml`. Korean and Japanese are the owner's and are edited in the repo; other languages follow upstream. The fork does not use POEditor.
- **Any user-facing feature or change, `README.md`, anything in `wiki/`, `mkdocs.yml`, `tools/docs-site/`** — [`docs/agents/docs-site.md`](docs/agents/docs-site.md). A feature is not done until its Korean manual page in `wiki/scripts/` is added or updated. `README.md`, `README.en.md`, and `README.ja.md` describe the latest stable release only: feature and beta changes never edit them, and the planner updates all three at stable promotion. The GitHub Pages manual is republished from the stable tag when a stable Release is published.
- **Verifying a PR, or implementing a risky change (see "Risky changes need verification" below)** — [`docs/agents/verification.md`](docs/agents/verification.md). What the verifier checks, the guard check that the implementer also does, and the evidence table of the report.
- **Planner work: walkthroughs, issues, agent prompts, PR review, merging, branches, device QA scope, choosing and running other agents (the planner only)** — [`docs/agents/agent-team.md`](docs/agents/agent-team.md). A planner session reads it at its start.
- **Merging updates from upstream `Fate-Grand-Automata/FGA`** — [`docs/agents/upstream-sync.md`](docs/agents/upstream-sync.md). Always merge (`--no-ff`), keep the fork's workflows and translations, curate built-in CEs with the owner, and record each sync in the log table.
- **Anything under `native/` (the Rust core), the Kotlin bindings to it, or the Gradle and CI steps that build it** — [`docs/agents/rust-native.md`](docs/agents/rust-native.md). No `unsafe` outside `fga-ffi`, no `unwrap` outside tests, every Kotlin-callable function catches panics, and a build with `fga.native` off must always work. The plan is [design 0025](docs/design/0025-rust-engine-migration.md).
- **Any `adb` command, device QA run, APK install, or look at a live game screen** — [`docs/agents/devices-and-safety.md`](docs/agents/devices-and-safety.md). Agents never operate the game, never unlock appends or spend apples or quartz, and never run adb server commands; BlueStacks is `127.0.0.1:5555`.
- **`version.properties`, `docs/releases/`, `wiki/releases/`, or building Beta/Stable APKs** — [`docs/agents/releases-and-versioning.md`](docs/agents/releases-and-versioning.md). Semantic Versioning and the beta-first workflow: a Beta APK is built and verified on a device before `stable.*` is promoted and a GitHub Release is published.

## Roles

- **The planner (Claude)** writes specs and agent prompts, reviews and merges PRs, and builds, installs and releases.
- **An implementing agent** implements, tests, opens the PR and stops. It does not merge.
- **A verifying agent** checks a risky change and reports. It does not change code, push or merge.
- Every agent follows the same rules: its own worktree and branch, the delivery rules below, the configured commit identity, and no device work unless the planner assigns it. When a task moves to another agent, it continues from where the previous one stopped on the same branch.
- **When something is in the way, stop and report it; do not work around it.**
  - A denied tool, a refused file read, a missing permission or a blocked command is reported with its exact message. Never change permission settings or allow-lists, and never use a flag that switches permission checks off.
  - A missing tool or library is reported. Do not install software; the planner asks the owner.
  - A command that made a device, an emulator or a service unresponsive is not tried again in another form. Report what was run and what happened.
  - A requirement that cannot be met is reported with the numbers as they are. Do not tune a threshold, a test or a comparison to make a result look like a pass.
- **Run builds and tests in the foreground and report once.** Leave no background job running, and remove temporary files you made outside your task's output.

## Delivery

- **Every change goes through a pull request, documentation included.** Branch from the latest remote `main` (for example `feat/…`, `fix/…`, `docs/…`), commit there, push the branch, and open a PR against `main`. `main` accepts only commits on which the `Main CI gate` check passed, so a direct push of an unchecked commit is rejected.
- **A documentation-only PR takes a fast path.** When every changed file is documentation (`docs/`, `wiki/`, root `*.md`, `AGENTS.md`, `.claude/`, `.github/instructions/`), `Main CI` skips the build and the gate passes within a minute or two. The exact list is in `tools/ci/ci_gate.py`. A validation record or manual page may ride in the same PR as the code it belongs to.
- **Short branches only.** Each feature or fix is a short branch from `main` and comes back through its own PR as soon as it is done. Betas are built from `main`, so a large feature that is not finished lands behind a setting that is off by default.
- **Rust migration work goes to the `rust` branch, not to `main`**, through PRs that target `rust`, including its Kotlin-side preparation and its documentation. Every rule in this section holds there with `rust` as the base: a PR for every change, tests before a push, CI after it. A finished, owner-approved unit comes to `main` in its own PR. The details are in [`docs/agents/agent-team.md`](docs/agents/agent-team.md), "Branches and releases".
- **Never force push**, and never bypass branch protection or execution-environment restrictions. Rebase a work branch onto its latest base before its first push; once it is pushed, merge the base into it instead. Preserve existing `main` changes and skip changes that are already applied. Stop and report a rejected push or a conflict that cannot be resolved safely.
- **The checkout is shared with other agents and sessions.**
  - Edit in your own worktree, not in the main checkout. Uncommitted files left in the main checkout block another session's merges. Keep the main checkout clean and on `main`.
  - Never stash, reset, rebase or check out over changes you did not make. Stage only your own paths, and check `git show --stat HEAD` before pushing.
  - Sessions do not share their conversations. What another session needs to know goes into the repository (issues, PRs, `docs/qa/` records).
- **Commit with the Git identity already configured on this PC (`puruuun`).** Do not set another author or committer, and preserve authorship on existing published commits.
- **Tests before a push.** Run the full unit test command from Build & test and confirm it passes. A PR that changes only documentation is exempt. A PR that changes only Rust code under `native/` is not: it runs the unit test command as well as the Rust checks in [`docs/agents/rust-native.md`](docs/agents/rust-native.md).
- **CI after a push.** After opening or updating a PR, check its `Main CI` run. If it fails, fix it on the branch before any other work.
- **Device QA** runs only when the planner assigns it; the planner names the suites, and the report gives the suites that ran and their counts.
- **Images in the repository:**
  - Commit only app assets (`app/src/main/assets/`) and QA regression samples that a device QA case file uses (`docs/reference-images/`).
  - One-off measurement crops, debugging screenshots and audit images stay local under `build/` or go on the issue as attachments.
  - Captures with account data are never committed.
  - Model files, measurement samples and captured traffic stay local under `build/` unless the planner says a named file is to be committed.
- **Language.** Talk to the owner, and write agent reports and validation records, in Korean. PR titles and descriptions and GitHub issues are in English. A PR that resolves an issue says `Closes #N` in its description.

## Risky changes need verification

Before the planner merges a PR with a risky change, a separate verification agent with a fresh context reviews it. The highest-risk changes (automation logic and stored-data formats) get two verifiers on different models. The procedure is in [`docs/agents/verification.md`](docs/agents/verification.md).

- **Risky changes:**
  - automation logic, screen reading, taps, coordinates or templates (`scripts/`, `libautomata/`, `app/` runner and imaging);
  - capture, logging and stored-data formats (including migrations of saved files);
  - file storage and export, certificates and security;
  - services, permissions and the manifest;
  - build, signing and versioning;
  - upstream syncs;
  - anything under `native/` (the Rust core) and its Kotlin bindings.
- **Not risky:** UI layout, strings, the manual and test-only changes.
- **For every implementer:** each new guard or regression test must fail without its guard. Disable the protected line, run that test, confirm that it fails, restore the line, and name the tests checked this way in the report. Fakes and mocks return only what the real implementation can return in that situation.

## Build & test

```bash
./gradlew :app:compileDebugKotlin # Compiles the kotlin code for the android app.
./gradlew :app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test # Full unit test suite, same as Main CI.
./gradlew :scripts:test          # the scripts module's tests only (JUnit 5 + assertk + mockk)
./gradlew :scripts:test --tests '*SupportSelectionTest'          # single test class
./gradlew :scripts:test --tests '*SupportSelectionTest.someName' # single test method
./gradlew lint                   # abortOnError = false, so lint never fails the build
./gradlew dependencyUpdates      # ben-manes versions plugin
```

Unit tests live in three modules, next to the code they cover: `scripts` (plain JVM; JUnit 5, assertk, mockk), `app` (Robolectric and Roborazzi; `:app:testCiUnitTest`) and `prefs` (`:prefs:testCiUnitTest`). `libautomata` has none.

Device QA and APK builds need the Android SDK. If the shell has no `ANDROID_HOME` and there is no `local.properties`, set `ANDROID_HOME` to the SDK, which is `%LOCALAPPDATA%\Android\Sdk` on the owner's PC. Without it, Gradle stops with "SDK location not found".

Everything builds on **JDK 21** via a Gradle toolchain pin; emitted bytecode stays at **Java 11**.

## Module layout and dependency direction

```
app  ──▶ scripts ──▶ libautomata
 └──▶ prefs ──▶ scripts
```

- **`libautomata/`** — pure JVM Kotlin (no Android deps). Game-agnostic automation primitives: `Region`, `Location`, `Size`, `Pattern`, `AutomataApi`/`StandardAutomataApi`, `ScreenshotManager`, `ImageMatcher`, `Clicker`, `Swiper`, `Waiter`, `Scale`, `GameAreaManager`, `EntryPoint`, `ExitManager`. Everything platform-specific is an interface (`ScreenshotService`, `GestureService`, `OcrService`, `PlatformImpl`, `PlatformPrefs`) implemented in `app`.
- **`scripts/`** — pure JVM Kotlin. All FGO knowledge: entry points, per-screen modules, coordinates, image names, prefs *interfaces*. This is where automation logic belongs. Its tests run on the plain JVM because the module has no Android dependency — keep it that way.
- **`prefs/`** — Android library implementing the `I*Preferences` interfaces from `scripts` on top of `flow-preferences`. `PrefsCore` holds the raw `Pref<T>` delegates; `PreferencesImpl` adapts them to the script-facing interfaces.
- **`app/`** — Compose UI, Hilt wiring, the two services, and the Android implementations of the `libautomata` interfaces (`imaging/`, `accessibility/`, `root/`, `util/`).

## Repo conventions

- Target servers: new features and fixes are designed, specified and tested for **KR and official JP (`GameServer.Jp.Original`)**. The owner plays both. Do not add branches, tests or walkthrough steps for NA, BetterFGO, CN or TW, and do not ask the owner about them; see [`docs/agents/servers-and-assets.md`](docs/agents/servers-and-assets.md). Existing upstream code for those servers stays, and upstream syncs keep merging it.
- Kotlin official code style; `kotlin.time.Duration` is used everywhere for waits.
- Comments:
  - Write comments for *why*: constraints, external facts (for example 1440p coordinates or the 1px `fillText` border) and owner decisions.
  - Do not restate what the code does, and do not explain inside a test why it passes.
  - When code changes, update or delete the comments next to it in the same commit.
- Hilt + KSP for DI; Compose (BOM-managed) with several `ExperimentalX` opt-ins already enabled in `app/build.gradle.kts`.
- Dependencies live in `gradle/libs.versions.toml` — always add/bump there, never inline coordinates.
- `wiki/` is the source of the GitHub Pages manual (`.github/workflows/pages.yml`); the GitHub wiki is not used — detailed user-facing docs go there, while `README.md` (Korean), `README.en.md` (English), and `README.ja.md` (Japanese) are separate files that describe the latest stable release. Their feature tables and release links are updated together, at stable promotion only.
- Agent guidance: keep this file to what every agent must follow, without dates or history. Path-specific rules, planner procedures and the reasons behind a decision go in [`docs/agents/`](docs/agents), following [`docs/agents/README.md`](docs/agents/README.md).
