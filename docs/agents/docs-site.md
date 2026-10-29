# User manual site (GitHub Pages)

The user manual at <https://puruuun.github.io/FGA-Modified/> is built from `wiki/`, which is also the GitHub wiki source. It is built with MkDocs Material (`mkdocs.yml`) and deployed by `.github/workflows/pages.yml` on every push to `main` that touches `wiki/`, `mkdocs.yml` or `tools/docs-site/`. Nobody edits the site by hand.

## Every user-facing feature ships with its manual page and README entries

A change that adds or changes something a user sees (a setting, a script, a stop message, a workflow) is not done until its Korean manual page under `wiki/scripts/` and the feature tables in **both** `README.md` (Korean) and `README.en.md` (English) are added or updated in the same change.

- **Two README files**: `README.md` is the Korean main README; `README.en.md` is the English version. Both carry the same language switch line at the top.
  - Whenever a user-facing feature is added, renamed, or changed, update the Korean feature table (`## 추가·수정된 기능`) in `README.md` and the English feature table (`## Added and modified features`) in `README.en.md` so the two tables stay in 1:1 order.
  - Whenever a stable release is promoted, update the Korean latest-release summary line (`**정규판 <version>** — ... [변경 사항](wiki/releases/<version>-KR.md).`) in `README.md` and the English release notes link (`- [Release notes (Korean)](wiki/releases/<version>-KR.md)`) in `README.en.md`.
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
