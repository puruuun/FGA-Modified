"""Normalize owner KR FP return captures 37-40 (Pillow; no device access).

Usage: python tools/device-qa/prepare_kr_fp_return.py INPUT_DIRECTORY
Window chrome and blue side borders are excluded exactly as in wide captures 15-35.
"""
from pathlib import Path
import sys
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]


def main():
    source = Path(sys.argv[1])
    target = ROOT / "docs/reference-images/Kr/wide-2560"
    for number in range(37, 41):
        raw = Image.open(next(source.glob(f"{number}-*"))).convert("RGB")
        assert raw.size == (2000, 866)
        frame = raw.crop((15, 37, 1947, 865)).resize((1680, 720), Image.Resampling.LANCZOS)
        frame.save(target / f"{number}.webp", lossless=True)
        if number == 38:
            # FpSellLocations.bannerTitle: Region(-650,600,1300,260).xFromCenter().
            frame.crop((515, 300, 1165, 430)).save(target / "38-banner-title.png")


if __name__ == "__main__":
    main()
