---
name: server-port
description: Port FGA to a server nobody plays (NA first) from the KR and JP work - which templates carry over, which can be redrawn from the new server's APK, which still need a screenshot, and which code branches need a decision. Use when a server becomes a target ("port NA", "what is missing for NA", "which templates can I make from the APK", "list the server branches").
---

# Porting a server from KR and JP

The owner plays KR and JP; NA is ported without playing it
([`docs/agents/servers-and-assets.md`](../../../docs/agents/servers-and-assets.md), "NA: ported, not
played"). Screen logic and layout are the same on every server; language, fonts and some art differ.
This skill turns that into a list: per `Images` entry, what to do, and per code branch, what to
decide. It builds on `apk-template-asset` (APK extraction, sprites, drafts, scoring) and
`extract-template-asset` (the final check); read both first.

Needs Python 3, Pillow, numpy and opencv-python-headless. On Windows run the scripts with
`py -3 -E -P script.py ...`. Do not install anything yourself; report a missing package.

## Files

| File | What it does |
|---|---|
| `make_recipes.py` | For each template of KR or JP: how it can be drawn again from another APK. Writes `recipes/<Source>.csv`. |
| `recipes/Kr.csv`, `recipes/Jp.csv` | The recipes, committed: names and numbers only. |
| `overrides.csv` | Recipes a script cannot find: layered templates, font rows with a localization key, Atlas Academy banner ids per server. Replayed and measured by `make_recipes.py`, so a row is never trusted unmeasured. |
| `port_server.py` | Replays the recipes on the target's APK, scores the drafts, writes `report.csv` and `report.md`. |
| `server_branches.py` | Lists every `GameServer.Kr` / `GameServer.Jp` / `is GameServer` use with an empty `decision` column. |
| `recipe_lib.py` | Shared code; the pixel work is `apk-template-asset`'s. |

## Recipe kinds

| Kind | Meaning | Port action |
|---|---|---|
| `language-free` | The art is the same on every server. Needs a template match: the KR and JP copies score 0.95 or more against each other, or the `En` copy scores 0.95 or more on source screenshots. Vetoed whenever two servers' copies score below 0.80 against each other (see "Language-free veto"). | Copy the KR or JP file; no draft. |
| `sprite` | One atlas sprite, a scale (1.25 unless another wins by 0.02), edge insets from `--crop-like`. | Replay on the target APK. |
| `layers` | Several sprites with offsets. | Replay. |
| `font` | A localization string in the game font; stored by its key, with size, stroke and color. | Replay with the target's text for that key. |
| `atlas` | An Atlas Academy banner at x1.25; the banner id differs by server. | Replay with `--atlas-dir` of the target's banners. |
| `manual` | Described in `overrides.csv`. | Read the note. |
| `screenshot` | None of the above holds; the best score found and why are in `evidence`. | Needs a screenshot of the target server. |

`quality` is `good` when the draft reaches 0.80 on the source screenshots (its minimum over the
screens where the existing template is a positive at 0.85 or more), or, with no positive, at its worst
half-pixel shift; otherwise `weak`. A `weak` recipe is replayed and listed apart: judge it by a screenshot.
A `language-free` row keeps its sprite columns when a sprite was found, for reference.

## Language-free veto and downloaded atlases

An APK atlas can be replaced at run time by a downloaded localized one (the KR APK holds the JP
originals of several atlases, and KR downloads `_Data/Scenes/{Battle,Combine,Common,EventGacha,MyRoom,
PartyOrganization,Shop,Summon,Title}/Atlas` replacements; the list is the server's `asset_storage.json`).
So two APKs holding the same sprite does not prove that the screens look the same:

- **Veto.** When copies of a template exist on two servers and score below 0.80 against each other, the
  template is not language-free, whatever the sprites say. Sprite identity across APKs (same size,
  correlation 0.98 or more) is supporting evidence only, never alone: `make_recipes.py` needs a
  matching copy or `En` copy as well.
- **Contradictions** (copies disagree or differ in size, sprites agree) are written to the recipe's
  `notes` as `CONTRADICTION: ...` and listed in `report.md`. Treat each as a sign that the APK atlas
  is not what the screen shows; the sprite recipe of such a template is a guess until a screenshot of
  the target confirms it.
- **Download caveat.** `port_server.py --asset-storage` takes the target's `asset_storage.json`; a
  sprite or layers recipe whose atlas (or whose scene's `Atlas`) is in that list is classed
  `drafted-download-risk`: the atlas may be replaced by a download on the target. Without the list the
  flag says "unknown". NA's list holds no `_Data/Scenes/*/Atlas` entry (only `UISkin*`,
  `DownloadClassBoard*`, `EventRewardAtlas` and a system file `AtlasOverrideData`), so no NA draft
  is flagged: the list does not show a replacement, and does not prove there is none.

## Procedure

1. **Extract the target APK** with `apk-template-asset/extract_apk_ui.py`, and unpack nothing into the
   repository. The KR, JP and target extractions all stay in a scratch folder (the session scratchpad or
   `build/`).
2. **Recipes from KR and JP** (redo only when a template, an override or a client build changes):

   ```bash
   py -3 -E -P .claude/skills/server-port/make_recipes.py --source Kr --extracted build/apk-kr \
       --templates app/src/main/assets/Kr --screens docs/reference-images/Kr \
       --other-templates Jp=app/src/main/assets/Jp --other-extracted NA=build/apk-na --other-extracted Jp=build/apk-jp \
       --en-templates app/src/main/assets/En --atlas-dir build/atlas-banners-KR
   py -3 -E -P .claude/skills/server-port/make_recipes.py --source Jp --extracted build/apk-jp \
       --templates app/src/main/assets/Jp --screens docs/reference-images/Jp \
       --other-templates Kr=app/src/main/assets/Kr --other-extracted NA=build/apk-na --other-extracted Kr=build/apk-kr \
       --extra-extracted Kr=build/apk-kr --en-templates app/src/main/assets/En --atlas-dir build/atlas-banners-JP
   ```

   The Jp run takes sprites of the KR APK for atlases the JP APK lacks (`ResultAtlas`, the JP
   originals); `sprite_apk` says which APK a sprite was measured in. Atlas rows need the Atlas Academy
   banner folders written by the planner's `atlas_banners.py` (downloaded with the owner's approval,
   never committed); without `--atlas-dir` they are recorded as `screenshot`.
3. **Port run**:

   ```bash
   py -3 -E -P .claude/skills/server-port/port_server.py --target En --extracted build/apk-na \
       --recipes .claude/skills/server-port/recipes/Kr.csv .claude/skills/server-port/recipes/Jp.csv \
       --screens docs/reference-images/En --atlas-dir build/atlas-banners-NA        --asset-storage build/atlas-export/NA/asset_storage.json        --source-extracted Kr=build/apk-kr --source-extracted Jp=build/apk-jp --out build/port-na
   ```

   Classes of a missing entry (the best of its sources counts): `language-free`, `drafted`,
   `drafted-weak`, `drafted-size-flag`, `drafted-download-risk`, `drafted-ambiguous-key`,
   `atlas-unverified` (a banner id that is not verified on the target, `atlas_unverified` in
   `overrides.csv`) and `screenshot-only`.

   `report.md` has the counts per class and, per script file that uses the image, each entry's class,
   source, kind and scores. `drafts/` holds the replayed drafts (`<name>.<Source>.png`),
   `language_free/` the proposed copies.
4. **Review the drafts.** Flagged ones (`size_flag`: the target's sprite is more than 2 px off the
   source's, a localized label) may need another scale or insets: `layout_calc.py` shows the widget, and
   `draft_template.py --scale X,Y` drafts a stretched sprite; a screenshot decides. A draft's score on the
   source template is for shape only (the art differs by language).
5. **Language-free copies**: the file goes to `app/src/main/assets/En/` only if the template is not
   already served by the `En` fallback logic, and after step 8.
6. **Code branches**: `server_branches.py --out-csv build/branches.csv --out-md build/branches.md`.
   It lists 72 lines (50 behaviour lines in 31 files, the rest only declare or label servers); under `scripts/` alone 39 lines in 26 files. Fill the `decision` column per row: the new server takes the KR, the JP or the default behaviour,
   including OCR settings. A row that only declares or labels servers (`declaration`) needs none.
7. **Screenshots for the rest** (screenshot-only entries, weak or flagged drafts): the owner's, or public
   ones (wiki, Reddit, X, video frames at 1080p or better). Downloading needs the owner's approval, and they
   stay out of the repository; only a finished template (and an account-free crop that a QA case uses) is
   committed.
8. **Verify each template** before it goes into `app/src/main/assets/<Server>/`: cut or check it with
   `extract-template-asset` against a real screenshot of that server, and score with `score_screens.py`
   (positives, the call's similarity, the highest false score). A draft nobody has seen on a screenshot is
   not committed as verified.
9. **The PR** says which parts were checked on screenshots only and which are drafts from the APK, with
   the counts of the port report.

## Overrides

`overrides.csv` columns: `source` (`Kr`, `Jp` or `*`), `template`, `kind` (`sprites`-based rows leave it
empty or `sprite`/`layers`; `font`, `atlas`, `manual`), `sprite_apk`, `sprites` (`Atlas/name`, bottom
layer first, separated by `;`), `scale`, `font`, `font_size`, `font_stroke`, `font_color`,
`localization_key`, `atlas` (`Kr=Banner/x.png;Jp=Banner/y.png;En=Banner/z.png`) and `notes`. The
offsets and insets are found by `make_recipes.py` against the existing template.

A font draft is `drafted-ambiguous-key` when the source text maps to keys with more than one distinct
target text (`report.md` lists the candidates with their target texts), and the KR and JP recipes of a
template may pick different keys: the report says so, and at least one of them is wrong. A font row is a fallback: it is used only when no sprite reaches 0.85 against the template, and kept only when the rendered text reaches 0.80 at the worst shift (otherwise the template is `screenshot`). It names the widget path (the key) of the string in `LocalizationJpn.txt`, not its text: the
same key holds the target's text in the target APK. Several keys can hold the same text; the row keeps
the one that exists in all three APKs and whose NA text is the most common among them, and `notes` says
how many candidates there were.

## What a port result means

- A `language-free` copy is evidence from KR and JP; it is not a view of the target server. List it in
  the PR as checked on other servers' screenshots.
- A drafted template is the APK's art at the measured scale. Where the source and target sprite sizes
  agree the draft is as good as the source's recipe; where they differ (flagged) it is a guess.
- A `font` draft is the right glyph shapes at the recorded size. A different text length changes the
  box, so such a template is judged by a screenshot first.
- Counts in the report are per `Images` entry. Several entries can share one file.

## Copyright

The APK, fonts, strings, textures, sprites and Atlas Academy images are copyrighted. They stay outside
the repository (the session scratchpad or `build/`); never commit them, a draft that is not a finished and
verified template, a port output folder, or an extraction folder. The recipe files hold names and numbers
only: sprite names, insets, sizes, scores, localization keys. Only a finished, verified template goes into
`app/src/main/assets/`.
