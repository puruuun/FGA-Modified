#!/usr/bin/env python3
"""Cut the 0009 navigation templates (top-left buttons and main record buttons) from the normalized reference images.

Input: docs/reference-images/{Jp,Kr}/weekly-route/NN.* (1280x720, see normalize_weekly_route.py).
Output: app/src/main/assets/{Jp,Kr}/weekly_*.png at 720p, the resolution the matcher runs at.
Usage: py -3 tools/device-qa/cut_weekly_templates.py
"""
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
REF = ROOT / "docs/reference-images"
ASSETS = ROOT / "app/src/main/assets"

# The shared button face of the top-left button: house or diamond icon and its label.
TOP_LEFT = (20, 24, 148, 40)
# The body of a main record list button without the CLEAR badge and the counter: colour and label.
BUTTON = (665, 0, 525, 70)

# name -> (server folder, reference image, (x, y, w, h))
CUTS = {
    "Jp": {
        "weekly_terminal": ("11.png", TOP_LEFT),
        "weekly_close": ("04.webp", TOP_LEFT),
        "weekly_notice": ("02.webp", TOP_LEFT),
        "weekly_mr1": ("03.webp", (665, 372, 525, 70)),
        "weekly_mr15": ("03.webp", (665, 205, 525, 70)),
        "weekly_mr2": ("12.webp", (668, 162, 522, 70)),
        "weekly_mi": ("35.webp", (665, 448, 525, 70)),
    },
    "Kr": {
        "weekly_terminal": ("29.webp", TOP_LEFT),
        "weekly_close": ("26.webp", TOP_LEFT),
        "weekly_notice": ("24.webp", TOP_LEFT),
        "weekly_mr1": ("24.webp", (665, 472, 525, 70)),
        "weekly_mr15": ("24.webp", (665, 305, 525, 70)),
        # Same button-face crop as JP; capture 37 is scrolled 61 px lower.
        "weekly_mi": ("37.webp", (665, 509, 525, 70)),
    },
}


def main() -> None:
    for server, cuts in CUTS.items():
        for name, (file, (x, y, w, h)) in cuts.items():
            image = Image.open(REF / server / "weekly-route" / file).convert("RGB")
            out = ASSETS / server / f"{name}.png"
            image.crop((x, y, x + w, y + h)).save(out, optimize=True)
            print(f"wrote {out.relative_to(ROOT)} {w}x{h}")


if __name__ == "__main__":
    main()
