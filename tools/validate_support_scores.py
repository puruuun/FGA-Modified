"""Pixel regression for anonymous crops from the JP support screen.

Run with Python, numpy and opencv-python-headless==4.11.0.86.
No emulator connection, image downloads or application changes are performed.
"""
import json
from pathlib import Path

import cv2

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets/En"
FIXTURES = ROOT / "docs/reference-images/Jp/support-scores"
SIMILARITY = 0.94
GRAND = [f"support_grand_16_{size}{phase}.png" for size in ("", "large_") for phase in "abc"]
CLASS = [f"support_compact_class_62_{phase}.png" for phase in "abc"]


def detects(image, filenames, verify_digit=False):
    for filename in filenames:
        template = cv2.imread(str(ASSETS / filename), cv2.IMREAD_GRAYSCALE)
        assert template is not None, filename
        scores = cv2.matchTemplate(image, template, cv2.TM_CCOEFF_NORMED)
        height, width = template.shape
        while True:
            _, score, _, (x, y) = cv2.minMaxLoc(scores)
            if score < SIMILARITY:
                break
            if not verify_digit:
                return True
            tail = 12 if width >= 34 else 10
            expected = template[:, width - tail:]
            observed = image[y:y + height, x + width - tail:x + width]
            core = float(cv2.matchTemplate(observed, expected, cv2.TM_CCOEFF_NORMED)[0, 0])
            if core >= SIMILARITY:
                return True
            scores[y, x] = -1
    return False


def main():
    samples = json.loads((FIXTURES / "manifest.json").read_text(encoding="utf-8"))
    failures = []
    for sample in samples:
        image = cv2.imread(str(FIXTURES / sample["file"]), cv2.IMREAD_GRAYSCALE)
        assert image is not None and image.shape == (30, 55), sample["file"]
        for field, detected in (("grand16", detects(image, GRAND, verify_digit=True)),
                                ("compactClass62", detects(image, CLASS))):
            if detected != sample[field]:
                failures.append({"file": sample["file"], "field": field,
                                 "expected": sample[field], "detected": detected})
    print(json.dumps({"opencv": cv2.__version__, "samples": len(samples),
                      "field_checks": len(samples) * 2, "failures": failures}, indent=2))
    assert not failures, "Support score screenshot regression failed"


if __name__ == "__main__":
    main()
