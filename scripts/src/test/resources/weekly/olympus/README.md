# Olympus regression fixtures

Issue #103: owner captures from BlueStacks at 2560×1080, JP and KR, top and bottom.
Screenshots retain 720p height; the map 30601 base and alpha-composited map are 1024×720
(half the Atlas display size). Grayscale is quantized to 64 levels to keep all fixtures below 3 MB.
The originals remain in `build/orchestration/inputs/beta9/` and are not committed.

Atlas source: `https://api.atlasacademy.io/nice/JP/war/306`, map 30601.
Layers in ascending depth: `gimmick_030614` (-400), `030613` (-100), `030601` (0),
`030612` (0), `030611` (200). The two unavailable images `030621` and `030622` are skipped.
All five decoded images are RGBA, 2048×1440, centred at (1024,720), scale 1000.

`WeeklyOlympusTest` runs the product MapLocalizer against SIFT correspondences with the same
settings as DroidCvPattern, using JVM-only OpenCV 4.9.0. Android continues to use OpenCV 4.11.0.
Fit scores are SIFT inlier ratios, distinct from the NCC measurements in the approved issue comment.
