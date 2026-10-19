# User manual site (GitHub Pages)

The user manual at <https://puruuun.github.io/FGA-Modified/> is built from `wiki/`, which is also the GitHub wiki source. It is built with MkDocs Material (`mkdocs.yml`) and deployed by `.github/workflows/pages.yml` on every push to `main` that touches `wiki/`, `mkdocs.yml` or `tools/docs-site/`. Nobody edits the site by hand.

## Every user-facing feature ships with its manual page

A change that adds or changes something a user sees (a setting, a script, a stop message, a workflow) is not done until its Korean manual page under `wiki/scripts/` is added or updated in the same change.

- New feature: add `wiki/scripts/<Feature-Name>-KR.md`. Its first line is `# <title>` and that title becomes the menu entry, so make it the feature name. A trailing version such as `— 1.4.0` or `(1.4.0-beta.10)` is stripped from the menu.
- The page appears under the **기능 설명서** tab automatically. Do not edit `mkdocs.yml` to list it.
- Write for users:
  - what the feature does;
  - where to turn it on (the screen and the setting names as they appear in the app);
  - what it will and will not do (safety limits);
  - the stop messages and what to do about each one.
- Release notes go to `wiki/releases/<version>-KR.md` and appear under **릴리스 노트**, newest first.
- English and Japanese pages keep their `<Name>.md` and `<Name>-JA.md` names. They are grouped under **English (원본 FGA)** and **日本語**.

## How the navigation is built

`tools/docs-site/hooks.py` builds the menu from the files at build time:
- **시작하기**: the fixed list `GETTING_STARTED` in the hook.
- **기능 설명서**: every other `wiki/scripts/*-KR.md`. Names containing `Study` or `Research` go to **참고 자료** instead.
- **릴리스 노트**: `wiki/releases/*.md`.
- **日本語**: `*-JA.md`.
- **English (원본 FGA)**: everything else.

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
