---
name: apk-template-asset
description: Fallback only - draft a KR or JP template from the game's APK data (NGUI layout, labels, atlas sprites, bitmap fonts) when the owner cannot get a screenshot of that screen, and judge it by the measured per-type criteria; also to cross-check a template or find which sprite/label/widget an element is. Screenshots come first - templates are cut with extract-template-asset. Use when "there is no screenshot of this screen", "can we make this template without a capture", "which sprite/label is this", "does the APK have this button".
---

# Drafting templates from the game's data

A feature needs templates (`app/src/main/assets/<Server>/`) and Regions (`scripts/.../locations/`). The APK of
each server holds the NGUI layout of every scene and prefab (widget sizes, pivots, Transform chains), the decoded
label settings (font size, effect, colours, ShrinkContent), the atlas sprites, the bitmap fonts and the
localization table. This skill turns those into a draft template and a Region when no screenshot of the screen
can be had, and locates elements for any template work. Game code and layout are shared by KR and JP, so one
screenshot of either server settles what the data cannot.

Read [`docs/agents/coordinates.md`](../../../docs/agents/coordinates.md) and
[`docs/agents/servers-and-assets.md`](../../../docs/agents/servers-and-assets.md) first: templates are 720p,
Regions 1440p with the X origin by aspect ratio, and the `En` fallback rules apply. The fork's target servers are
KR and official JP; the scripts take any server's APK, but drafts for other servers are not a fork deliverable.

## When to use this skill

**Screenshots come first.** A feature's templates are cut from the owner's screenshots with
`extract-template-asset`. This skill is the fallback when the owner cannot get a screenshot of that screen,
and a check: it may locate an element (which sprite, label or widget; its Region) at any time, but a draft is
used as a template only under the criteria below.

A draft is used only when **both** hold:

1. Its element type is in a usable row of the table.
2. On real screenshots of the same screen from either server, it clears both margins: true score at least the
   code's threshold + 0.05, and best false positive at most the threshold - 0.15 (see "Judging a draft").
   For the "use without a screenshot" row, a screen of the other server drafted from that server's data counts.

If either fails, the draft is not used and the feature waits for a screenshot. A PR that commits a drafted
template says so, with the margins, and the owner's final in-game check covers it before release.

Measured on real screens (cross-server acceptance below, and the NA comparison of 2026-11-20 in issue #570):

| Element type | Measured true score | Use |
|---|---|---|
| Button labels of standard dialogs: static text from the string table, standard font, no run-time state (Close, OK, Execute, Repeat, quest-item Close) | 0.952–0.986 in 13 of 13 KR/JP/NA cases, as good as the cut templates | Use without a screenshot when no screenshot can be had; the final in-game check confirms it |
| Static atlas sprites, icons and sprite text drawn at the layout size (tool icons, reselect buttons, labels drawn as sprites, the weekly terminal button) | 0.87–0.99 | Use only when it clears the margins on a real screen of the same screen, either server |
| Bitmap-font digits | 0.94–0.97 against the cut templates only, never scored on screens | Same as the row above |
| Dialog titles and tab labels | JP 0.83–0.98; KR failed twice for an unknown reason (0.559, 0.682) and once with a wrong string (0.190) | Do not use without a real screen of the same server |
| Art not in the APK (downloaded banners, result and bond titles, summon button art) | cannot be drawn | Screenshot only |
| Run-time state: disabled tint, glow, size set by the layout at run time (four-button rows), bar lengths | 0.13–0.79 | Screenshot only |
| A template that crops part of a widget, or an element the tool cannot locate | 0.11–0.66 | Screenshot only |

Needs Python 3, Pillow, numpy, opencv-python-headless and UnityPy. Run the scripts with `py -3 -E -P script.py`
on Windows so nothing from the current folder is imported. Do not install anything yourself; report a missing
package.

## Workflow

1. **Extract** each server's APK once (the owner supplies the `.apk`/`.xapk`; never from a device an agent
   operates). About a minute per APK:

   ```bash
   py -3 -E -P .claude/skills/apk-template-asset/extract_apk_ui.py kr.apk --out-dir build/apk-kr
   py -3 -E -P .claude/skills/apk-template-asset/extract_apk_ui.py jp.apk --out-dir build/apk-jp
   ```

   It writes `fonts/`, `text/` (localization tables), `textures/`, `sprites/<Atlas>/<sprite>.png`, `index.json`,
   `layout.json` (every widget, atlas, UI Root and layout component, `ngui_dump.py`) and `uifonts.json` (bitmap
   fonts). Check it with `selfcheck.py` (below) after a game update.

