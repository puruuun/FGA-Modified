# Game-area calibration fixture

Source: owner's `kr-tablet-split-main-3040x1904.png`, issue #116, preview off.
Only x=1000..3039 is stored. Tests place it at x=1000 on a 3040×1904 grayscale canvas;
the unrelated FGA pane becomes blank. Pixel coordinates and scale are unchanged.
Grayscale is quantized to 64 levels, keeping the fixture below 1 MB (819134 bytes).
Original captures stay in `build/orchestration/inputs/game-area-calibrate/` and are not committed.

The pure JVM test uses OpenCV 4.9.0 with the product's INTER_AREA resize and TM_CCOEFF_NORMED.
Android still uses OpenCV 4.11.0. Templates are the existing app assets, including the En MENU fallback.
