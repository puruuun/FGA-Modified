#!/usr/bin/env python3
"""Normalize the 0008 owner window captures (2000x1147, title bar and sidebar included) to 1280x720.

The game area is x 0-1950, y 48-1145 of the window capture. Output goes to
docs/reference-images/<Server>/skill-level/<NN>.png (server from the file name suffix).
Usage: py -3 tools/device-qa/normalize_skill_level.py
"""
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "build/orchestration/inputs/0008"
GAME_BOX = (0, 48, 1950, 1145)


def main() -> None:
    for path in sorted(SRC.glob("*.webp")):
        number, suffix, *_ = path.stem.split("-")
        server = {"jp": "Jp", "kr": "Kr"}[suffix]
        out = ROOT / f"docs/reference-images/{server}/skill-level/{number}.png"
        out.parent.mkdir(parents=True, exist_ok=True)
        Image.open(path).convert("RGB").crop(GAME_BOX).resize((1280, 720), Image.Resampling.LANCZOS).save(out, optimize=True)
        print(f"wrote {out.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
