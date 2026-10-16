"""Pixel regression for anonymous crops from the JP support screen.

Run with tools/np-level-vision-requirements.txt (OpenCV 4.11.0, Tesseract 5.5.1).
No emulator connection, image downloads or application changes are performed.
"""
import json
import re
from pathlib import Path

import cv2

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets/En"
FIXTURES = ROOT / "docs/reference-images/Jp/support-scores"
READER = ROOT / "scripts/src/main/java/io/github/fate_grand_automata/scripts/supportSelection/SupportScoreReader.kt"
SIMILARITY = float(re.search(r"COMPACT_FIELD_SIMILARITY = ([\d.]+)", READER.read_text())[1])
GRAND = [f"support_grand_16_{size}{phase}.png" for size in ("", "large_") for phase in "abc"]
CLASS = [f"support_compact_class_62_{phase}.png" for phase in "abc"]


def detects(image, filenames, verify_digit=False, similarity=SIMILARITY):
    for filename in filenames:
        template = cv2.imread(str(ASSETS / filename), cv2.IMREAD_GRAYSCALE)
        assert template is not None, filename
        scores = cv2.matchTemplate(image, template, cv2.TM_CCOEFF_NORMED)
        height, width = template.shape
        while True:
            _, score, _, (x, y) = cv2.minMaxLoc(scores)
            if score < similarity:
                break
            if not verify_digit:
                return True
            tail = 12 if width >= 34 else 10
            expected = template[:, width - tail:]
            observed = image[y:y + height, x + width - tail:x + width]
            core = float(cv2.matchTemplate(observed, expected, cv2.TM_CCOEFF_NORMED)[0, 0])
            if core >= similarity:
                return True
            scores[y, x] = -1
    return False


def main():
    import tesserocr
    from verify_support_grand_score_images import Reader, policy
    api = tesserocr.PyTessBaseAPI(path=str(ROOT / "app/src/main/assets/tessdata"), lang="eng", psm=7)
    reader = Reader(api)
    samples = json.loads((FIXTURES / "manifest.json").read_text(encoding="utf-8"))
    failures = []
    for sample in samples:
        image = cv2.imread(str(FIXTURES / sample["file"]), cv2.IMREAD_GRAYSCALE)
        assert image is not None and image.shape == (30, 55), sample["file"]
        grand16 = detects(image, GRAND, verify_digit=True)
        class62 = detects(image, CLASS)
        for field, detected in (("grand16", grand16), ("compactClass62", class62)):
            if detected != sample[field]:
                failures.append({"file": sample["file"], "field": field,
                                 "expected": sample[field], "detected": detected})
        for class_on, grand_on in ((False, False), (True, False), (False, True), (True, True)):
            actual = policy(reader.read(image, 'Jp', class_on, grand_on), class_on, grand_on)
            expected = (not class_on or sample["compactClass62"] or sample["ordinaryClass62"]) and (
                not grand_on or sample["grand16"])
            if actual != expected:
                failures.append({"file": sample["file"], "settings": [class_on, grand_on],
                                 "expected": expected, "detected": actual})
    print(json.dumps({"opencv": cv2.__version__, "samples": len(samples),
                      "field_checks": len(samples) * 2, "policy_checks": len(samples) * 4,
                      "failures": failures}, indent=2))
    api.End()
    assert not failures, "Support score screenshot regression failed"


if __name__ == "__main__":
    main()
