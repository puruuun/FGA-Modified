---
name: apk-template-asset
description: Draft a 720p template image for app/src/main/assets from a server's game APK (UI atlas sprites or the game font), for a server and screen you have no screenshot of. Use when a template is missing for a server (NA, CN, TW, or a KR/JP screen the owner has not captured) - "make the TW asset without playing", "does the APK have this button", "draft a template from the APK". If a screenshot exists, use extract-template-asset instead.
---

# Drafting a template asset from the game's APK

`extract-template-asset` cuts a template from a screenshot. This skill is for when there is
**no screenshot** of that server and screen: the APK of that server's game holds the UI font, the
string tables and the NGUI atlases (the real button and label art), enough to draft many
templates without playing. Read [`docs/agents/coordinates.md`](../../../docs/agents/coordinates.md)
and [`docs/agents/servers-and-assets.md`](../../../docs/agents/servers-and-assets.md) first; the
Region, the 720p size and the `En` fallback rules still apply.

Needs Python 3, Pillow, numpy, opencv-python-headless and UnityPy. On Windows run the scripts with
`py -3 -E -P script.py ...` so nothing from the current folder is imported. Do not install
anything yourself; report a missing package.

## Getting the APK

The owner supplies the `.apk` or `.xapk` (or pulls it read-only from an app player the owner set
up; never from a device or account an agent operates). An XAPK is a zip of APKs: the script finds
the one that holds `assets/bin/Data/` and stops with a message if none does. Never write into the
folder the package came from.

## Procedure

1. **Extract** into a scratch folder outside the repository (the session scratchpad or `build/`):

   ```bash
   py -3 -E -P .claude/skills/apk-template-asset/extract_apk_ui.py kr.apk --out-dir build/apk-kr
   ```

   It writes `fonts/`, `text/` (the `Localization*` tables), `textures/` (atlases with their
   `_alpha` texture merged in), `sprites/<Atlas>/<sprite>.png` and `index.json`. It prints each
   font's family and style, and the counts to compare with the reference table below.

2. **Find a sprite** the screen could use. For a server whose templates already exist elsewhere
   (KR sprites against the KR folder) `find_sprite.py` shows which sprite each template was cut from:

   ```bash
   py -3 -E -P .claude/skills/apk-template-asset/find_sprite.py --extracted build/apk-kr \
       --templates app/src/main/assets/Kr --names retreat_button.png --out-csv build/match-kr.csv
   ```

   For a missing template, search the sprite names (`index.json`, or `build/apk-xx/sprites/`
   by eye) and the string table: the sprite names are the same on every server
   (`BattleUIAtlas/buttontxt_retire` is the retreat label), only the art in them is localized.
   Use another server's template of the same name as the `--check` to see whether a sprite fits.

3. **Draft** it, in one of two modes:

   - **Atlas sprite first.** The sprite is the real art, so bold, outlined, glowing or colored
     labels only work this way.

     ```bash
     py -3 -E -P .claude/skills/apk-template-asset/draft_template.py --extracted build/apk-kr          --sprite BattleUIAtlas/buttontxt_retire --check app/src/main/assets/Kr/retreat_button.png          --out build/draft/retreat_button.png
     ```

     The sprite is scaled like a device screenshot: bilinear to a `--device-height` screen
     (default 1080, so x1.875), then `INTER_AREA` down to 720p (`--scale` 1.25 overall). One
     `INTER_AREA` enlargement acts like nearest neighbour and scores 0.01-0.03 lower;
     `--device-height 720` gives that old behaviour. `--background` defaults to the median of the
     first `--check` (or of the `--crop-like` / `--layers-like` template).
     `--crop x,y,w,h` (720p pixels after scaling) keeps a box of the draft: the sprite's
     transparent margin is flattened on the background color and would be noise in a template.
   - **Font rendering** for plain text that is not in an atlas. Take the text from the string
     table (`text/LocalizationXxx.txt`) and the color from its `[RRGGBB]` markup, if any.

     ```bash
     py -3 -E -P .claude/skills/apk-template-asset/draft_template.py          --font build/apk-kr/fonts/FGO-Main-Font.otf --text "닫기" --color FFFFFF --fit          --check app/src/main/assets/Kr/close.png
     ```

     `--fit` picks the font size by the best worst-shift score on the first check; `--stroke` adds
     the dark outline (usually it does not help: outlined labels are atlas cases). Without `--color`
     the ink color is estimated from the check.

