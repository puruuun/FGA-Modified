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
# Only the captures the weeklyRoute suite reads (21-23 are Mictlan, which the route excludes). Bottom edge of the game area by capture height.
KEEP = {
    "01": "01-jp-spot-panel", "02": "02-jp-home", "03": "03-jp-home-scrolled", "04": "04-jp-mr1-top", "05": "05-jp-mr1-bottom",
    "06": "06-jp-fuyuki-a", "07": "07-jp-fuyuki-b", "08": "08-jp-fuyuki-c", "09": "09-jp-fuyuki-d",
    "10": "10-jp-avalon-oneoff-panel", "11": "11-jp-fuyuki-zoomed-out", "12": "12-jp-home-mr2", "13": "13-jp-mr2-top",
    "14": "14-jp-mr2-mid", "15": "15-jp-mr2-lb6", "16": "16-jp-mr2-lb3", "17": "17-jp-mr15", "18": "18-jp-mr1-mid",
    "19": "19-jp-orleans-zoomed-in", "20": "20-jp-atlantis-zoomed-out",
    "24": "24-kr-home-mr", "25": "25-kr-home-lostbelts", "26": "26-kr-mr1", "27": "27-kr-mr15",
    "35": "35-home-main-records", "36": "36-main-interlude-list-traum-heian",
    "37": "37-kr-home-main-interlude", "38": "38-kr-main-interlude-list",
    "28": "28-kr-camelot-default", "29": "29-kr-camelot-zoomed-out", "30": "30-kr-camelot-quest-panel",
}
BOTTOM = {1156: 1153, 1147: 1144, 1146: 1143, 1111: 1111}


def main() -> None:
    for number, stem in KEEP.items():
        path = SRC / f"{stem}.webp"
        server = {"jp": "Jp", "kr": "Kr"}.get(stem.split("-")[1], "Jp")  # 35 and 36 are JP captures without a server tag
        image = Image.open(path).convert("RGB")
        left, top, right = 0, 48, 1950
        bottom = BOTTOM[image.height]
        if image.height == 1111:  # 11 is a window capture without border
            left, top, right, bottom = 0, 0, image.width, image.height
        # 01, 10, 11 and 30 shipped as PNG in 1.6.0-beta.1; the rest are lossless WebP (about half the size, same pixels).
        legacy = number in {"01", "10", "11", "30"}
        out = ROOT / f"docs/reference-images/{server}/weekly-route/{number}.{'png' if legacy else 'webp'}"
        out.parent.mkdir(parents=True, exist_ok=True)
        frame = image.crop((left, top, right, bottom)).resize((1280, 720), Image.Resampling.LANCZOS)
        if legacy:
            frame.save(out, optimize=True)
        else:
            frame.save(out, "WEBP", lossless=True, quality=100, method=6)
        print(f"wrote {out.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
