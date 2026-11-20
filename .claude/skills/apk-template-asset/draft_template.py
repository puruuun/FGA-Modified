#!/usr/bin/env python3
"""Draft a 720p template from an APK atlas sprite or from the game font, and score the draft.

Requires Pillow, numpy and opencv-python-headless. Run on Windows with `py -3 -E -P`.
The inputs come from extract_apk_ui.py.

Sprite mode - scale the atlas sprite to 720p the way a device screenshot shows it (bilinear to a
--device-height screen, then INTER_AREA down) and flatten it on the screen's background:

    draft_template.py --extracted build/apk-kr --sprite BattleUIAtlas/buttontxt_retire \
        --check app/src/main/assets/Kr/retreat_button.png --out build/draft/retreat_button.png

Crop that carries over to another server. Sprite names are the same on every server but the art
inside differs, so a box in pixels does not transfer; edge insets do. On a server that has the
template, --crop-like finds it inside the draft and prints the insets; on the server that lacks it,
--inset applies them to that server's draft of the same sprite:

    draft_template.py --extracted build/apk-kr --sprite PartyOrganizationAtlas/btn_supportform \
        --crop-like app/src/main/assets/Kr/support_region_tool.png        # prints "--inset 12,9,12,9"
    draft_template.py --extracted build/apk-tw --sprite PartyOrganizationAtlas/btn_supportform \
        --inset 12,9,12,9 --background 303030 --out build/draft/support_region_tool.png

Layered sprites - repeat --sprite, bottom layer first. --layers-like REF finds each layer in a
reference template and prints the offsets from the first layer (720p pixels); --offset dx,dy
(once per later layer) applies known ones. The canvas is the union of the layers; --crop-like,
--inset and --crop then apply to the composite.

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

import cv2
import numpy as np
from PIL import Image

sys.dont_write_bytecode = True  # no __pycache__ inside the skill folder
# Korean and Japanese text; a Korean Windows console defaults to cp949, which cannot print Japanese
for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, 'reconfigure'):
        _stream.reconfigure(encoding='utf-8')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import template_lib as lib  # noqa: E402


def parse_box4(value: str) -> tuple[int, int, int, int]:
    left, top, right, bottom = (int(v) for v in value.split(","))
    return left, top, right, bottom


def parse_box(value: str) -> tuple[int, int, int, int]:
    x, y, w, h = (int(v) for v in value.split(","))
    return x, y, w, h


def parse_pair(value: str) -> tuple[int, int]:
    dx, dy = (int(v) for v in value.split(","))
    return dx, dy


def merge_negative_values(argv: list[str]) -> list[str]:
    """crop_template's version for --region, extended to the options here that can start with a minus sign."""
    out, i = [], 0
    while i < len(argv):
        arg = argv[i]
        if arg in ("--offset", "--inset") and i + 1 < len(argv) and argv[i + 1].startswith("-"):
            out.append(f"{arg}={argv[i + 1]}")
            i += 2
            continue
        out.append(arg)
        i += 1
    return out


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


def load_layer(args, sprite: str) -> np.ndarray:
    atlas, _, name = sprite.partition("/")
    path = os.path.join(args.extracted, "sprites", atlas, name + ".png")
    if not os.path.exists(path):
        sys.exit(f"no sprite {sprite}: {path} does not exist")
    return lib.scale_sprite(np.array(Image.open(path).convert("RGBA")), args.scale, args.device_height)


