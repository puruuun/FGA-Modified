#!/usr/bin/env python3
"""Cut 0008 skill / append level templates from the owner captures normalized to 1280x720.

Sources are docs/reference-images/<Server>/skill-level/<NN>.png (see normalize_skill_level.py).
Boxes are 720p pixels, so a crop is exactly the pixels the app compares. Needs Pillow.
Usage: py -3 tools/device-qa/cut_skill_level_templates.py [--check]
"""
import sys
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/main/assets"

# (asset folder, file name, reference folder, reference file, box x, y, w, h)
RECIPES = [
    # Title banners, cut from the part that tells skill and append apart (the KR skill title is the tail of the append one).
    ("Jp", "skill_level_title.png", "Jp", "02.png", 704, 14, 266, 48),
    ("Kr", "skill_level_title.png", "Kr", "15.png", 1010, 14, 250, 50),
    ("Jp", "append_level_title.png", "Jp", "08.png", 792, 14, 190, 48),
    ("Kr", "append_level_title.png", "Kr", "13.png", 872, 14, 160, 50),
    # Servant re-select button: only on screen once a servant is selected.
    ("Jp", "skill_level_reselect.png", "Jp", "02.png", 194, 14, 112, 60),
    ("Kr", "skill_level_reselect.png", "Kr", "15.png", 194, 14, 112, 60),
    # Instruction band, the two words that differ: enhance / unlock.
    ("Jp", "skill_level_band_enhance.png", "Jp", "02.png", 411, 109, 38, 30),
    ("Jp", "skill_level_band_unlock.png", "Jp", "08.png", 411, 109, 38, 30),
    ("Kr", "skill_level_band_enhance.png", "Kr", "15.png", 396, 112, 56, 30),
    ("Kr", "skill_level_band_unlock.png", "Kr", "13.png", 396, 112, 56, 30),
    # Bottom-right button: unlock word (guard), and the JP max-level red label.
    ("Jp", "skill_level_unlock_button.png", "Jp", "08.png", 1074, 652, 68, 40),
    ("Kr", "skill_level_unlock_button.png", "Kr", "13.png", 1104, 652, 72, 40),
    ("Jp", "skill_level_max_button.png", "Jp", "07.png", 1042, 660, 196, 30),
    # Confirm dialog: shared watermark (En art), padlock overlay, and the title tails.
    ("En", "skill_level_confirmation.png", "Jp", "04.png", 540, 625, 206, 14),
    ("En", "skill_level_lock.png", "Kr", "13.png", 455, 238, 38, 46),
    ("Jp", "skill_level_dialog_title.png", "Jp", "04.png", 652, 144, 68, 34),
    ("Kr", "skill_level_dialog_title.png", "Kr", "16.png", 630, 144, 86, 34),
    ("Jp", "append_level_dialog_title.png", "Jp", "18.png", 732, 118, 68, 36),
    ("Kr", "append_level_dialog_title.png", "Kr", "17.png", 690, 118, 64, 36),
    # Result screen prompt (touch the screen), the same for skill and append.
    ("Jp", "skill_level_result.png", "Jp", "05.png", 413, 658, 455, 36),
    ("Kr", "skill_level_result.png", "Kr", "11.png", 475, 658, 324, 30),
]


def main() -> None:
    check = "--check" in sys.argv
    for folder, name, ref_dir, ref, x, y, w, h in RECIPES:
        source = Image.open(ROOT / f"docs/reference-images/{ref_dir}/skill-level/{ref}").convert("RGB")
        crop = source.crop((x, y, x + w, y + h))
        if check:
            print(f"{folder}/{name} <- {ref_dir}/{ref}: {w}x{h}")
            continue
        out = ASSETS / folder / name
        out.parent.mkdir(parents=True, exist_ok=True)
        crop.save(out, optimize=True)
        print(f"wrote {out.relative_to(ROOT)} {w}x{h} from {ref_dir}/{ref}")


if __name__ == "__main__":
    main()
