"""Verify +62 vs non-62 on the five user-provided KR/JP support screens.

Uses the production Kotlin score rectangle and threshold, plus shipped templates.
The full screenshots remain local; only anonymous score crops are committed.
"""
import argparse
import hashlib
import json
from pathlib import Path

import cv2

from verify_support_append_images import ROOT, SCRIPTS, constants, normalized, rounded, score


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ["kr_normal", "kr_append", "jp_normal", "jp_append", "jp_other"]:
        parser.add_argument("--" + key.replace("_", "-"), required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args()
    layout = constants(SCRIPTS / "locations/SupportScoreLayout.kt")
    threshold = constants(SCRIPTS / "supportSelection/SupportScoreSelection.kt")["SCORE_SIMILARITY"]
    rows = {
        "kr_normal": [(204, 0), (404, 62)],
        "kr_append": [(204, 0), (404, 62)],
        "jp_normal": [(58, 62), (258, 62), (458, 62)],
        "jp_append": [(58, 62), (258, 62), (458, 62)],
        "jp_other": [(111, 23), (311, 0), (512, 43)],
    }
    report = {"passed": True, "opencv": cv2.__version__, "threshold": threshold,
              "score_checks": 0, "rows": [], "source_inputs": {}, "templates": {}}
    for key, cases in rows.items():
        source = getattr(args, key)
        template_path = ROOT / "app/src/main/assets" / ("Kr" if key.startswith("kr") else "En") / "support_score_62.png"
        template = cv2.imread(str(template_path), 0)
        raw = cv2.imread(str(source))
        assert raw is not None and template is not None
        report["source_inputs"][key] = {"file": source.name, "sha256": hashlib.sha256(source.read_bytes()).hexdigest()}
        report["templates"][str(template_path.relative_to(ROOT))] = hashlib.sha256(template_path.read_bytes()).hexdigest()
        for method in ["crop_then_resize", "resize_then_crop"]:
            gray = cv2.cvtColor(normalized(raw, method), cv2.COLOR_BGR2GRAY)
            for row_y, expected_score in cases:
                x = rounded((76 + layout["SCORE_X"]) / 2)
                y = rounded(row_y + layout["SCORE_Y"] / 2)
                w, h = rounded(layout["SCORE_WIDTH"] / 2), rounded(layout["SCORE_HEIGHT"] / 2)
                value = score(gray[y:y+h, x:x+w], template)
                detected = value >= threshold
                assert detected == (expected_score == 62), (key, method, row_y, expected_score, value)
                report["score_checks"] += 1
                report["rows"].append({"image": key, "normalization": method, "row_y": row_y,
                                       "expected_score": expected_score, "matched_62": detected, "similarity": value})
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(f"PASS: {report['score_checks']} real image score checks (+62, +0, +23, +43)")


if __name__ == "__main__":
    main()