def layer_offsets(args, layers, background, ref_rgb) -> list[tuple[int, int]]:
    """Offsets of every layer from the first: found in the reference, or the given --offset values."""
    if not args.layers_like:
        if len(args.offset) != len(layers) - 1:
            sys.exit(f"{len(layers)} layers need {len(layers) - 1} --offset (or --layers-like), got {len(args.offset)}")
        return [(0, 0)] + list(args.offset)
    ref = lib.to_gray(ref_rgb)
    positions = []
    for sprite, layer in zip(args.sprite, layers):
        # A layer is matched flattened on the reference's background, so its transparent margin is as flat as there
        found = lib.locate(ref, lib.to_gray(lib.sprite_on_background(layer, background)))
        if found is None:
            sys.exit(f"{sprite} and {args.layers_like} cannot be matched (flat image, or neither contains the other)")
        print(f"  layer {sprite}: score {found[0]:.3f} at {found[1]},{found[2]}")
        positions.append((found[1], found[2]))
    offsets = [(x - positions[0][0], y - positions[0][1]) for x, y in positions]
    print("layer offsets: " + " ".join(f"--offset {dx},{dy}" for dx, dy in offsets[1:]))
    return offsets


def crop_to_reference(draft: np.ndarray, ref_rgb: np.ndarray, path: str) -> np.ndarray:
    """Crop the draft to the box of the reference template and print the insets that reproduce it."""
    dh, dw = draft.shape[:2]
    rh, rw = ref_rgb.shape[:2]
    pad_w, pad_h = max(0, rw - dw), max(0, rh - dh)
    if pad_w or pad_h:
        # The padding only lets the match run; a dimension where the reference is larger is left uncropped
        print(f"  {os.path.basename(path)} is larger than the draft ({rw}x{rh} vs {dw}x{dh}): "
              f"{'width' if pad_w else ''}{' and ' if pad_w and pad_h else ''}{'height' if pad_h else ''} not cropped")
        draft_for_match = cv2.copyMakeBorder(draft, pad_h // 2, pad_h - pad_h // 2, pad_w // 2,
                                             pad_w - pad_w // 2, cv2.BORDER_REPLICATE)
    else:
        draft_for_match = draft
    found = lib.locate(lib.to_gray(draft_for_match), lib.to_gray(ref_rgb))
    if found is None:
        sys.exit(f"cannot match {path} inside the draft (flat image)")
    score, x, y = found
    left, top = (0 if pad_w else x), (0 if pad_h else y)
    width, height = (dw if pad_w else rw), (dh if pad_h else rh)
    insets = (left, top, dw - left - width, dh - top - height)
    text = ",".join(str(v) for v in insets)
    print(f"crop-like {os.path.basename(path)}: score {score:.3f}, insets {text}  (--inset {text})")
    return draft[top:top + height, left:left + width]


def apply_inset(draft: np.ndarray, inset: tuple[int, ...]) -> np.ndarray:
    left, top, right, bottom = inset
    h, w = draft.shape[:2]
    if min(inset) < 0 or left + right >= w or top + bottom >= h:
        sys.exit(f"--inset {left},{top},{right},{bottom} leaves nothing of the {w}x{h} draft")
    return draft[top:h - bottom, left:w - right]


def draft_sprite(args, background, reference) -> np.ndarray:
    layers = [load_layer(args, sprite) for sprite in args.sprite]
    if len(layers) == 1:
        if args.offset or args.layers_like:
            sys.exit("--offset and --layers-like need more than one --sprite")
        canvas = layers[0]
    else:
        canvas = lib.composite_layers(layers, layer_offsets(args, layers, background, reference))
    draft = lib.sprite_on_background(canvas, background)
    if args.crop:
        x, y, w, h = args.crop
        if x + w > draft.shape[1] or y + h > draft.shape[0]:
            sys.exit(f"--crop {x},{y},{w},{h} runs outside the {draft.shape[1]}x{draft.shape[0]} draft")
        draft = draft[y:y + h, x:x + w]
    elif args.inset:
        draft = apply_inset(draft, args.inset)
    elif args.crop_like:
        draft = crop_to_reference(draft, lib.load_rgb(args.crop_like), args.crop_like)
    return draft


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


def parse_scale(text: str):
    parts = text.split(",")
    try:
        values = tuple(float(p) for p in parts)
    except ValueError:
        raise argparse.ArgumentTypeError(f"not a number: {text}")
    if len(values) > 2:
        raise argparse.ArgumentTypeError("--scale takes X or X,Y")
    return values[0] if len(values) == 1 else values


def scale_text(scale) -> str:
    return ",".join(f"{v:g}" for v in scale) if isinstance(scale, tuple) else f"{scale:g}"


def main() -> None:
    ct = lib.crop_template_module()
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--sprite", action="append", default=[], metavar="ATLAS/NAME",
                        help="sprite mode: a sprite of --extracted; repeat for layers, bottom layer first")
    parser.add_argument("--extracted", help="folder written by extract_apk_ui.py (sprite mode)")
    parser.add_argument("--device-height", type=int, default=lib.DEFAULT_DEVICE_HEIGHT, metavar="PX",
                        help="sprite mode: height of the device screen the sprite is drawn on before FGA shrinks it "
                             "to 720p; 720 = one INTER_AREA step, the behaviour before #556 (default: %(default)s)")
    parser.add_argument("--scale", type=parse_scale, default=lib.DEFAULT_SPRITE_SCALE,
                        help="sprite to 720p scale, a number or X,Y for a sprite stretched to its widget "
                             "(default: %(default)s, measured on KR 8.1.0)")
    parser.add_argument("--crop", type=parse_box, metavar="X,Y,W,H",
                        help="keep only this box of the scaled sprite, in 720p pixels")
    parser.add_argument("--crop-like", metavar="REF.png",
                        help="sprite mode: find this template in the draft, print its edge insets and crop to its box")
    parser.add_argument("--inset", type=parse_box4, metavar="L,T,R,B",
                        help="sprite mode: crop this many 720p pixels off each edge of the draft")
    parser.add_argument("--layers-like", metavar="REF.png",
                        help="sprite mode: find each layer in this template and print the layer offsets")
    parser.add_argument("--offset", action="append", default=[], type=parse_pair, metavar="DX,DY",
                        help="sprite mode: a layer's offset from the first layer, 720p pixels; one per later layer")
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
    args = parser.parse_args(merge_negative_values(ct.merge_negative_values(sys.argv[1:])))

    if bool(args.sprite) == bool(args.text):
        sys.exit("give exactly one of --sprite and --text")
    if args.sprite and not args.extracted:
        sys.exit("--sprite needs --extracted")
    if args.text and not args.font:
        sys.exit("--text needs --font")
    if sum(bool(v) for v in (args.crop, args.inset, args.crop_like)) > 1:
        sys.exit("give only one of --crop, --inset and --crop-like")
    if args.text and (args.crop_like or args.inset or args.layers_like or args.offset):
        sys.exit("--crop-like, --inset, --layers-like and --offset are for sprite mode")
    if args.layers_like and args.offset:
        sys.exit("give --layers-like or --offset, not both")

    region_args = SimpleNamespace(region=args.region, anchor=args.anchor, anchor_y=args.anchor_y) \
        if args.region else None
    checks = load_checks(args.check, region_args)
    # A template to crop or layer by also shows what the screen behind the sprite looks like
    reference_path = args.layers_like or args.crop_like
    reference = lib.load_rgb(reference_path) if reference_path else None
    if args.fit and not checks:
        sys.exit("--fit needs at least one --check")
    if not args.background and not checks and reference is None:
        sys.exit("a missing --background needs at least one --check, --layers-like or --crop-like")

    first = next(iter(checks.values()), None)
    if args.background:
        background = lib.parse_color(args.background)
    else:
        source = first if first is not None else reference
        background = lib.median_color(source)
        print(f"background {''.join(f'{v:02X}' for v in background)} "
              f"(median of the {'first --check' if first is not None else 'reference template'})")

    if args.sprite:
        draft = draft_sprite(args, background, reference)
        print(f"draft {draft.shape[1]}x{draft.shape[0]} from {' + '.join(args.sprite)} at x{scale_text(args.scale)}, "
              f"device {args.device_height}p")
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
