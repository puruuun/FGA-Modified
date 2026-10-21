"""Reproduce the beta.3 KR workshop fixtures and glyph-only title template (Pillow, OpenCV).

Usage: python tools/device-qa/prepare_kr_workshop.py RAW_FRAME_36
The raw 2560x1080 capture has no window chrome; crop the 20px side borders first.
"""
import sys
from pathlib import Path
from PIL import Image
import cv2

ROOT = Path(__file__).resolve().parents[2]


def main():
    raw = Image.open(sys.argv[1]).convert("RGB")
    assert raw.size == (2560, 1080)
    frame = raw.crop((20, 0, 2540, 1080)).resize((1680, 720), Image.Resampling.LANCZOS)
    frame.save(ROOT / "docs/reference-images/Kr/wide-2560/36.webp", lossless=True)
    # Also exercise the production full-frame INTER_AREA resize, followed by the
    # rounded game-area crop (20 * 2/3 -> 13, 2520 * 2/3 -> 1680).
    gray = cv2.imread(sys.argv[1], cv2.IMREAD_GRAYSCALE)
    runtime = cv2.resize(gray, (1707, 720), interpolation=cv2.INTER_AREA)[:, 13:1693]
    Image.fromarray(runtime).save(ROOT / "docs/reference-images/Kr/wide-2560/36-runtime.webp", lossless=True)
    # Existing 16:9 source, inside the dense strokes of 빈, rather than the
    # old 243x45 crop containing the workshop's moving background and shadow.
    source = Image.open(ROOT / "docs/reference-images/Kr/fp-sell/23.png")
    source.crop((1010, 14, 1053, 54)).save(ROOT / "app/src/main/assets/Kr/fp_sell_shop.png")


if __name__ == "__main__":
    main()
