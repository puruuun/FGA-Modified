#!/usr/bin/env python3
"""Draft a 720p template from an APK atlas sprite or from the game font, and score the draft.

Requires Pillow, numpy and opencv-python-headless. Run on Windows with `py -3 -E -P`.
The inputs come from extract_apk_ui.py.

Sprite mode - scale the atlas sprite to 720p and flatten it on the screen's background:

    draft_template.py --extracted build/apk-kr --sprite BattleUIAtlas/buttontxt_retire \
        --check app/src/main/assets/Kr/retreat_button.png --out build/draft/retreat_button.png

Text mode - render a label with the game font (fit the size on a check image first):

    draft_template.py --font build/apk-kr/fonts/FGO-Main-Font.otf --text "닫기" --color FFFFFF \
        --fit --check app/src/main/assets/Kr/close.png

--check takes a template or a 720p crop (the smaller image is searched inside the larger), or,
with --region and --anchor, a full screenshot whose search region is resolved like
extract-template-asset does. Each check prints the score at four half-pixel shifts. Draft
templates are only a start: check them against a real screenshot of that server before they go
into app/src/main/assets/.
"""

import argparse
import os
import sys
from types import SimpleNamespace

import numpy as np
from PIL import Image

sys.dont_write_bytecode = True  # no __pycache__ inside the skill folder
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import template_lib as lib  # noqa: E402


def parse_box(value: str) -> tuple[int, int, int, int]:
    x, y, w, h = (int(v) for v in value.split(","))
    return x, y, w, h


def estimate_colors(region: np.ndarray) -> tuple[tuple, tuple]:
    """Background = median; text = mean of the 15% of pixels farthest from it in gray."""
    flat = region.reshape(-1, 3).astype(np.float32)
    background = np.median(flat, axis=0)
    weights = np.array([0.299, 0.587, 0.114], np.float32)
    distance = np.abs(flat @ weights - float(background @ weights))
    ink = flat[distance >= np.quantile(distance, 0.85)].mean(axis=0)
    return tuple(int(v) for v in ink), tuple(int(v) for v in background)


def load_checks(paths: list[str], region_args) -> dict[str, np.ndarray]:
    checks = {}
    for path in paths:
        if region_args is not None:
            checks[path] = lib.load_search_region(path, region_args)
        else:
            checks[path] = lib.load_rgb(path)
    return checks


def draft_sprite(args, background) -> np.ndarray:
    atlas, _, name = args.sprite.partition("/")
    path = os.path.join(args.extracted, "sprites", atlas, name + ".png")
    if not os.path.exists(path):
        sys.exit(f"no sprite {args.sprite}: {path} does not exist")
    rgba = np.array(Image.open(path).convert("RGBA"))
    scaled = lib.scale_sprite(rgba, args.scale)
    if args.crop:
        x, y, w, h = args.crop
        if x + w > scaled.shape[1] or y + h > scaled.shape[0]:
            sys.exit(f"--crop {x},{y},{w},{h} runs outside the {scaled.shape[1]}x{scaled.shape[0]} scaled sprite")
        scaled = scaled[y:y + h, x:x + w]
    return lib.sprite_on_background(scaled, background)


def draft_text(args, color, background, checks) -> tuple[np.ndarray, float]:
    size = args.size
    if args.fit:
        # The worst shift decides, because the real screenshot lands on an arbitrary sub-pixel offset
        first = next(iter(checks.values()))
        fits = []
        for candidate in np.arange(12, 40.01, 0.5):
            result = lib.shift_scores(first, lib.render(args.font, args.text, color, candidate, args.stroke, background))
            if result:
                fits.append((min(result), float(candidate)))
        if not fits:
            sys.exit("no font size between 12 and 40 gives a usable score on the first --check")
        fits.sort(reverse=True)
        print("best sizes (worst-shift score):", ", ".join(f"{s:g} -> {score:.3f}" for score, s in fits[:3]))
        size = fits[0][1]
    return lib.render(args.font, args.text, color, size, args.stroke, background), size


