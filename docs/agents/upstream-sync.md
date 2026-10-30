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
4. **Built-in Craft Essence curation check**: Never blindly adopt upstream CE additions, renames,
   or restorations of deleted CEs. Inspect
   `git diff main..upstream/master -- app/src/main/assets/Support/ce/ app/src/main/java/io/github/fate_grand_automata/util/SupportNameResources.kt`,
   look up any newly added or modified CEs on Atlas Academy (`collectionNo`, `id`, Korean
   translation, and whether MLB grants a 20% Bond Point bonus), present the candidate list to the
   owner, and only include/exclude/format them (plus regenerate `builtin-ids.json`) per the
   owner's decision.
5. Fork follow-ups (the owner's Korean/Japanese text for new or conflicting upstream strings,
   adaptations of fork code) go in separate commits after the merge commit.
6. Deliver it like any code change: full unit suite, PR, `Main CI`, `--ff-only` merge
   (see `AGENTS.md`). Device QA on BlueStacks covers the upstream behaviour changes that touch
   KR/JP play.
7. Add a row to the sync log below (documentation-only, pushed directly to `main`).

## Ownership

| Area | Owner | On conflict |
| --- | --- | --- |
| App code, assets, `scripts/`, `libautomata/`, `prefs/` | Upstream, plus fork features | Merge both; keep fork behaviour, adopt upstream fixes |
| Built-in Craft Essences (`app/src/main/assets/Support/ce/`, `SupportNameResources.ceNameResIds`, `ce_name_*` in `localized.xml`, `builtin-ids.json`) | **Fork (owner-curated)** | Do not automatically add, rename, or restore deleted CEs from upstream; inspect Atlas Academy (`collectionNo`, `id`, KR translation, 20% Bond bonus condition) and ask the owner before applying |
| Gradle wrapper, `gradle/libs.versions.toml`, `build.gradle.kts` | Upstream | Take upstream, keep fork-only additions |
| English strings `values/localized.xml` | Upstream, plus fork strings | Merge both |
| Translations `values-ko`, `values-ja` | **Owner** — the fork does not use POEditor | Take upstream's changes to strings the fork never touched; list every conflicting string (fork text and upstream text) for the owner to choose; the owner decides the ko/ja text of new upstream strings (propose drafts). See `translations.md` |
| Other translations (`values-b+zh+CN`, `values-vi`, …) | Upstream | Take upstream, conflicts included |
| `.github/workflows/` and `.github/workflows/actions.lock` | **Fork** | Keep the fork's set; do not take the lockfile, `ci.yml`, `close-issues.yml` or `upload-translations.yml` |
| `AGENTS.md`, `docs/agents/`, `.claude/`, `.github/instructions/` | **Fork** | Keep the fork's text; take upstream guides only when they apply to the fork (not the lockfile guide) |
| `version.properties`, `README.md`, `README.en.md`, `wiki/`, `docs/design/`, `docs/releases/`, `docs/qa/` | **Fork** | Keep the fork's files (`README.md` is Korean and `README.en.md` is English; keep both) |
| `renovate.json` | Upstream | Take upstream |
| Removed upstream leftovers: `fastlane/`, `Gemfile`, `Gemfile.lock`, `FUNDING.yml`, `.github/ISSUE_TEMPLATE/`, `.github/workflows/publish-wiki.yml` | **Fork (deleted)** | Keep them deleted (owner, 2026-10-30). The fork does not publish to the Play Store or the GitHub wiki, does not take donations for upstream, and files issues through the planner |
| Removed upstream wiki pages: `wiki/Home.md`, `_Sidebar.md`, `_Footer.md`, `Donations.md`, `Privacy-Policy.md`, `Troubleshooting.md`, `Running-on-Emulators.md`, `Game-Area-detection.md`, `Fine-tune.md`, `Friend-Point-Summon.md`, `Gift-Box.md`, `wiki/scripts/_Sidebar.md`, `Battle.md`, `Lottery.md`, `Support-Image-Maker.md`, `wiki/img/` | **Fork (deleted)** | Keep them deleted (owner, 2026-10-30). `wiki/` holds only the fork's manual; readers who need the original guides use the upstream wiki linked from the README |

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
