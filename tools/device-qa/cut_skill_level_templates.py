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
    # skill_level_label.png / append_level_label.png (centre-left label with the chevron icon, issue #33) are cut from the
    # owner's 2560x1440 captures at 720p and are not part of these recipes.
    # Servant re-select button: only on screen once a servant is selected.
    ("Jp", "skill_level_reselect.png", "Jp", "02.png", 194, 14, 112, 60),
    ("Kr", "skill_level_reselect.png", "Kr", "15.png", 194, 14, 112, 60),
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
    # Isolated digit candidates for the conservative nine fallback (owner frames only).
    ("En", "skill_level_digit_0.png", "Jp", "31.png", 794, 312, 34, 48),
    ("En", "skill_level_digit_1.png", "Jp", "18.png", 540, 268, 18, 41),
    ("En", "skill_level_digit_2.png", "Jp", "18.png", 755, 262, 34, 48),
    ("En", "skill_level_digit_3.png", "Kr", "16.png", 538, 318, 29, 41),
    ("En", "skill_level_digit_4.png", "Jp", "04.png", 539, 318, 29, 41),
    ("En", "skill_level_digit_5.png", "Jp", "25.png", 539, 319, 28, 41),
    ("En", "skill_level_digit_6.png", "Kr", "26.png", 539, 319, 28, 41),
    ("En", "skill_level_digit_7.png", "Kr", "28.png", 539, 319, 28, 41),
    ("En", "skill_level_digit_8.png", "Kr", "27.png", 539, 319, 28, 41),
    ("En", "skill_level_digit_9.png", "Jp", "31.png", 538, 319, 29, 41),
    ("En", "skill_level_digit_9_kr.png", "Kr", "29.png", 538, 319, 29, 41),
    ("En", "skill_level_digit_9_next.png", "Jp", "32.png", 754, 312, 34, 49),

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
