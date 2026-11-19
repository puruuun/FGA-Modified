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
     py -3 -E -P .claude/skills/apk-template-asset/draft_template.py --extracted build/apk-kr \
         --sprite BattleUIAtlas/buttontxt_retire --check app/src/main/assets/Kr/retreat_button.png \
         --out build/draft/retreat_button.png
     ```

     `--scale` defaults to 1.25 and `--background` to the median of the first `--check`. Use
     `--crop x,y,w,h` (720p pixels after scaling) to keep only pixels that are UI: the sprite's
     transparent margin is flattened on the background color and would be noise in a template.
   - **Font rendering** for plain text that is not in an atlas. Take the text from the string
     table (`text/LocalizationXxx.txt`) and the color from its `[RRGGBB]` markup, if any.

     ```bash
     py -3 -E -P .claude/skills/apk-template-asset/draft_template.py \
         --font build/apk-kr/fonts/FGO-Main-Font.otf --text "닫기" --color FFFFFF --fit \
         --check app/src/main/assets/Kr/close.png
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

5. **Verify before committing.** A draft is only a start. Check it against a real screenshot of
   that server (ask the owner for one, or for a QA capture) before it goes into
   `app/src/main/assets/<Server>/`, and run the usual `extract-template-asset` verification.

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

Sprite matches against `app/src/main/assets/Kr`, all at scale 1.25 (`find_sprite.py`, no shift):

| Template | Sprite | Score |
|---|---|---|
| `retreat_button` | `BattleUIAtlas/buttontxt_retire` | 0.954 |
| `retreat_confirm` | `CommonUIAtlas/buttontxt_decide` | 0.911 |
| `skill_level_label` | `CombineAtlas/img_txt_skillitem` | 0.961 |
| `skill_level_reselect` | `CombineAtlas/btn_txt_servant_reselect` | 0.927 |
| `ce_enhance_reselect` | `CombineAtlas/btn_txt_synthesis_reselect` | 0.913 |
| `support_region_tool` | `PartyOrganizationAtlas/btn_supportform` | 0.942 |
| `weekly_notice` | `CommonUIAtlas/img_return_info` | 0.969 |
| `weekly_terminal` | `CommonUIAtlas/img_return_terminal` | 0.967 |

Font rendering with `--fit`, worst of the four shifts: `close` (닫기) 0.935, `retry` (다시 시도)
0.933, `execute` (실행) 0.903, `withdraw` (철수하기) 0.873. Sprite mode `retreat_button` scores
0.954 / 0.895 / 0.900 / 0.844 over the four shifts: a scaled sprite is sharper than a screenshot,
so its worst shift is lower than the unshifted score.

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
