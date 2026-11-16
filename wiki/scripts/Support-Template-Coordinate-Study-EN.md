# Built-in Support Images and faceX/faceY Verification

Verified on 2026-10-15. Checked all 107 ascension/costume images across 24 Servants and all 28 Craft Essences bundled identically to upstream v3059. This study did not add image-downloading features to the app.

## Conclusion

**No evidence was found to confirm that the built-in images were generated from `faceX`/`faceY`.** In public viewers, those coordinates are used to place facial expression overlays onto full-body character illustrations; they should not be interpreted as crop coordinates for support thumbnails.

Instead, dedicated assets matching the built-in templates were confirmed in **`extraAssets.faces`** for Servants and **`extraAssets.equipFace`** for Craft Essences. For CEs, using the separate `equipFace` asset matched better than general `faces`.

| Target | Built-in image count | Matching public asset | Best per-image match similarity (min / median) |
|---|---:|---|---|
| Servants | 107 | `Faces` 128×128 | 0.9533 / 0.9891 |
| Craft Essences | 28 | `EquipFaces` 150×68 | 0.9254 / 0.9862 |

These are **normalized cross-correlation coefficients between image files**—they do not imply in-game detection success rates or guarantees across all Servants. For this comparison, each image's scale and crop offset were searched individually.

## Actual Role of faceX/faceY

Atlas's `charaScripts` exposes `faceX`, `faceY`, `scale`, `offsetX`, `offsetY`, and related fields from raw `mstSvtScript`. [Converter code](https://github.com/atlasacademy/fgo-game-data-api/blob/master/app/core/nice/svt/chara_script.py)

Atlas's scene renderer and Chaldea's character-figure composer place facial expression pieces at those coordinates. [Atlas renderer](https://github.com/atlasacademy/apps/blob/master/packages/db/src/Component/Scene.tsx#L108-L150), [Chaldea renderer](https://github.com/chaldea-center/chaldea/blob/main/lib/app/modules/tools/chara_figure/custom_chara_figure.dart#L629-L649)

By contrast, FGA's Support Image Maker crops a fixed region from the screen—currently `(0, 0, 125, 44)` for Servants—and never queries `faceX`/`faceY` in the process. [FGA code](https://github.com/puruuun/FGA-Modified/blob/main/scripts/src/main/java/io/github/fate_grand_automata/scripts/entrypoints/SupportImageMaker.kt#L103-L116)

Among the 107 Servant images compared, 81 had coordinates under a `charaScripts.id` matching the asset ID directly, while the remaining 26 lacked a direct matching row. Although that alone cannot rule out game-side inheritance rules, one cannot assume every image has its own `faceX`/`faceY`.

For the 81 images with coordinates, we tested whether a shared linear model of `faceX`/`faceY` could explain the crop positions found inside `Faces`. Vertical position error averaged 0.621 px using a constant offset alone versus 0.616 px when including both coordinates—barely changing—and leave-one-Servant-out cross-validation yielded 0.652 px. Under this simple model, there was no evidence that those coordinates meaningfully explain support crop positions (though this is not a proof that no other transformation exists).

From the PNGs alone, one cannot determine for certain what pipeline the original author used, and no notes in commit history mention generating them from these coordinates. The built-in files also vary in width and height, so they are not a set saved from identical pixel bounds.

## Do Craft Essences Have Corresponding Coordinates?

In the parsed API responses for the 28 built-in CEs, `faceX`/`faceY` were absent and every CE's `script` was empty. In raw data for all 28 CEs as well, `mstSvtScript`, `mstSvtLimitImage`, and `mstImagePartsGroup` were all empty. This means no per-CE position coordinates were found in the public API, not that no values exist inside private in-game UI resources outside the public API.

CEs already have dedicated **`EquipFaces` 150×68** images available—for example, the [dedicated image](https://static.atlasacademy.io/JP/EquipFaces/f_94003400.png) referenced by `extraAssets.equipFace` in [Kaleidoscope data](https://api.atlasacademy.io/nice/JP/equip/34). Using these pre-made assets matched the built-in templates far better than attempting to compute face regions from full card artwork.

## Does a Uniform Rule Work?

Apart from per-image optimal searches, we also ran an experiment applying **one common scale and one small interior crop** per asset type:

| Target | Common scale | Interior region `(x,y,w,h)` in source asset | Comparison result |
|---|---:|---|---|
| Servants | 1.235 | `(24,48,72,20)` | 107 images: min 0.8767, median 0.9761; 106 at 0.90 or higher |
| Craft Essences | 1.053 | `(16,24,100,10)` | 28 images: min 0.9458, median 0.9924; all 28 at 0.90 or higher |

This shared region was cropped from the rescaled dedicated asset and searched inside each existing template. For Servants, the leftmost 15 px where the friend lock icon appears in FGA were excluded from comparison.

Because this small shared region was chosen after inspecting the dataset, it is not an independent out-of-sample validation. Shrinking the region also requires separate testing for false positives against other characters. **This experiment supports the feasibility of automated generation from dedicated assets, but these numbers are not yet validated for shipping directly in the app for all supports.**

For future development, it makes sense to generate templates from `Faces` and `EquipFaces` and validate same-character detection, cross-character false positives, costumes/special forms, and regular/Grand CE displays on live KR and JP support screens. No auto-download feature was added in this release.

## Comparison Data

- [Per-image optimal comparison results across all 135 images](research/support-template-correlations.csv)
- [Shared interior-patch comparison results across all 135 images](research/support-template-fixed-patches.csv)
- Public data: [Atlas API](https://api.atlasacademy.io/docs), [Skadi example](https://api.atlasacademy.io/nice/JP/servant/215), [Raw CE data example](https://api.atlasacademy.io/raw/JP/equip/9400340)

Downloaded public assets and metadata were not added to the app. Comparisons used OpenCV grayscale `TM_CCOEFF_NORMED`. Neither the game's actual rendering code nor private client files from a user installation were analyzed.
