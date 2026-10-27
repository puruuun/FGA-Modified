# Reference images

Device-screen fixtures for the on-device reader QA. The authoritative check is `tools/device-qa/run.py`, which reads the case files under `cases/`.

## Case files

- `cases/hakunoShuffle.json`: #45 KR NP-shortage Close popup on the owner's 2560×1080 wide and 1920×1080 frames, normalized to 720p game areas as lossless WebP under `Kr/hakuno-shuffle/`. Sources: `build/orchestration/inputs/hakuno-np-close-kr-{2560x1080,1920x1080}.png`. The suite uses the product's named Close region and existing `Kr/close.png`; `Kr/battle-screen.png` is the absence control. No game input.

| File | Suite | Labels from | Cases |
|---|---|---|---|
| `cases/support.json` | support | `Jp/support-scores/manifest.json` (101), `Kr/support-scores/manifest.json` (26), `Kr/support-np-20261017/manifest.json` (38), `support-np-*.png` extras (3), the device-side full screens (20), `Jp/wide-2340/` 0013 (2340x1080 score crops 2, full frame 1) | 191 |
| `cases/bond.json` | bond | `tools/bond-image-cases.json` (20) | 20 |
| `cases/questItems.json` | questItems | `quest-items-crops.json` (38 crops, 13 screens) | 13 |
| `cases/noblePhantasm.json` | noblePhantasm | `Jp/np-20261018/`, `Jp/np-20261018-r3/` (owner JP screens, names/comments masked; existing 3x3 and NP-only cases, eight normal rows, Grand/normal Olga cross-layout matches and production maker crop checks) | 18 |
| `cases/skillLevel.json` | skillLevel | 0008 owner screens `Jp/skill-level/`, `Kr/skill-level/` (24 frames normalized to 1280x720 by `tools/device-qa/normalize_skill_level.py`; title/mode, instruction band, confirm dialog, `Lv.current` OCR, append padlocks, result) | 63 |

Run one suite or all of them:

```bash
py -3 tools/device-qa/run.py --build --suite support,bond,questItems
```

## Case format (v1)

- Support `view: "CESelection"`: saved full frame through `SupportBoundsFinder` and `CESelection.check`, with `builtInCes`, `mlb`, `bothSlots` and per-row `effects` expectations. Reports contain both bond-template scores and the beta diagnostic line. An explicitly labelled `partial` row uses its measured `y` when its confirm button is outside the capture.
- `Jp/wide-2560/27-grand-lancer-support.webp`: #23 owner JP 2560×1080 frame, lossless WebP with player names/comments masked. Row 1 Scáthach uses Foreign God + Chaldea Teatime, MLB and both slots on; NP=true, Default=false, Ignore=true. Partial Grand row 3 has the Default icon and NP=false.

- `frame: "crop"`: the fixture is a crop, placed at `region720` on a black 1280x720 canvas. Reads outside the crop fail the case (coverage, D-4).
- `bleedRight720`: a single crop case may read this many 720p pixels past its right edge (black canvas there). Set to 5 on the 55x30 score crops, since the score region is 60 px wide.
- `pieces`: several crops of one screen, each with its own `region720`. Used by the quest item suite.
- `masked720`: rectangles hidden for privacy. A read that overlaps one fails the case.
- Full frames (no `frame`): any aspect ratio. The frame is scaled to 720 high and the coordinates use the same formulas as `ScriptAreaTransforms`.
- `skip`: a reason string. The case is left out of the run and listed in `summary-<suite>.md`.
- Support row `grandAllowed: [null, 16]`: retains the `grand: 16` ground-truth label while testing that D-1 returns either 16 or unknown, never another number (199.webp row 2).
- Support row `protectiveUnknown: ["class", "grand"]` (either field or both): requires those fields to return **null** and their selection conditions to fail. Original numeric labels and `policy` remain unchanged. Use only for documented D-1 veto regressions; a passing veto test is not a successful numeric detection.

## Rules for new fixtures

- Crop the reader's own region. Do not add whole game screens that show player names or friend codes; if a whole screen is needed, mask the name area with `masked720`.
- Keep the label next to its fixture in the case file. Do not change an expected value to make a run pass. A mismatch is reported, not fixed.
- Record the source (device, date) in the case `name` or the manifest it came from.

## Skipped cases

| Suite | Count | Reason |
|---|---|---|
| support | 3 | The NP crops are 448x48 but the production name region is 448x52. The original full screens are not in the repo, so they cannot be cut again. |
| questItems | 8 | The reader reads the heading area, which is not in the screen's crops. Each screen only has the main and party heading crops. |
| bond | 0 | |

The support and bond counts above match the last full run (185 passed + 3 skipped, 20 passed).
