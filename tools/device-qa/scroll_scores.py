"""Scores the weekly-route and FP-sell scroll-end boxes over the reference frames.

beta.13 (#122): every box follows the right edge plus the wide inset (171 at 1440p), and frames wider than 21:9
are cropped to a centred 21:9 picture first, as FgoGameAreaManager (isUltraWide) does on the device.
Usage: py -3 tools/device-qa/scroll_scores.py [--old] [--out docs/qa/1.7.0-beta.13-scroll-scores.json]
--old reproduces the beta.6 centre-anchored boxes without the crop, to check this script against 1.7.0-beta.6-scroll-scores.json.
"""
import argparse, json
from pathlib import Path
import cv2, numpy as np

ROOT = Path(__file__).resolve().parents[2]
WIDE_EDGE_INSET = 171
THRESHOLDS = {"weekly": 0.60, "fp": 0.72}
NEW_BOXES = {  # (x from the right edge, y, w, h) at 1440p
    ("fp", "bottom"): (-554, 1330, 72, 34),
    ("weekly", "top"): (-82, 178, 74, 38),
    ("weekly", "bottom"): (-82, 1152, 74, 34),
}
OLD_WIDE = {("fp", "bottom"): (956, 1330, 72, 34), ("weekly", "top"): (1430, 178, 72, 42), ("weekly", "bottom"): (1430, 1152, 72, 34)}


def gray(path, to720=True):
    im = cv2.imdecode(np.fromfile(str(path), dtype=np.uint8), cv2.IMREAD_COLOR)
    g = cv2.cvtColor(im, cv2.COLOR_BGR2GRAY)
    if to720 and g.shape[0] != 720:  # raw captures are matched at 720p height
        g = cv2.resize(g, (round(g.shape[1] * 720 / g.shape[0]), 720), interpolation=cv2.INTER_AREA)
    return g


def crop_ultra_wide(im):
    """Frames wider than 21:9 are shown as a centred 21:9 picture on the device."""
    h, w = im.shape
    full = round(h * 21 / 9)
    if w / h > 21 / 9 and w > full:
        x = (w - full) // 2
        return im[:, x:x + full]
    return im


def score(im, screen, end, tpl, old=False):
    h, w = im.shape
    wide = w > 1440 * h / 720
    if not old:
        im = crop_ultra_wide(im); w = im.shape[1]
    if old and wide:
        bx, by, bw, bh = OLD_WIDE[(screen, end)]
        left = (w + bx) // 2
    else:
        bx, by, bw, bh = NEW_BOXES[(screen, end)]
        left = (2 * w - (WIDE_EDGE_INSET if wide else 0) + bx) // 2
    top = by // 2
    region = im[top:top + (bh + 1) // 2, left:left + bw // 2]
    r = cv2.matchTemplate(region, tpl, cv2.TM_CCOEFF_NORMED)
    r = r[np.isfinite(r)]
    return float(r.max()) if r.size else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--old", action="store_true")
    ap.add_argument("--out")
    a = ap.parse_args()
    tpls = {e: gray(ROOT / f"app/src/main/assets/En/support_scrollbar_{e}.png", to720=False) for e in ("top", "bottom")}
    base = json.loads((ROOT / "docs/qa/1.7.0-beta.6-scroll-scores.json").read_text(encoding="utf-8"))
    extra = [{"file": "docs/reference-images/Kr/fp-sell/list-top-2340.webp", "screen": "fp"}]
    frames, summary = [], {}
    # Frames outside docs/reference-images are the owner's uncommitted desktop captures; they cannot be re-measured here.
    committed = [f for f in base["frames"] if f["file"].startswith("docs/")]
    for f in committed + ([] if a.old else extra):
        im = gray(ROOT / f["file"])
        ends = ("bottom",) if f["screen"] == "fp" else ("top", "bottom")
        scores = {e: score(im, f["screen"], e, tpls[e], a.old) for e in ends}
        wide = im.shape[1] / im.shape[0] > 2
        thr = THRESHOLDS[f["screen"]]
        frames.append({"file": f["file"], "screen": f["screen"], "wide": wide,
                       "scores": {k: round(v, 4) for k, v in scores.items()},
                       "ends": {k: v >= thr for k, v in scores.items()}})
    for f in frames:
        for e, s in f["scores"].items():
            key = f"{f['screen']}-{e}-{'wide' if f['wide'] else '16:9'}"
            d = summary.setdefault(key, {"end": [], "non": []})
            d["end" if f["ends"][e] else "non"].append(s)
    out = {k: {"endMinimum": min(v["end"]) if v["end"] else None, "nonEndMaximum": max(v["non"]) if v["non"] else None,
               "endFrames": len(v["end"]), "nonEndFrames": len(v["non"])} for k, v in sorted(summary.items())}
    result = {"method": "beta.13: boxes follow the right edge plus the wide inset on every ratio; frames wider than 21:9 are cropped to a centred 21:9 picture; 720p grayscale TM_CCOEFF_NORMED with the upstream support_scrollbar_top/bottom templates. Frames as in 1.7.0-beta.6-scroll-scores.json plus the KR 19.5:9 FP list top (negative case)." if not a.old else "old",
              "boxes1440p": {f"{s}-{e}": f"x {b[0]} (right edge + wide inset) y {b[1]} {b[2]}x{b[3]}" for (s, e), b in NEW_BOXES.items()},
              "thresholds": THRESHOLDS, "summary": out, "frames": frames}
    if a.out:
        Path(a.out).write_text(json.dumps(result, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    else:
        print(json.dumps(out, indent=1))
        diffs = [(f["file"], e, s, g["scores"][e]) for f, g in zip(committed, frames) for e, s in f["scores"].items() if abs(s - g["scores"][e]) > 0.002] if a.old else []
        print("diffs vs beta.6:", len(diffs), diffs[:10])


main()
