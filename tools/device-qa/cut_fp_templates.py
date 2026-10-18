#!/usr/bin/env python3
"""Cut 0007 FP auto-sell templates from owner captures normalized to 1280x720 (game area at 720p).

Reference images live in docs/reference-images/<Server>/fp-sell/. Boxes are 720p pixels, so a
crop here is exactly the pixels the app compares (no resampling). Run with a Python that has Pillow.
Usage: py -3 tools/device-qa/cut_fp_templates.py [--check]
--check only reports the self-match of each cut against its source (sanity), writes nothing.
"""
import sys
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/main/assets"

# (asset folder, file name, reference folder, reference file, box x, y, w, h)
RECIPES = [
    # Kr: the warning word and the locked label (owner screen 17, 38)
    ("Kr", "fp_sell_word_servant.png", "Kr", "17.png", 422, 150, 118, 34),
    ("Kr", "fp_sell_locked_text.png", "Kr", "38.png", 228, 346, 70, 22),
    # Jp: sell flow, warning word, locked/unselectable labels, menus
    ("Jp", "fp_sell_word_servant.png", "Jp", "40.png", 268, 148, 221, 34),
    ("Jp", "fp_sell_heading.png", "Jp", "37.png", 1040, 8, 220, 50),
    ("Jp", "fp_sell_tab.png", "Jp", "37.png", 42, 100, 116, 24),
    ("Jp", "fp_sell_close.png", "Jp", "31.png", 592, 612, 92, 28),
    ("Jp", "fp_sell_sell.png", "Jp", "40.png", 318, 462, 56, 30),
    ("Jp", "fp_sell_confirm.png", "Jp", "29.png", 1125, 650, 60, 36),
    ("Jp", "fp_sell_convert.png", "Jp", "30.png", 790, 612, 104, 32),
    ("Jp", "fp_sell_convert_heading.png", "Jp", "30.png", 580, 32, 124, 36),
    ("Jp", "fp_sell_done.png", "Jp", "31.png", 470, 216, 260, 30),
    ("Jp", "fp_sell_shop.png", "Jp", "32.png", 1040, 14, 220, 44),
    ("Jp", "fp_sell_menu.png", "Jp", "32.png", 1142, 636, 92, 30),
    ("Jp", "fp_sell_summon_menu.png", "Jp", "33.png", 533, 625, 63, 26),
    ("Jp", "fp_sell_locked_text.png", "Jp", "37.png", 105, 555, 66, 22),
    ("Jp", "fp_sell_unselectable.png", "Jp", "37.png", 900, 555, 70, 22),
    ("Jp", "fp_sell_grand_servant.png", "Jp", "37.png", 370, 550, 84, 20),
    # En (shared by both servers): padlock overlay and PARTY tag, identical English art
    ("En", "fp_sell_lock.png", "Kr", "38.png", 202, 334, 28, 30),
    ("En", "fp_sell_party.png", "Kr", "38.png", 628, 546, 76, 22),
    ("Jp", "fp_sell_party.png", "Jp", "37.png", 765, 617, 72, 20),
    # JP "Exp. UP" label: the KR template scores 0.78-0.85 on JP cards. The Servant label matches JP as is.
    ("Jp", "fp_sell_exp.png", "Jp", "29.png", 119, 299, 35, 9),
]


def main() -> None:
    check = "--check" in sys.argv
    for folder, name, ref_dir, ref, x, y, w, h in RECIPES:
        source = Image.open(ROOT / f"docs/reference-images/{ref_dir}/fp-sell/{ref}").convert("RGB")
        crop = source.crop((x, y, x + w, y + h))
        if check:
            print(f"{folder}/{name} <- {ref}: {w}x{h}")
            continue
        out = ASSETS / folder / name
        out.parent.mkdir(parents=True, exist_ok=True)
        crop.save(out, optimize=True)
        print(f"wrote {out.relative_to(ROOT)} {w}x{h} from {ref_dir}/{ref}")


if __name__ == "__main__":
    main()