2. **Locate the element in the layout data** and draft it with `draft_element.py`. Name it the way you know it:

   ```bash
   # a label by the text it shows, a sprite by name, a widget by path, or what sits at a 720p point
   py -3 -E -P .claude/skills/apk-template-asset/draft_element.py --data build/apk-kr --text "닫기" --out-dir build/d
   py -3 -E -P .claude/skills/apk-template-asset/draft_element.py --data build/apk-kr \
       --path 'SvtCombinePanel/RecommendedMaterialButton/Label$' --key RECOMMENDED_MATERIAL_BUTTON_LABEL \
       --with 'RecommendedMaterialButton/Bg$' --out-dir build/d --name servant_auto_select
   py -3 -E -P .claude/skills/apk-template-asset/draft_element.py --data build/apk-jp \
       --sprite CombineAtlas/btn_txt_servant_reselect --out-dir build/d
   ```

   Every match is listed with its path; `--pick N` keeps some, `--with RX` adds widgets of the same scene or prefab
   (the button sprite under a label). For each widget it prints the size rule's branch, the box at 720p (16:9) and
   `Region(x, y, w, h).xFromCenter()` at 1440p, and it writes `<name>.png` plus `<name>.json`.

   A text list is not a choice. `--text "확인"` lists 25 KR labels, and with no `--pick` the first one is drawn,
   which is a profile dialog's button. Each list is in data order. A label whose text code sets shows something
   else in the data, so the right one can be missing from the list: the KR level-up confirmation's 확인 is `결정`
   in the data. `--with RX` searches the whole scene or prefab of the element: `--with 'ConfirmBtn/BtnBg$'` adds
   every such button in CombineScene and gives a 965x278 draft. Anchor it to the element's own path. See "Picking
   the right widget" below.

   **From a screenshot of the other server.** When you have the screen on JP but draft for KR (or the reverse),
   let the screenshot find the element. The other server's template (or a box) marks it; the widgets drawn there
   are found in that server's data and drafted from the target's data under the same paths:

   ```bash
   py -3 -E -P .claude/skills/apk-template-asset/draft_element.py --data build/apk-kr \
       --locate-on docs/reference-images/Jp/weekly-route/32-repeat-dialog.webp --locate-data build/apk-jp \
       --locate-template app/src/main/assets/Jp/close.png --background-from app/src/main/assets/Jp/close.png \
       --out-dir build/d --name close
   ```

   A label whose text is set by code shows something else in the data: give the text it shows on the screenshot
   with `--shown-text 閉じる`; the target text is the same localization entry on the target server, or, for texts
   that come from server data and are not in the APK table, the one you give with `--target-text 닫기`.
   The margin between the drawn content and the located box carries over as the crop.

   Locating picks the widget that best explains the box, which is often another label of the same style or the
   wrong scene. When you know the element from the data, name it and let the screenshot give only the crop margin,
   the sprites under it and the N/W decision: `--locate-path RX` (a regex of its widget path) and, for a sprite
   that code sets (no widget names it in the data), `--locate-sprite S`. A named widget that is not found in the
   box is drafted without the margin and without the sprites under it, and the output says so.

