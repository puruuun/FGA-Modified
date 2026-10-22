# JP native holdout — measurement only

Nine native BlueStacks JP captures, 27 fully visible score boxes. Labels were visually checked independently against every score crop; they agree with the planner. No clipped score rows were excluded.

The full game area has no window chrome. OpenCV INTER_AREA resizes to height 720 with width `sourceWidth * 720 // sourceHeight`. `labels.json` records source size, SHA-256, normalized size and row origins. `scores/` contains the exact product 60×30 score regions; QA places these unchanged at the standard score region on a black 1280×720 canvas, as for the D-2 training evaluation. The product retains all training resolution template sets and does not infer a native capture resolution from the canvas.

These images are never templates or calibration inputs. The beta.2 comparison rebuilds the exact `SupportScoreReader.kt` from `3a8af5b7` with the current fixture harness, then restores it byte for byte. The beta.3 comparison uses the unchanged reader and templates from `d98b9525`. Both run on 127.0.0.1:5555 with product OpenCV/Tesseract.

See `docs/design/0014-native-holdout-results.json` for every before/after result, including all 18 remaining unknown rows. QA cases live in `cases/support.json`; rows missing any labelled field retain the ground truth and skip with a reason. The all-row measurement includes those skips in every denominator.
