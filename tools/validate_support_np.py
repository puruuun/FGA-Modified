"""Development aid. The authoritative check is `tools/device-qa/run.py`.

Pixel regression for anonymous KR NP labels and existing JP/KR controls.

Uses the pinned OpenCV/Tesseract dependencies in np-level-vision-requirements.txt.
Original player names and screenshots are not part of these fixtures.
"""
import json
from pathlib import Path

import cv2
import tesserocr
from verify_support_np_images import Reader

ROOT = Path(__file__).resolve().parents[1]


def main():
    folder = ROOT / 'docs/reference-images/Kr/support-np-20261017'
    cases = [(folder / c['file'], c['server'], c['level'])
             for c in json.loads((folder / 'manifest.json').read_text(encoding='utf-8'))]
    cases += [(ROOT / 'docs/reference-images' / server / name, server, level)
              for server, name, level in [('Jp', 'support-np-143344-311.png', 1),
                                         ('Jp', 'support-np-143344-512.png', 5),
                                         ('Kr', 'support-np-134749-204.png', 5)]]
    failures = []
    negative_checks = 0
    with tesserocr.PyTessBaseAPI(path=str(ROOT / 'app/src/main/assets/tessdata'), lang='eng', psm=7) as api:
        reader = Reader(api)
        for path, server, level in cases:
            image = cv2.imread(str(path), cv2.IMREAD_GRAYSCALE)
            assert image is not None, path
            actual = reader.read(image, server)
            if actual['level'] != level:
                failures.append({'file': path.name, 'expected': level, 'actual': actual})
            if 'candidate' in actual:
                _, x, _, width, *_ = actual['candidate']
                for end in (x - 3, x + width):
                    negative_checks += 1
                    negative = reader.read(image[:, :max(0, end)], server)
                    if negative['level'] is not None:
                        failures.append({'file': path.name, 'clipped_at': end, 'actual': negative})
    print(json.dumps({'opencv': cv2.__version__, 'labels': len(cases),
                      'minimum_level_checks': len(cases) * 5,
                      'negative_checks': negative_checks, 'failures': failures}, indent=2))
    assert not failures, 'Support NP screenshot regression failed'


if __name__ == '__main__':
    main()
