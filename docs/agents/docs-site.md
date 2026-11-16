# User manual site (GitHub Pages)

The user manual at <https://puruuun.github.io/FGA-Modified/> is built from `wiki/`. The GitHub wiki is not used, and its upstream publish workflow was removed (owner, 2026-10-30). It is built with MkDocs Material (`mkdocs.yml`) and deployed by `.github/workflows/pages.yml` **only when a stable GitHub Release is published** (owner, 2026-10-29). The workflow builds the site from that stable tag. A manual run of the workflow (no tag) rebuilds the site from the latest stable tag. Beta work on `main` therefore never reaches the published manual. Nobody edits the site by hand.

## Every user-facing feature ships with its manual page; the README follows stable releases

A change that adds or changes something a user sees (a setting, a script, a stop message, a workflow) is not done until its Korean manual page under `wiki/scripts/` is added or updated in the same change. The page sits on `main` and goes live with the next stable release.

- **Three README files.** `README.md` is the Korean main README, `README.en.md` is the English version, and `README.ja.md` is the Japanese version. All three carry the same language switch line at the top.
- **All three describe the latest stable release only** (owner, 2026-10-29). **Feature and beta changes never edit any README.**
- The planner updates all three once, when a stable release is promoted (see `docs/agents/releases-and-versioning.md`):
  - **Feature tables:** the Korean `## 추가·수정된 기능`, English `## Added and modified features`, and Japanese `## 追加・修正された機能`, kept 1:1 in order. Take the rows from `wiki/releases/<version>-KR.md` and `git diff v<last-stable>..HEAD -- wiki/scripts`.
  - **Release line and link:** the Korean latest-release line (`**정규판 <version>** — ... [변경 사항](wiki/releases/<version>-KR.md).`), the English release-notes link (`- [Release notes (Korean)](wiki/releases/<version>-KR.md)`), and the Japanese latest-release line (`**正式版 <version>** — ... [変更内容（韓国語）](wiki/releases/<version>-KR.md)`).
- **Manual page (`wiki/scripts/`)**:
  - New feature: add `wiki/scripts/<Feature-Name>-KR.md` (and `-EN.md` / `-JA.md`, or translate them before/at the next stable release; missing translations automatically fall back to `-KR.md` with a notice banner). Its first line is `# <title>` and that title becomes the menu entry, so make it the feature name. A trailing version such as `— 1.4.0` or `(1.4.0-beta.10)` is stripped from the menu.
  - The page appears under the **기능 설명서** (**Guides** / **機能ガイド**) tab automatically. Do not edit `mkdocs.yml` to list it (you can optionally assign its category key in `GUIDE_GROUPS` inside `tools/docs-site/hooks.py`; otherwise it appears under `화면 · 기타 기능`).
  - Write for users:
    - what the feature does;
    - where to turn it on (the screen and the setting names as they appear in the app);
    - what it will and will not do (safety limits);
    - the stop messages and what to do about each one.
- Release notes go to `wiki/releases/<version>-KR.md` (plus `-EN.md` / `-JA.md`) and appear under **릴리스 노트** (**Release Notes** / **リリースノート**), newest first.

## Multi-language build and navigation

The site publishes Korean at `/` (`-KR.md`), English at `/en/` (`-EN.md`), and Japanese at `/ja/` (`-JA.md`), switched via the header language dropdown (`extra.alternate`).

`tools/docs-site/hooks.py` builds the menu for each language from the files at build time:
- **시작하기 / Getting Started / はじめに**: the fixed list `GETTING_STARTED` in the hook (`Installation` and `Original-FGA-Import`, keeping feature guides separate).
- **기능 설명서 / Guides / 機能ガイド**: categorized into sidebar sections via `GUIDE_GROUPS` (`전투 · 카드`, `서포트 선택`, `소환 · 강화 · 주회`, `로그 · Chaldea 연동`, `화면 · 기타 기능`). Unlisted `scripts/*` pages automatically fall into the last category. Names containing `Study` or `Research` are research notes and are left out of navigation and search.
- **릴리스 노트 / Release Notes / リリースノート**: `wiki/releases/*`.

Each language build keeps only that language's active files (or `-KR.md` fallback) so search indexes remain isolated per language. When building the default Korean site (`mkdocs build --strict`), `on_post_build` automatically runs `--strict` sub-builds for `/en/` and `/ja/`.

The hook also makes GitHub-wiki links work on the site:
- links written as a bare page name (`[x](Troubleshooting)`) or cross-language suffix (`*-KR.md`) resolve to the active language's page;
- `img/...` paths resolve from the wiki root;
- links that leave `wiki/` (for example `../../README.md`) point to the file on GitHub.

The landing pages are `tools/docs-site/index.md` (Korean), `index.en.md` (English), and `index.ja.md` (Japanese), so the wiki itself gets no extra page.

## Checking a change locally

```bash
pip install -r tools/docs-site/requirements.txt
mkdocs build --strict
```

`--strict` turns broken links into errors; the workflow uses it too. The output goes to `build/site/`, which is git-ignored.
