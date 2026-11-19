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

   **Layout: where it is drawn and at what scale.** The APK's scenes hold every widget that uses
   the sprite, so the screen position and the draft scale need no screenshot:

   ```bash
   py -3 -E -P .claude/skills/apk-template-asset/layout_calc.py --data build/apk-kr-unpacked/assets/bin/Data \
       --extracted build/apk-kr BattleUIAtlas/buttontxt_retire --out-json build/layout.json
   ```

   `--data` is the APK's `assets/bin/Data` (unpack the APK; the script only reads it),
   `--extracted` the folder of step 1 (each sprite's own pixel size comes from its `index.json`).
   Sprites are `Atlas/sprite` or a bare name. It takes about 10 s. For every widget it prints
   the scene file, the Transform path, the box at 720p and in 1440p script coordinates (16:9, X
   origin as in `coordinates.md`) and the draft scale.

   - **Position.** Predicted boxes of `retreat_button`, `retreat_confirm`, `skill_level_label`,
     `skill_level_reselect`, `support_region_tool` and `weekly_notice` agree with the planner's
     numbers within 0.5 px on KR 8.1.0, and NA 2.95.0 gives the same boxes; they matched the KR
     screenshots within 2 px. The same sprite can sit on several screens: pick the widget whose
     path is the screen you want. A sprite whose widgets hang under no `UI Root` (a prefab that a
     scene instantiates at run time) is listed as "no UI Root": its position is not in the data.
     A widget with a UIRect anchor is marked `anchored`; the game may resize it at run time, so
     check its box against a screenshot.
   - **Scale.** The script prints the widget/sprite ratio per axis (`1.25 * widget / sprite`) and
     suggests `--scale 1.25` unless both axes agree on another value. Do not read the ratio as the
     drawn scale: a localized sprite is not the size of the widget it sits in. KR's retire label
     is 90x31 and NA's is 140x30 in the same 90x32 widget, so the ratio on NA is x 0.80, y 1.33.
     The game either stretches the sprite to the widget (non-uniform) or resizes the widget to
     the sprite at run time (uniform 1.25), and which one happens is not in the data (no NA
     screenshot of that screen exists to check). Where widget and sprite differ by more than 2
     NGUI units (NGUI's even-size rounding is 1-2) the script warns and prints both candidates;
     `draft_template.py --scale X,Y` drafts the stretched one, `--scale 1.25` the other. Draft
     both and let a screenshot decide; without one the draft stays a guess.
   - **What the widget rule did and did not predict**, against the scale that won when the
     sprite was matched against screenshots (KR 8.1.0 data; NA 2.95.0 prints the same):

     | Sprite | Widget rule | Best scale found | |
     |---|---|---|---|
     | `CommonUIAtlas/btn_boostitem_off` | no widget; its `btn_boostitem_on` sibling (62x62) is drawn 54x54 units: 1.089, with a warning | x1.1 | agrees |
     | `CommonUIAtlas/rarity4_0` | 58x20 widget on the costume dialog: 1.047, with a warning | x0.9 (drop screen) | differs: the matched screen's widget is not in the scene data (prefab) |
     | `GachaAtlas/img_bg_friendpointuse` | 234x38 widget: x 1.25, y 1.28, suggests 1.25 | x1.0 | differs: the matched crop is a smooth gradient, so every scale scores the same |
     | `CommonUIAtlas/btn_list_small` | no widget in the scenes (set at run time) | x1.35 | no prediction |

     The rule agrees on 1 of 4. For the sprites of the sprite-match table below it suggests 1.25
     (retire, decide, `support_region_tool`: widget one unit off its sprite); on
     `btn_txt_servant_reselect` (widget 104x64 for a 63x46 sprite) it warns, and the sprite
     matched at 1.25 as a layer. It gives nothing for sprites that are set from code. The boxes
     printed in the issue as "widget 292x48" and "60x21" are 720p sizes (units x 1.25), not NGUI
     units.

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

- **Scale 1.25 is the default, not a law.** It takes an NGUI sprite drawn at its own size to
  720p, and every sprite in the KR table below won at 1.25. A sprite is drawn at its widget's
  size, so others win elsewhere (`btn_boostitem_off` x1.1, `btn_list_small` x1.35, `rarity4_0`
  x0.9): read the widget with `layout_calc.py` before reading a different winner as a false match.
- **Cards and banners from Atlas Academy.** Some screens show art that is not in the APK atlases.
  Atlas Academy's images (downloaded by the owner or planner; they are copyrighted too and stay
  outside the repository) cover two cases: weekly banners `Banner/chaldea_category_*` and
  `Banner/questboard_cap*` at x1.25, and card faces `Faces/f_<id>.png` (128x128) drawn at about
  x0.77 under the APK's frame sprites (for the Ember of Wisdom cards: `CommonUIAtlas/listframes2_bg`,
  `listframes3_bg`, `listframes*_txt_expup` and `rarity3_0`/`rarity4_0`/`rarity5_0`, offsets located
  inside the template). Such a composite is an `APK-partial` draft: the top-left corner badge is in
  no sprite, and offsets are off by 1-3 px (worst shift 0.74-0.79 on `En`).
- **Resources of the KR APK that are the JP originals** make `Jp` drafts, for atlases the JP APK
  lacks (`ResultAtlas`). Drafted from the KR extraction against `app/src/main/assets/Jp`:

  ```bash
  py -3 -E -P .claude/skills/apk-template-asset/draft_template.py --extracted build/apk-kr \
      --sprite ResultAtlas/btn_txt_next --crop-like app/src/main/assets/Jp/mat_rewards.png \
      --check app/src/main/assets/Jp/mat_rewards.png --out build/draft/mat_rewards.png
  ```

  | Template | Sprite | Worst shift | Screenshots |
  |---|---|---|---|
  | `mat_rewards` | `ResultAtlas/btn_txt_next` | 0.925 | none |
  | `bond_result_title` | `ResultAtlas/img_txt_servantbonds01` | 0.872 | 8 positives, draft min 0.879, false max 0.406; call similarity 0.85 |

  Both reproduce with the command above (the second with `servantbonds01` and `bond_result_title.png`).
- **The worst half-pixel shift underestimates small templates.** `friend_summon` has a worst
  shift of 0.766 but a draft minimum of 0.998 on its 2 positives; Jp `bond_popup_title` 0.481 and
  0.897 on 2. A small or smooth crop moves by more than a large one when it shifts half a pixel.
  Judge a draft by `score_screens.py` against the call's similarity wherever screenshots exist,
  and by the worst shift only when they do not.
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
  reward screens still need a screenshot. The KR APK's JP-original atlases are a JP source only
  for atlases the JP APK lacks; where both have an atlas, use the JP APK's.
- `layout_calc.py` matches widgets by sprite name only (the atlas reference is not resolved), so a
  name used in two atlases lists both; it lists no widget for a sprite the game sets from code,
  and no position for a prefab outside a `UI Root`.
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
