#!/usr/bin/env python3
"""Score template drafts on a folder of screenshots the way FGA matches, and compare with the existing templates.

Requires Pillow, numpy and opencv-python-headless. Run on Windows with `py -3 -E -P`.
Each screenshot goes through FGA's pipeline: game area (extract-template-asset's crop_template),
INTER_AREA to 720p height, grayscale; the template is read as grayscale; TM_CCOEFF_NORMED; best
score. "Positives" are the screenshots where the existing template scores at least --positive
(default 0.85): a draft must score about as high there. Per template the table gives the number
of positives, the existing template's minimum, the draft's minimum and mean on positives, and the
highest draft score on a screenshot where the existing template is below --negative (default
0.6), with that screenshot's name.

    score_screens.py --templates app/src/main/assets/Kr --drafts build/drafts \\
        --screens docs/reference-images/Kr --out-csv build/score.csv

Two limits when reading the numbers. FGA searches only the Region a script gives, not the whole
game area, so the false scores here are an upper bound of what the app would see. And each call
has its own similarity threshold, which is often not the 0.80 default: compare a draft's minimum
on positives with the threshold of the call that uses the template, and its highest false score
with the same threshold.
"""

import argparse
import csv
import os
import sys

import cv2
import numpy as np

sys.dont_write_bytecode = True  # no __pycache__ inside the skill folder
# Korean and Japanese text; a Korean Windows console defaults to cp949, which cannot print Japanese
for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, 'reconfigure'):
        _stream.reconfigure(encoding='utf-8')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import template_lib as lib  # noqa: E402

EXTENSIONS = (".png", ".webp", ".jpg", ".jpeg")


def screenshot_paths(folder: str) -> list[str]:
    found = []
    for root, _, files in os.walk(folder):
        found += [os.path.join(root, f) for f in files if f.lower().endswith(EXTENSIONS)]
    return sorted(found)


def game_area_gray(path: str, ct) -> np.ndarray | None:
    image = cv2.imdecode(np.fromfile(path, np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        return None
    ax, ay, aw, ah = ct.game_area(image.shape[1], image.shape[0])
    area = image[ay:ay + ah, ax:ax + aw]
    if ah != ct.IMAGE_H:
        area = cv2.resize(area, (round(aw * ct.IMAGE_H / ah), ct.IMAGE_H), interpolation=cv2.INTER_AREA)
    return cv2.cvtColor(area, cv2.COLOR_BGR2GRAY)


def best(screen: np.ndarray, template: np.ndarray) -> float | None:
    if template.shape[0] > screen.shape[0] or template.shape[1] > screen.shape[1]:
        return None
    return float(cv2.minMaxLoc(cv2.matchTemplate(screen, template, cv2.TM_CCOEFF_NORMED))[1])


def read_gray(path: str) -> np.ndarray:
    image = cv2.imdecode(np.fromfile(path, np.uint8), cv2.IMREAD_GRAYSCALE)
    if image is None:
        sys.exit(f"cannot read {path}")
    return image


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--templates", required=True, help="folder of the existing templates, e.g. app/src/main/assets/Kr")
    parser.add_argument("--drafts", required=True, help="folder of drafts named like the templates (NAME.png)")
    parser.add_argument("--screens", required=True, help="folder of screenshots (searched recursively)")
    parser.add_argument("--names", help="comma-separated template names, with or without .png; default: every draft")
    parser.add_argument("--positive", type=float, default=0.85,
                        help="existing score from which a screenshot counts as a positive (default: %(default)s)")
    parser.add_argument("--negative", type=float, default=0.6,
                        help="existing score below which a screenshot counts as a negative (default: %(default)s)")
    parser.add_argument("--out-csv", help="write every score here: template, screenshot, existing, draft")
    args = parser.parse_args()

    if args.names:
        names = [n if n.endswith(".png") else n + ".png" for n in args.names.split(",")]
    else:
        names = sorted(f for f in os.listdir(args.drafts) if f.endswith(".png"))
    pairs = {}
    for name in names:
        for folder in (args.templates, args.drafts):
            if not os.path.exists(os.path.join(folder, name)):
                sys.exit(f"{os.path.join(folder, name)} does not exist")
        pairs[name[:-4]] = (read_gray(os.path.join(args.templates, name)), read_gray(os.path.join(args.drafts, name)))

    ct = lib.crop_template_module()
    paths = screenshot_paths(args.screens)
    rows = []
    unreadable = 0
    for path in paths:
        screen = game_area_gray(path, ct)
        if screen is None:
            unreadable += 1
            continue
        shown = os.path.relpath(path, args.screens).replace(os.sep, "/")
        for name, (existing, draft) in pairs.items():
            a, b = best(screen, existing), best(screen, draft)
            if a is not None and b is not None:
                rows.append((name, shown, a, b))
    print(f"{len(paths) - unreadable} screenshots ({unreadable} unreadable), {len(pairs)} templates")

    if args.out_csv:
        os.makedirs(os.path.dirname(os.path.abspath(args.out_csv)), exist_ok=True)
        with open(args.out_csv, "w", newline="", encoding="utf-8") as f:
            csv.writer(f).writerows([("template", "screenshot", "existing", "draft")]
                                    + [(n, s, f"{a:.4f}", f"{b:.4f}") for n, s, a, b in rows])

    print(f"{'template':24s} {'pos':>4s} {'existing min':>12s} {'draft min':>10s} {'draft mean':>10s}  "
          f"highest draft score where existing < {args.negative:g}")
    for name in pairs:
        mine = [r for r in rows if r[0] == name]
        positives = [r for r in mine if r[2] >= args.positive]
        negatives = [r for r in mine if r[2] < args.negative]
        worst = max(negatives, key=lambda r: r[3], default=None)
        false = f"{worst[3]:.3f} on {worst[1]}" if worst else "no negative screenshot"
        if positives:
            print(f"{name:24s} {len(positives):4d} {min(r[2] for r in positives):12.3f} "
                  f"{min(r[3] for r in positives):10.3f} {np.mean([r[3] for r in positives]):10.3f}  {false}")
        else:
            print(f"{name:24s}    0 {'-':>12s} {'-':>10s} {'-':>10s}  {false}")


if __name__ == "__main__":
    main()
