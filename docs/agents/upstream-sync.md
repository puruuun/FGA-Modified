# Upstream sync

This repository is a fork of [Fate-Grand-Automata/FGA](https://github.com/Fate-Grand-Automata/FGA)
(default branch `master`). The fork keeps taking upstream updates while developing its own
features, so every sync follows the same rules instead of being decided again each time
(owner decision, 2026-10-24).

## Remote and cadence

- Remote: `git remote add upstream https://github.com/Fate-Grand-Automata/FGA.git` (once), then
  `git fetch upstream`.
- Upstream tags every published build as `v<versionCode>` (for example `v3065`). Check
  `git log --no-merges main..upstream/master` when upstream publishes a new tag, and before
  starting a fork release.

## How to sync

1. Branch `sync/upstream-v<code>` from the latest `origin/main`.
2. `git merge --no-ff upstream/master`. Always merge — never rebase or cherry-pick upstream
   commits — so upstream history and authorship stay intact and the next merge base is exact.
3. Resolve conflicts with the ownership table below. Title the merge commit
   `Merge upstream FGA master (<short sha>) into the fork`; the body lists what was taken and
   what was not, and why.
4. Fork follow-ups (Korean/Japanese translations of new upstream strings, adaptations of fork
   code) go in separate commits after the merge commit.
5. Deliver it like any code change: full unit suite, PR, `Main CI`, `--ff-only` merge
   (see `AGENTS.md`). Device QA on BlueStacks covers the upstream behaviour changes that touch
   KR/JP play.
6. Add a row to the sync log below (documentation-only, pushed directly to `main`).

## Ownership

| Area | Owner | On conflict |
| --- | --- | --- |
| App code, assets, `scripts/`, `libautomata/`, `prefs/` | Upstream, plus fork features | Merge both; keep fork behaviour, adopt upstream fixes |
| Gradle wrapper, `gradle/libs.versions.toml`, `build.gradle.kts` | Upstream | Take upstream, keep fork-only additions |
| English strings `values/localized.xml` | Upstream, plus fork strings | Merge both |
| Translations `values-ko`, `values-ja` (and other `values-*`) | **Fork** — the fork does not use POEditor | Keep fork text; translate new upstream strings in ko and ja yourself |
| `.github/workflows/` and `.github/workflows/actions.lock` | **Fork** | Keep the fork's set; do not take the lockfile, `ci.yml`, `close-issues.yml` or `upload-translations.yml` |
| `AGENTS.md`, `docs/agents/`, `.claude/`, `.github/instructions/` | **Fork** | Keep the fork's text; take upstream guides only when they apply to the fork (not the lockfile guide) |
| `version.properties`, `README*.md`, `wiki/`, `docs/design/`, `docs/releases/`, `docs/qa/`, `fastlane/` | **Fork** | Keep the fork's files |
| `renovate.json`, `Gemfile*` | Upstream | Take upstream |

When upstream changes something the fork has deleted (modify/delete conflict), keep it deleted
unless the table says otherwise.

## Keeping future syncs cheap

- Put fork features in new files or packages and keep edits to upstream files to the minimal
  hook.
- Do not reformat or reorder upstream code you are not changing.

## Sync log

| Date | Upstream | Fork PR | Taken | Not taken |
| --- | --- | --- | --- | --- |
| 2026-10-19 | `bf75acf0` (Gradle 9.8.1) | `011240c6` | NA servant enhancement fix, Gradle 9.8.1 | `ci.yml`, `close-issues.yml`, actions lockfile and pin changes |
| 2026-10-24 | `cfb0593c` (v3065) | [#13](https://github.com/puruuun/FGA-Modified/pull/13) (`2a3d8b99`) | Support screen `notFoundRegion` fix (v3063), server auto-detect error instead of NA (v3064), Gradle 9.8.2 (v3065), Renovate config | Actions lockfile and its guide, `upload-translations.yml` |