3. **Settle what the data cannot decide.** A sprite whose code swaps it and resizes it to its own size (N) cannot
   be told from one stretched to its widget (W) when the scene lists only one sprite for that slot. When the two
   sizes differ, both drafts are written (`<name>_N.png`, `<name>_W.png`) with a warning. One screenshot of either
   server decides: `--decide-from SHOT --decide-data DIR` measures the widget there with that server's art (size
   sweep, alpha-weighted NCC), picks the mode whose prediction from **that** server's data matches, and applies
   the mode (not the size) to the target. `--locate-on` decides from its screenshot automatically.

4. **Score on real screens** if the server's reference screenshots show the element:

   ```bash
   ... draft_element.py ... --screens docs/reference-images/Kr --reference app/src/main/assets/Kr/close.png \
       --cache build/screen-cache
   py -3 -E -P .claude/skills/apk-template-asset/screen_score.py --screens docs/reference-images/Kr \
       --reference app/src/main/assets/Kr/close.png --template build/d/close.png --out-csv build/close.csv \
       --cache build/screen-cache
   ```

   `--cache DIR` keeps the decoded screenshots, so a second run skips decoding them. The cache key is each file's
   absolute path and size (known issue 7). After you replace a screenshot with one of the same size, delete the
   cache folder.

   The threshold is read from `scripts/` (`--threshold code`, the default: every call of the `Images` entry, its
   `similarity = ...` or minSimilarity 0.8; the highest is used and each call is listed) or given as a number.
   Positives are the screenshots where the reference scores at least the threshold (or `--positive RX`), negatives
   where it scores under 0.6. Printed and written per draft: the true score (worst positive, best match within
   +-12 px of the element), the best false positive (whole screen, an upper bound; `--region` for the Region),
   and both margins, true - threshold and threshold - false.

5. **Implement**, with the Region from step 2 (add the margin the call needs, and the `isWide` value where the
   element follows an edge), then **the owner's in-game check**. Templates that fail there are re-cut from the
   owner's captures with `extract-template-asset`; keep those captures in `docs/reference-images/`.

### Picking the right widget

This was tested on the KR `ok` button (확인 in the append skill level-up confirmation). It needs a screenshot of
the screen, from either server, since the layout is shared:

1. **Find the panel by what the screenshot shows.** Search a distinctive text on that screen, not the
   button's own word:
   `draft_element.py --data build/apk-kr --text '강화에 필요한 QP:' --out-dir build/d --name probe`
   lists `.../SetRarityDialog/DlgPanel/<Kind>ConfirmInfo/SpendQpTxt`, so the dialog is `SetRarityDialog/DlgPanel`.
   `--at X,Y` (a 720p point) lists every widget whose data rect covers it, every stacked dialog and mask
   included. Add `--path '^CombineScene/'` to keep it short.
2. **List that panel's widgets** and compare their 720p boxes with the screenshot:
   `--path 'SetRarityDialog/DlgPanel/ConfirmBtnInfo/'` lists `CancleBtn/CancleTxt` (취소, box 308,555) and
   `ConfirmBtn/ConfirmTxt` (`결정` in the data, box 704,555 272x70). The screenshot shows 확인 on the right
   button, so the element is `ConfirmBtn/ConfirmTxt` and code sets its text.
3. **Read the element's box on the screenshot** (720p game area, x y w h around what the template should hold):
   here `803,543,70,42`.
4. **Draft with the element named** and score it:

   ```bash
   py -3 -E -P .claude/skills/apk-template-asset/draft_element.py --data build/apk-kr \
       --locate-on docs/reference-images/Kr/skill-level/03-append-2to3-tablet.png --locate-data build/apk-kr \
       --locate-box 803,543,70,42 \
       --locate-path 'SetRarityDialog/DlgPanel/ConfirmBtnInfo/ConfirmBtn/ConfirmTxt$' --shown-text 확인 \
       --screens docs/reference-images/Kr --reference app/src/main/assets/Kr/ok.png --cache build/screen-cache \
       --out-dir build/d --name ok
   ```

   The named label is found at 0.988, and the button sprite under it is added as background. The 71x43 draft
   scores true 0.979 and best false 0.662 against threshold 0.80, so the margins are 0.179 and 0.138. The existing
   template's margins are 0.157 and 0.218. Check the `B` lines: the sprites under the element are searched in the
   whole scene, so look-alike buttons of other dialogs at the same spot can be listed too. They are harmless here
   (the same sprite in the same place).