4. **Score** it with `--check`: against an existing template or 720p crop (the smaller image is
   searched inside the larger), or against a full screenshot's search region with
   `--region X,Y,W,H --anchor ... [--anchor-y ...]`, resolved by `extract-template-asset`'s
   `crop_template.py` so both skills compare the same pixels. Four scores are printed, at
   half-pixel shifts, because a real screenshot lands on an arbitrary sub-pixel offset. The
   app's threshold is on the best match, so judge by the worst shift. `--check` can be repeated:
   include screenshots that must not match too.

   When screenshots of the server exist, `score_screens.py` scores whole folders of drafts the
   way FGA matches (game area, 720p, grayscale, best score over the screenshot):

   ```bash
   py -3 -E -P .claude/skills/apk-template-asset/score_screens.py --templates app/src/main/assets/Kr        --drafts build/drafts --screens docs/reference-images/Kr --out-csv build/score.csv
   ```

   Per template it prints the positives (screenshots where the existing template scores 0.85 or
   more), the existing minimum, the draft's minimum and mean on them, and the highest draft score
   on a screenshot without the label, with its name. FGA searches only a Region, so the false
   scores are an upper bound, and each call has its own similarity threshold, often not the 0.80
   default: compare with the threshold of the call that uses the template.

5. **Verify before committing.** A draft is only a start. Check it against a real screenshot of
   that server (ask the owner for one, or for a QA capture) before it goes into
   `app/src/main/assets/<Server>/`, and run the usual `extract-template-asset` verification.

## Another server: crop and layers that carry over

Sprite names are the same on every server but the art inside differs by language, so a crop box in
pixels (or one found by matching the text) does not transfer. Edge insets and layer offsets do.

