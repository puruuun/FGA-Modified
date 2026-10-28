"""Reproduce SupportPortraitTemplate's bilinear resize/crop, with a mandatory shipped-image check.

Requires opencv-python and numpy. Run --check first. A failed check never writes an asset.
Usage: python tools/chaldea/make_support_portrait.py --check
       python tools/chaldea/make_support_portrait.py f_40001300.png OUTPUT.png
"""
import argparse
import re
import urllib.request
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
KOTLIN = ROOT / "app/src/main/java/io/github/fate_grand_automata/util/portraits/ServantPortraitMetadata.kt"
BLOCK = KOTLIN.read_text(encoding="utf-8").split("object SupportPortraitCrop {", 1)[1].split("}", 1)[0]
CONSTANTS = {name: int(value) for name, value in re.findall(r"const val (\w+) = (\d+)", BLOCK)}


def portrait(face):
    if not re.fullmatch(r"f_\d+\.png", face):
        raise ValueError("Expected an Atlas face filename")
    with urllib.request.urlopen(f"https://static.atlasacademy.io/JP/Faces/{face}", timeout=30) as response:
        source = cv2.imdecode(np.frombuffer(response.read(), np.uint8), cv2.IMREAD_UNCHANGED)
    c = CONSTANTS
    if source is None or source.shape[:2] != (c["SOURCE_SIZE"], c["SOURCE_SIZE"]):
        raise ValueError("Unexpected Atlas face dimensions")
    # Same bilinear upscaling and 720p crop as SupportPortraitTemplate.create in ServantPortraitImporter.kt.
    resized = cv2.resize(source, (c["RESIZED_SIZE"], c["RESIZED_SIZE"]), interpolation=cv2.INTER_LINEAR)
    return resized[c["Y"]:c["Y"] + c["HEIGHT"], c["X"]:c["X"] + c["WIDTH"]]


def check_pipeline():
    expected = cv2.imread(str(ROOT / "app/src/main/assets/Support/servant/U-Olga Marie/f_40001000.png"), cv2.IMREAD_UNCHANGED)
    actual = portrait("f_40001000.png")
    same_size = expected is not None and actual.shape == expected.shape
    difference = float(np.abs(actual.astype(float) - expected.astype(float)).mean()) if same_size else float("inf")
    # Report RGB separately: opaque alpha must not dilute the colour comparison.
    rgb_difference = float(np.abs(actual[:, :, :3].astype(float) - expected[:, :, :3].astype(float)).mean()) if same_size else float("inf")
    print(f"Pipeline check: output={actual.shape[1]}x{actual.shape[0]}, same_size={same_size}, "
          f"mean_absolute_difference_RGB={rgb_difference:.6f}, all_channels={difference:.6f}")
    if not same_size or rgb_difference > 3.0:
        raise SystemExit("Pipeline differs from the shipped portrait; no costume asset was written.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("face", nargs="?")
    parser.add_argument("output", type=Path, nargs="?")
    args = parser.parse_args()
    check_pipeline()
    if not args.check:
        if not args.face or not args.output:
            parser.error("face and output are required unless --check is used")
        if not cv2.imwrite(str(args.output), portrait(args.face)):
            raise SystemExit("Could not write portrait")


if __name__ == "__main__":
    main()
