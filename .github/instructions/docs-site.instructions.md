---
applyTo: "README.md,README.en.md,README.ja.md,wiki/**,mkdocs.yml,tools/docs-site/**,app/src/main/res/values/localized.xml,scripts/src/main/java/**/entrypoints/**"
description: The user manual site built from wiki/, the Korean README.md, English README.en.md, and Japanese README.ja.md, and the rule that all three follow stable releases only.
---

User-facing features must add or update their Korean manual page in `wiki/scripts/`.

Feature and beta changes never edit `README.md`, `README.en.md`, or `README.ja.md`. When a stable release is published, the planner updates all three and the GitHub Pages manual is republished from the stable tag.

Read [`docs/agents/docs-site.md`](../../docs/agents/docs-site.md).
