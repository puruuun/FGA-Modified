# User manual site (GitHub Pages)

The user manual at <https://puruuun.github.io/FGA-Modified/> is built from `wiki/`, which is also the GitHub wiki source. It is built with MkDocs Material (`mkdocs.yml`) and deployed by `.github/workflows/pages.yml` **only when a stable GitHub Release is published** (owner, 2026-10-29). The workflow builds the site from that stable tag. A manual run of the workflow (no tag) rebuilds the site from the latest stable tag. Beta work on `main` therefore never reaches the published manual. Nobody edits the site by hand.

## Every user-facing feature ships with its manual page; the README follows stable releases

A change that adds or changes something a user sees (a setting, a script, a stop message, a workflow) is not done until its Korean manual page under `wiki/scripts/` is added or updated in the same change. The page sits on `main` and goes live with the next stable release.

- **Two README files.** `README.md` is the Korean main README; `README.en.md` is the English version. Both carry the same language switch line at the top.
- **Both describe the latest stable release only** (owner, 2026-10-29). **Feature and beta changes never edit either README.**
- The planner updates both once, when a stable release is promoted (see `docs/agents/releases-and-versioning.md`):
  - **Feature tables:** the Korean `## 추가·수정된 기능` and the English `## Added and modified features`, kept 1:1 in order. Take the rows from `wiki/releases/<version>-KR.md` and `git diff v<last-stable>..HEAD -- wiki/scripts`.
  - **Release line and link:** the Korean latest-release line (`**정규판 <version>** — ... [변경 사항](wiki/releases/<version>-KR.md).`) and the English release-notes link (`- [Release notes (Korean)](wiki/releases/<version>-KR.md)`).
- **Manual page (`wiki/scripts/`)**:
  - New feature: add `wiki/scripts/<Feature-Name>-KR.md` (and link/summarize it in `wiki/scripts/Modified-Features-KR.md` if applicable). Its first line is `# <title>` and that title becomes the menu entry, so make it the feature name. A trailing version such as `— 1.4.0` or `(1.4.0-beta.10)` is stripped from the menu.
  - The page appears under the **기능 설명서** tab automatically. Do not edit `mkdocs.yml` to list it.
  - Write for users:
    - what the feature does;
    - where to turn it on (the screen and the setting names as they appear in the app);
    - what it will and will not do (safety limits);
    - the stop messages and what to do about each one.
- Release notes go to `wiki/releases/<version>-KR.md` and appear under **릴리스 노트**, newest first.
- English pages (`<Name>.md`, mostly original FGA docs) stay in the wiki but are left out of the site menu and search.

## How the navigation is built

`tools/docs-site/hooks.py` builds the menu from the files at build time:
- **시작하기**: the fixed list `GETTING_STARTED` in the hook.
- **기능 설명서**: every other `wiki/scripts/*-KR.md`. Names containing `Study` or `Research` are research notes and are left out.
- **릴리스 노트**: `wiki/releases/*.md`.
- Everything else (English / original FGA pages, research notes) is built so links keep working, but is not in the menu or search.

The hook also makes GitHub-wiki links work on the site:
- links written as a bare page name (`[x](Troubleshooting)`) resolve to that page;
- `img/...` paths resolve from the wiki root;
- links that leave `wiki/` (for example `../../README.md`) point to the file on GitHub.

The landing page is `tools/docs-site/index.md`, so the wiki itself gets no extra page.

## Checking a change locally

```bash
pip install -r tools/docs-site/requirements.txt
mkdocs build --strict
```

`--strict` turns broken links into errors; the workflow uses it too. The output goes to `build/site/`, which is git-ignored.
