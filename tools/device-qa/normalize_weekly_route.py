#!/usr/bin/env python3
"""Normalize the 0009 owner window captures to 1280x720 game-area images.

The captures are BlueStacks windows: a 48 px title bar on top, a tool sidebar from x 1950, and a thin window border at
the bottom. The game area is x 0-1950 and y 48 to the row above the border, whose position depends on the capture height.
Output goes to docs/reference-images/<Server>/weekly-route/<NN>.png (server from the file name suffix).
Usage: py -3 tools/device-qa/normalize_weekly_route.py
"""
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "build/orchestration/inputs/0009"
# Only the captures the weeklyRoute suite reads. Bottom edge of the game area by capture height.
KEEP = {"01": "01-jp-spot-panel", "10": "10-jp-avalon-oneoff-panel", "11": "11-jp-fuyuki-zoomed-out", "30": "30-kr-camelot-quest-panel"}
BOTTOM = {1156: 1153, 1146: 1143, 1111: 1111}


def main() -> None:
    for number, stem in KEEP.items():
        path = SRC / f"{stem}.webp"
        server = {"jp": "Jp", "kr": "Kr"}[stem.split("-")[1]]
        image = Image.open(path).convert("RGB")
        left, top, right = 0, 48, 1950
        bottom = BOTTOM[image.height]
        if image.height == 1111:  # 11 is a window capture without border
            left, top, right, bottom = 0, 0, image.width, image.height
        out = ROOT / f"docs/reference-images/{server}/weekly-route/{number}.png"
        out.parent.mkdir(parents=True, exist_ok=True)
        image.crop((left, top, right, bottom)).resize((1280, 720), Image.Resampling.LANCZOS).save(out, optimize=True)
        print(f"wrote {out.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