Without a screenshot, `--path ... --key COMMON_CONFIRM_DECIDE` (any entry that reads 확인) draws the right label.
Adding the button with `--with 'SetRarityDialog/DlgPanel/ConfirmBtnInfo/ConfirmBtn$'` crops the whole 272x70
button, and with N/W open both drafts are written. The W draft scored true 0.942 but best false 0.908, because
every such button looks alike, so it fails. Get a screenshot for the crop.

### Judging a draft

Only margins on real screens count: true score - threshold, and threshold - best false positive, both positive.
Similarity between a draft and an existing template does not predict either (a draft can score 0.95 against the
template and fail on screens, or 0.7 and pass). Fit nothing to the screens you judge on: no scale, offset,
colour, font size or threshold chosen by scoring against them. A flat background colour taken from a template's
1 px border, where the draft has no widget behind it, is allowed and is reported.

### When a screenshot is needed up front

- A sprite set from code (N or W open) with no screenshot of either server to decide.
- Art that is not in the APK: downloaded banners and atlases (weekly route banners, the KR `ResultAtlas`, which is
  the JP original replaced at run time), card faces.
- Elements over animation or glow (the drawn pixels change between frames), or semi-transparent strips over
  changing content.
- Bar-driven lengths (UIScrollBar / UIProgressBar / UISlider set the length at run time; `D` in the output).
- Positions that follow an edge on screens wider than 16:9 (UIAnchor / anchored widgets are reported; the data
  gives the 16:9 position).
- Labels whose text comes from server data and is not in the APK table (give `--target-text`; the style is still
  from data).

## Scripts

| Script | Does |
|---|---|
| `extract_apk_ui.py APK --out-dir DIR` | fonts, localization, textures, sprites, `index.json`, `layout.json`, `uifonts.json` |
| `ngui_dump.py` | the raw MonoBehaviour decoders (UIWidget, UISprite, UILabel, UIAtlas, UIFont, UIRoot), used by the extraction |
| `ngui_layout.py` | loads an extraction; the size rule (`Rule`), positions, `region_1440` |
| `ngui_render.py` | draws labels (dynamic font), bitmap-font labels and sprites (Simple/Sliced) like a 1080p screenshot shrunk to 720p |
| `element.py` | a widget as a layer, masked matching, size measurement, N/W decision, locating widgets on a screenshot |
| `draft_element.py` | the entry point above |
| `screen_score.py` | true score, false positives and margins on a folder of screenshots; the threshold from `scripts/` |
| `selfcheck.py --kr DIR --jp DIR` | replays the accepted numbers (below) on your extraction; `text_check.py --record` rewrites the text expectations |
| `cross_server.py [--named]` | the cross-server acceptance replay (below), with the element located or named |
| `draft_template.py`, `find_sprite.py`, `score_screens.py`, `template_lib.py` | the manual tools: one sprite or font text at a chosen scale, which sprite a template was cut from, folder scoring |

## What the rules are and how well they hold

**Sprite size** (`ngui_layout.Rule`, learned on KR 8.1.0, frozen, then held out on JP). Decision list: D when a
bar component drives the widget; N when the slot names several sprites in the APK, the widget does not fit the
sprite within 2 units and the aspect ratios differ by more than 25 %; otherwise W, NGUI's `drawingDimensions`
(padding in proportion with the odd-size fix for Simple/Filled, padding x pixelSize for Sliced) x world scale x
720/`manualHeight`. Measured sizes (within 2 px or 3 %) on the screenshots of `docs/reference-images`:

| | rule | old fixed x1.25 |
|---|---|---|
| KR (training, 90 elements) | 89/90 | 74/90 |
| JP (held out, 106) | 97/106 | 79/106 |

The JP misses are `icon_limit_on` (8: drawn about 22 px where the prefab widget says 16-24) and one
`img_commonbg_02`; the KR miss is `img_combine_arrow` (3 px tall). `selfcheck_sizes.csv` holds the element
identifiers and measured sizes (no images).

**Text** (`ngui_render`): glyphs at the printed size on the label's unit grid, NGUIText's baseline, effect copies
(Shadow, Outline, Outline8), gradient, ShrinkContent by pen width, world scale x 720/576.
`selfcheck_text.csv` replays 19 labels (2 KR, 17 JP) on the reference screenshots they appear on, drawn at
their data scale and matched alpha-weighted: KR 0.82-0.95, JP 0.85-0.98 (the study the renderer came from swept
the scale and reached 0.88-0.99 on the same labels).

**Numbers**: bitmap-font labels (`critfont01`, `FGO-NumberFont-*`) are drawn from the font's glyph table at label
size / font size. Earlier research measured 0.94-0.97 on critical-star digit templates; no KR/JP number template was in the
acceptance set below.

## Cross-server acceptance (`cross_server.py`)

Every template that exists in both `Kr/` and `Jp/` and that the existing template finds on both servers'
reference screenshots (38): the KR draft from KR data plus **one** JP screenshot (the JP template's best 16:9
positive, used to locate the element and to decide N/W), the JP draft the same way the other round; drafts frozen
(sha256) before scoring; scored on the target server's screenshots against the existing template and the code's
threshold. The KR reference screenshots are also the size rule's KR training set (the "KR (training, 90 elements)"
row above), so KR-target numbers are not held out. Only the JP-target numbers come from screens the rule was not
learned on.

**Element located automatically** — grade of the first draft per template (as good as existing / passes but
lower / fails / cannot build):

| | as good | lower | fails | cannot build |
|---|---|---|---|---|
| KR (38) | 5 | 7 | 10 | 16 |
| JP (38) | 6 | 5 | 14 | 13 |

As good on both: `ok`, `quest_item_close`, `skill_level_label`, `support_region_tool`; passing on both:
`ce_enhance_execute`, `close`, `execute`, `fp_sell_sell`, `inven_full`, `skill_level_reselect`.
`rank_up` passes (as good) with its W draft on both, but the rule's N draft is first and fails: the arrow was not
found on the other screenshot, so N/W stayed open.

- **Cannot build (29):** art not in the APK (weekly route banners x10, `bond_popup_title`/`bond_result_title` x4,
  KR `result`: the KR `ResultAtlas` is the JP original) and elements the other screenshot did not locate (14:
  sprite text that no rooted widget draws at that spot, runtime tints such as `servant_auto_select_off`).
- **Fails (24):** mostly a different widget located for the element (20: a label of the same text but another
  style or size, or a label where the real element is atlas art, e.g. `weekly_close`/`weekly_notice`/
  `weekly_terminal` whose text is a sprite); `rank_up` x2 (N/W open); `ce_enhance_reselect` KR (the servant
  reselect sprite located instead); JP `skill_level_result` 0.783 against 0.80 (glowing text; the existing
  template itself scores 0.804).

**Element named instead of located** (`cross_server.py --named`): the same 76 drafts with the element chosen by
hand from the source server only (its data, template and the same one screenshot; `cross_server_cases.json`
"named" records each path, sprite, text and why), or marked as art that is not in the APK. The screenshot still
gives the crop margin, the sprites under the element and the N/W decision. Frozen before scoring, scored the same
way. **Not a blind result:** each choice cites source-server evidence only, but the one agent that made all of
them had already seen both servers' templates and screenshots. Grades (as good / lower / fails / cannot build /
not in the APK):

