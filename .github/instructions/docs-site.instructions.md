---
applyTo: "README.md,wiki/**,mkdocs.yml,tools/docs-site/**,app/src/main/res/values/localized.xml,scripts/src/main/java/**/entrypoints/**"
description: The user manual site built from wiki/, the single bilingual README.md (English top, Korean bottom), and the rule that user-facing features update both.
---

User-facing features must add or update their Korean manual page in `wiki/scripts/` and update both the
English (top) and Korean (bottom) feature tables in `README.md`; the GitHub Pages site picks up
`wiki/scripts/` automatically.

Read [`docs/agents/docs-site.md`](../../docs/agents/docs-site.md).