def main() -> None:
    ct = lib.crop_template_module()
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--sprite", metavar="ATLAS/NAME", help="sprite mode: a sprite of --extracted")
    parser.add_argument("--extracted", help="folder written by extract_apk_ui.py (sprite mode)")
    parser.add_argument("--scale", type=float, default=lib.DEFAULT_SPRITE_SCALE,
                        help="sprite to 720p scale (default: %(default)s, measured on KR 8.1.0)")
    parser.add_argument("--crop", type=parse_box, metavar="X,Y,W,H",
                        help="keep only this box of the scaled sprite, in 720p pixels")
    parser.add_argument("--font", help="text mode: font file from extract_apk_ui.py")
    parser.add_argument("--text", help="text mode: the label, without color markup")
    parser.add_argument("--color", help="text mode: RRGGBB; default: estimated from the first --check")
    parser.add_argument("--size", type=float, default=25, help="text mode: font size in 720p pixels (default: 25)")
    parser.add_argument("--fit", action="store_true", help="text mode: pick --size by the best score on the first --check")
    parser.add_argument("--stroke", type=float, default=0, help="text mode: dark outline width in 720p pixels")
    parser.add_argument("--background", help="RRGGBB behind the draft; default: median of the first --check")
    parser.add_argument("--check", action="append", default=[], metavar="IMAGE",
                        help="template, 720p crop or (with --region) screenshot to score against; repeatable")
    parser.add_argument("--region", type=lambda s: [int(v) for v in s.split(",")], metavar="X,Y,W,H",
                        help="Region(...) from locations/*.kt in 1440p script coords; makes --check a screenshot")
    parser.add_argument("--anchor", choices=["left", "center", "right"], default="left")
    parser.add_argument("--anchor-y", choices=["top", "bottom"], default="top")
    parser.add_argument("--out", help="write the draft here (720p PNG)")
    args = parser.parse_args(ct.merge_negative_values(sys.argv[1:]))

    if bool(args.sprite) == bool(args.text):
        sys.exit("give exactly one of --sprite and --text")
    if args.sprite and not args.extracted:
        sys.exit("--sprite needs --extracted")
    if args.text and not args.font:
        sys.exit("--text needs --font")

    region_args = SimpleNamespace(region=args.region, anchor=args.anchor, anchor_y=args.anchor_y) \
        if args.region else None
    checks = load_checks(args.check, region_args)
    if (args.fit or not args.background) and not checks:
        sys.exit("--fit and a missing --background need at least one --check")

    first = next(iter(checks.values()), None)
    if args.background:
        background = lib.parse_color(args.background)
    else:
        background = lib.median_color(first)
        print(f"background {''.join(f'{v:02X}' for v in background)} (median of the first --check)")

    if args.sprite:
        draft = draft_sprite(args, background)
        print(f"draft {draft.shape[1]}x{draft.shape[0]} from {args.sprite} at x{args.scale:g}")
    else:
        if args.color:
            color = lib.parse_color(args.color)
        elif first is not None:
            color, _ = estimate_colors(first)
            print(f"color {''.join(f'{v:02X}' for v in color)} (estimated from the first --check)")
        else:
            sys.exit("--color or at least one --check is needed")
        draft, size = draft_text(args, color, background, checks)
        print(f"draft {draft.shape[1]}x{draft.shape[0]} at size {size:g}")

    for path, image in checks.items():
        result = lib.shift_scores(image, draft)
        shown = "no usable score (flat image)" if result is None else \
            " ".join(f"{s:.3f}" for s in result) + f"   worst {min(result):.3f}"
        print(f"  {os.path.basename(path)}: {shown}")

    if args.out:
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        Image.fromarray(draft).save(args.out)
        print(f"wrote {args.out}")


if __name__ == "__main__":
    main()