| | as good | lower | fails | cannot build | not in the APK |
|---|---|---|---|---|---|
| KR (38) | 6 | 8 | 13 | 0 | 11 |
| JP (38) | 9 | 6 | 11 | 0 | 12 |

Naming fixes, among others, `ce_enhance_reselect`, `fp_sell_tab` JP, `fp_sell_word_servant` JP, `weekly_terminal` and the JP
dialog titles; the failures left are drafting limits and known issues: a label whose size is set at run time
(`ce_enhance_execute`: the dialog's four-button layout), part of a widget cropped by the template (a word of a
title, the caption of a big button: the draft is the whole widget), runtime tints, a JP or KR atlas that holds
the other server's art, glow, KR dialog text that the renderer does not match (`append_level_dialog_title`,
`fp_sell_word_servant` on KR), and the known issues below.

So: naming the right widget is necessary, not sufficient.
- Most right-widget drafts clear the threshold with margin. Passing named drafts have a true minimum of
  0.81-0.98 and a best false positive of 0.36-0.68; existing templates have 0.34-0.60.
- Two fail with the right widget and the right string: KR `append_level_dialog_title` at 0.682 and KR
  `fp_sell_word_servant` at 0.559. The KR dialog text is not matched by the renderer, and it is not a size
  difference; the cause is not isolated.

Name the element yourself (`--locate-path`, or `--path`, `--sprite`, `--key` without a screenshot). Name every
widget the template shows (the caption and the button under it). Look at what was printed before trusting a
draft, and judge only by the margins on real screens.

## Known issues (not fixed)

1. The N/W decision (`element.decide_mode`) measures the widget within ±24 px of its data position, and
   `draft_element` does not pass it where the widget was located. Anchored or moved widgets stay undecided:
   `rank_up`, `weekly_*`, KR `battle_menu`.
2. With `--shown-text`, when the source label already shows that text in its own data, the target label keeps
   *its* data text. That text can be a placeholder id (JP `servant_auto_select`). When it is empty, drafting
   stops with `min() iterable argument is empty`.
3. `--target-text` is used only when the localization mapping finds no entry. A single shown word maps to its
   most common translation, which gave 강화 instead of the given 레벨업 for KR `skill_level_dialog_title`.
4. `--locate-path` keeps the highest raw match over its search windows, rather than the match at the widget's
   own spot or the one that explains the box. This cut the JP `skill_level_label` crop.
5. Crop insets are clamped to ±12 px. A template that crops part of a widget (a word of a title, the caption of a
   large button) gets the whole widget: `battle_menu`, `fp_continue`.
6. `screen_score.load_screens` writes its cache without an atomic rename. Several pool workers on a fresh cache
   folder can hit `BadZipFile`; copy or pre-build the cache.
7. The screenshot cache key is each file's absolute path and size, not its content. A screenshot replaced by one
   of the same size reads the stale cache; delete the cache folder.

## Limits

- Downloaded game data is encrypted and not used. The KR APK's `ResultAtlas` is the JP original that the game
  replaces with a downloaded Korean one; result and reward screens need a screenshot.
- The layout is read at fixed byte offsets (no type tree). A client update that changes NGUI's field layout makes
  `selfcheck.py` fail; re-check the offsets in `ngui_dump.py` then.
- Prefab widgets (dialogs instantiated at run time) have no screen position in the data: their size and look are
  predicted, their position comes from the other server's screenshot or the owner's capture.
- Runtime state is not in the data: a button's disabled tint (`servant_auto_select_off`), a selected tab colour,
  a list that scrolls.
- Locating picks, among identical-looking labels in different prefabs, the first; their style is the same, so
  the draft is too.

## Copyright

The APK, fonts, strings, textures and sprites are copyrighted. Extractions, drafts and screenshots stay outside the
repository (the session scratchpad or `build/`); only a finished template verified on a real screen goes into
`app/src/main/assets/`.
