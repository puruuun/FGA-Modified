#!/usr/bin/env python3
"""Find which atlas sprite each existing template was cut from, to confirm the scale and the method.

Requires Pillow, numpy and opencv-python-headless. Run on Windows with `py -3 -E -P`.
Every sprite of an extract_apk_ui.py folder is tried at scales 1.0-1.5 against every template of
a folder; a sprite's alpha is flattened on the template's median color so the art sits on a
similar background. The best sprites per template are printed and written to a CSV, together
with the best sprite's highest score inside the OTHER templates (a false-match check: a sprite
that also scores high elsewhere would match the wrong screen).

    find_sprite.py --extracted build/apk-kr --templates app/src/main/assets/Kr --out-csv build/match-kr.csv
    find_sprite.py --extracted build/apk-kr --templates app/src/main/assets/Kr --names retreat_button.png,weekly_notice.png
"""

import argparse
import csv
import json
import os
import re
import sys

import numpy as np
from PIL import Image

sys.dont_write_bytecode = True  # no __pycache__ inside the skill folder
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import template_lib as lib  # noqa: E402

SCALES = [1.0, 1.1, 1.2, 1.25, 1.3, 1.4, 1.5]
# Below this a sprite and a template overlap too little for the score to mean anything
MIN_SIZE_RATIO = 0.5
MIN_SPRITE_SIDE = 8


def load_sprites(extracted: str) -> list[tuple[str, str, np.ndarray]]:
    with open(os.path.join(extracted, "index.json"), encoding="utf-8") as f:
        index = json.load(f)
    sprites = []
    for atlas, entries in index["atlases"].items():
        folder = os.path.join(extracted, "sprites", lib_safe(atlas))
        for entry in entries:
            if entry["w"] < MIN_SPRITE_SIDE or entry["h"] < MIN_SPRITE_SIDE:
                continue
            path = os.path.join(folder, lib_safe(entry["name"]) + ".png")
            if os.path.exists(path):
                sprites.append((atlas, entry["name"], np.array(Image.open(path).convert("RGBA"))))
    return sprites


def lib_safe(name: str) -> str:
    """Same file naming as extract_apk_ui.safe_name."""
    return re.sub(r"[^\w.\-]+", "_", name or "unnamed")


class Scaled:
    """Sprites resized once per scale; only the cheap background flattening depends on the template."""

    def __init__(self, sprites):
        self.sprites = sprites
        self.cache = {}

    def get(self, i: int, scale: float) -> np.ndarray:
        key = (i, scale)
        if key not in self.cache:
            self.cache[key] = lib.scale_sprite(self.sprites[i][2], scale)
        return self.cache[key]


def sizes_compatible(sw: int, sh: int, tw: int, th: int) -> bool:
    return min(sw, tw) >= MIN_SIZE_RATIO * max(sw, tw) and min(sh, th) >= MIN_SIZE_RATIO * max(sh, th)


def score_sprite(scaled_rgba: np.ndarray, template_gray: np.ndarray, background) -> float | None:
    gray = lib.to_gray(lib.sprite_on_background(scaled_rgba, background))
    return lib.best_score(template_gray, gray)


def best_sprites(scaled: Scaled, template_rgb: np.ndarray, top: int) -> list[tuple]:
    background = lib.median_color(template_rgb)
    template_gray = lib.to_gray(template_rgb)
    th, tw = template_gray.shape
    results = []
    for i, (atlas, name, _) in enumerate(scaled.sprites):
        best = None
        for scale in SCALES:
            rgba = scaled.get(i, scale)
            if not sizes_compatible(rgba.shape[1], rgba.shape[0], tw, th):
                continue
            score = score_sprite(rgba, template_gray, background)
            if score is not None and (best is None or score > best[0]):
                best = (score, atlas, name, scale, i)
        if best:
            results.append(best)
    results.sort(reverse=True)
    return results[:top]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--extracted", required=True, help="folder written by extract_apk_ui.py")
    parser.add_argument("--templates", required=True, help="folder of 720p templates, e.g. app/src/main/assets/Kr")
    parser.add_argument("--names", help="comma-separated template files to match; default: every .png")
    parser.add_argument("--top", type=int, default=3, help="best sprites to show per template (default: 3)")
    parser.add_argument("--out-csv", help="write the best sprite per template here")
    args = parser.parse_args()

    templates = {f: lib.load_rgb(os.path.join(args.templates, f))
                 for f in sorted(os.listdir(args.templates)) if f.endswith(".png")}
    wanted = args.names.split(",") if args.names else list(templates)
    missing = [n for n in wanted if n not in templates]
    if missing:
        sys.exit(f"not in {args.templates}: {', '.join(missing)}")

    scaled = Scaled(load_sprites(args.extracted))
    print(f"{len(scaled.sprites)} sprites, {len(templates)} templates in the folder, {len(wanted)} to match")

    rows = []
    for name in wanted:
        found = best_sprites(scaled, templates[name], args.top)
        if not found:
            print(f"{name:40s} no sprite fits")
            rows.append((name, "", "", "", "", "", ""))
            continue
        for rank, (score, atlas, sprite, scale, _) in enumerate(found):
            print(f"{name if rank == 0 else '':40s} {score:.3f}  {atlas}/{sprite}  x{scale:g}")
        score, atlas, sprite, scale, i = found[0]
        # How well the best sprite also matches the other screens: a high value means a false match
        rgba = scaled.get(i, scale)
        others = []
        for other, image in templates.items():
            if other == name:
                continue
            gray = lib.to_gray(image)
            if not sizes_compatible(rgba.shape[1], rgba.shape[0], gray.shape[1], gray.shape[0]):
                continue
            s = score_sprite(rgba, gray, lib.median_color(image))
            if s is not None:
                others.append((s, other))
        others.sort(reverse=True)
        worst = others[0] if others else (None, "")
        if others:
            print(f"{'':40s} false-match check: highest in another template {worst[0]:.3f} ({worst[1]})")
        rows.append((name, f"{score:.3f}", atlas, sprite, scale,
                     "" if worst[0] is None else f"{worst[0]:.3f}", worst[1]))

    if args.out_csv:
        os.makedirs(os.path.dirname(os.path.abspath(args.out_csv)), exist_ok=True)
        with open(args.out_csv, "w", newline="", encoding="utf-8") as f:
            csv.writer(f).writerows([("template", "score", "atlas", "sprite", "scale",
                                      "highest_in_other_template", "other_template")] + rows)
        print(f"wrote {args.out_csv}")


if __name__ == "__main__":
    main()
