"""Reproduce SupportPortraitTemplate's bilinear resize/crop, with a real-frame match-score gate.

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
    frame = cv2.imread(str(ROOT / "docs/reference-images/Jp/np-20261018-r3/4-grand-olga-masked720.png"), cv2.IMREAD_COLOR)
    shipped = cv2.imread(str(ROOT / "app/src/main/assets/Support/servant/U-Olga Marie/f_40001002.png"), cv2.IMREAD_COLOR)
    actual = portrait("f_40001002.png")[:, :, :3]  # BGR; opaque alpha is not a matching channel.
    if frame is None or shipped is None or shipped.shape != actual.shape:
        raise SystemExit("Missing frame or incompatible portrait dimensions; no costume asset was written.")
    importer_score = cv2.minMaxLoc(cv2.matchTemplate(frame, actual, cv2.TM_CCOEFF_NORMED))[1]
    shipped_score = cv2.minMaxLoc(cv2.matchTemplate(frame, shipped, cv2.TM_CCOEFF_NORMED))[1]
    print(f"Pipeline check (colour TM_CCOEFF_NORMED): importer={importer_score:.6f}, shipped={shipped_score:.6f}")
    if not (importer_score >= 0.9 and importer_score >= shipped_score):
        raise SystemExit("Importer score must be at least 0.9 and no lower than shipped; no costume asset was written.")


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
