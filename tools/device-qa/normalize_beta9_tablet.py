"""Normalize beta.9 owner tablet captures using the product 16:9 game area.

3040x1904 raw screen: FgoGameAreaManager uses (0,97)-(3040,1807).
OpenCV INTER_AREA produces the 1280x720 reference; keep the raw inputs unchanged.
"""
from pathlib import Path
import cv2

ROOT = Path(__file__).resolve().parents[2]

def normalize(source, target):
    image = cv2.imread(str(source))
    if image is None or image.shape[:2] != (1904, 3040):
        raise ValueError(f"unexpected tablet frame: {source}")
    target.parent.mkdir(parents=True, exist_ok=True)
    frame = cv2.resize(image[97:1807, :3040], (1280, 720), interpolation=cv2.INTER_AREA)
    if not cv2.imwrite(str(target), frame):
        raise OSError(target)

if __name__ == "__main__":
    normalize(ROOT / "build/orchestration/inputs/0015-bugs/03-append-2to3-kr-tablet.png",
              ROOT / "docs/reference-images/Kr/skill-level/03-append-2to3-tablet.png")
    for source in sorted((ROOT / "build/orchestration/inputs/0017-scrollbar").glob("0[1-6]-*.png")):
        normalize(source, ROOT / "docs/reference-images/Kr/weekly-route/tablet-scrollbar" / source.name)