a. **On KR or JP, where the template exists**, draft from the sprite and derive the numbers:

   ```bash
   py -3 -E -P .claude/skills/apk-template-asset/draft_template.py --extracted build/apk-kr        --sprite PartyOrganizationAtlas/btn_supportform        --crop-like app/src/main/assets/Kr/support_region_tool.png
   # crop-like support_region_tool.png: score 0.948, insets 21,12,20,10  (--inset 21,12,20,10)
   ```

   `--crop-like` finds the template inside the draft, prints the match score and the insets
   `left,top,right,bottom` in 720p pixels and crops to the template's box. A dimension where the
   template is larger than the draft is reported and not cropped. For a layered template repeat
   `--sprite` (bottom layer first) and add `--layers-like` with the same template: each layer is
   found in it and the offsets from the first layer are printed as `--offset dx,dy`.
   Which sprites make up a layered template is found with `find_sprite.py` (a template's best
   sprite is the label) and by matching the template against larger sprites: `weekly_notice`
   is `CommonUIAtlas/icon_return_bg` (the arrow and the strip behind it, found as a 172x81 sprite
   that holds the template's left edge at 0.978) under `img_return_info`; `skill_level_reselect`
   is `CombineAtlas/button_reselect` under `btn_txt_servant_reselect`.
b. **On the target server**, apply the same sprite names with the printed numbers:

   ```bash
   py -3 -E -P .claude/skills/apk-template-asset/draft_template.py --extracted build/apk-tw        --sprite PartyOrganizationAtlas/btn_supportform --inset 21,12,20,10 --background RRGGBB        --out build/draft/support_region_tool.png
   ```

   Layers use `--sprite A --sprite B --offset dx,dy [--inset l,t,r,b]`. The same insets give the
   same image as `--crop-like` did on KR (checked byte for byte on `support_region_tool`). Give
   `--background` from a screenshot of that server or from the KR/JP template's color; its art may
   differ in language but a button background rarely does.
c. **Score**: with `score_screens.py` when screenshots of the target server exist; otherwise
   `--check` against the KR/JP template only to see that the shape fits (the art differs, so a
   high score is not expected).
d. **A real screenshot of the target server** before committing, as in step 5.

## What to expect

- **Scale 1.25** takes an NGUI atlas sprite to 720p. Measured on KR 8.1.0, every matched sprite's
  best scale was 1.25. Another scale winning is a sign of a false match.
- Font rendering reaches 0.80 or more at the worst shift for plain labels; bold, outlined or
  glowing ones fail.
- A rendered label scored at most 0.49 inside another template; sprites at most 0.42 on KR
  except where two templates share art (`weekly_notice` scores 0.76 inside `weekly_close`).

### Reference, KR 8.1.0 (`com.netmarble.fgok`)

Extraction: `FGO-Main-Font.otf` (family `RixVita L`), `LocalizationJpn` 5,070 lines, 290 Texture2D
objects of which 288 decode, 233 merged textures, 68 atlases with 2,535 sprites (2 of 70 atlases
list sprites but have no texture in the APK).

Sprite matches against `app/src/main/assets/Kr`, all at scale 1.25 (`find_sprite.py`, no shift,
default device-like scaling; the single-`INTER_AREA` score with `--device-height 720` in brackets):

| Template | Sprite | Score |
|---|---|---|
| `retreat_button` | `BattleUIAtlas/buttontxt_retire` | 0.972 (0.954) |
| `retreat_confirm` | `CommonUIAtlas/buttontxt_decide` | 0.928 (0.911) |
| `skill_level_label` | `CombineAtlas/img_txt_skillitem` | 0.968 (0.961) |
| `skill_level_reselect` | `CombineAtlas/btn_txt_servant_reselect` (+ `CombineAtlas/button_reselect` below) | 0.952 (0.927) |
| `ce_enhance_reselect` | `CombineAtlas/btn_txt_synthesis_reselect` | 0.945 (0.913) |
| `support_region_tool` | `PartyOrganizationAtlas/btn_supportform` | 0.948 (0.942) |
| `weekly_notice` | `CommonUIAtlas/img_return_info` (+ `CommonUIAtlas/icon_return_bg` below) | 0.978 (0.969) |
| `weekly_terminal` | `CommonUIAtlas/img_return_terminal` | 0.975 (0.967) |

Drafts on the 270 screenshots of `docs/reference-images/Kr` (`score_screens.py`, positives =
existing template at 0.85 or more; drafts made with `--crop-like` against the KR template, and
layers for the two layered ones):

| Template | Positives | Existing min | Draft min | Draft mean | Highest false draft score |
|---|---|---|---|---|---|
| `skill_level_label` | 8 | 0.948 | 0.924 | 0.959 | 0.764 (`wide-2560/41-skill-9to10.webp`) |
| `skill_level_reselect` (2 layers) | 63 | 0.933 | 0.894 | 0.948 | 0.492 |
| `ce_enhance_reselect` | 7 | 0.905 | 0.897 | 0.924 | 0.534 |
| `support_region_tool` | 2 | 0.861 | 0.931 | 0.939 | 0.354 |
| `weekly_notice` (2 layers) | 48 | 0.851 | 0.836 | 0.866 | 0.588 |
| `weekly_terminal` | 5 | 0.969 | 0.966 | 0.972 | 0.489 |

`weekly_notice` stays under its template: the arrow strip `icon_return_bg` is drawn
semi-transparent over a screen that changes behind it, so the flattened background is not exact.
`retreat_button` and `retreat_confirm` have no positive in the KR screenshots.

Font rendering with `--fit`, worst of the four shifts: `close` (닫기) 0.935, `retry` (다시 시도)
0.933, `execute` (실행) 0.903, `withdraw` (철수하기) 0.873. Sprite mode `retreat_button` scores
0.972 / 0.909 / 0.914 / 0.852 over the four shifts (0.954 / 0.895 / 0.900 / 0.844 with
`--device-height 720`): a scaled sprite is sharper than a screenshot, so its worst shift is lower
than the unshifted score.

## Limits

- **Downloaded game data is encrypted** and not used. Some APK atlases are the JP originals that
  the game replaces with downloaded localized ones (KR `ResultAtlas` is Japanese), so result and
  reward screens still need a screenshot.
- UnityPy cannot decode a few textures (2 on KR 8.1.0); those atlases are skipped or listed
  without art.
- The atlas rects come from a byte scan of the il2cpp build's MonoBehaviours (no type tree).
  If a new client build changes the layout the atlas count drops to zero: compare it with the
  table above.

## Copyright

The APK, fonts, strings, textures and sprites are copyrighted. They stay outside the repository
(the session scratchpad or `build/`); never commit them, a crop of them that is not a finished
template, or the extraction folder. Only a finished, verified template goes into
`app/src/main/assets/`.
