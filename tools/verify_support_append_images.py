"""Development aid. The authoritative check is `tools/device-qa/run.py`.

Real OpenCV checks against KR/JP normal and append support screenshots.

Uses production Kotlin layout/threshold constants and the bundled templates.
Full player screenshots stay local; anonymous cropped UI references live in docs/.
"""
import argparse
import hashlib
import json
import math
import re
from pathlib import Path

import cv2

ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / "scripts/src/main/java/io/github/fate_grand_automata/scripts"


def constants(path):
    return {name: float(value) for name, value in re.findall(r"const val (\w+) = ([\d.]+)", path.read_text())}


def rounded(value):
    # Kotlin roundToInt rounds positive .5 upward.
    return math.floor(value + 0.5)


def normalized(raw, method):
    h, w = raw.shape[:2]
    height = rounded(w * 9 / 16)
    top = rounded((h - height) / 2)
    if method == "crop_then_resize":
        return cv2.resize(raw[top:top + height], (1280, 720), interpolation=cv2.INTER_AREA)
    # MediaProjection/Root paths may scale the full screenshot before the game-area crop.
    rate = 1280 / w
    scaled = cv2.resize(raw, (1280, rounded(h * rate)), interpolation=cv2.INTER_AREA)
    offset = rounded(top * rate)
    return scaled[offset:offset + 720]


def score(region, template):
    return float(cv2.matchTemplate(region, template, cv2.TM_CCOEFF_NORMED).max())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ["kr_normal", "kr_append", "jp_normal", "jp_append"]:
        parser.add_argument("--" + name.replace("_", "-"), type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    layout = constants(SCRIPTS / "locations/SupportSkillLayout.kt")
    threshold = constants(SCRIPTS / "supportSelection/SupportSkillLevelReader.kt")["MAX_LEVEL_SIMILARITY"]
    label_threshold = constants(SCRIPTS / "supportSelection/SupportSkillDisplay.kt")["LABEL_SIMILARITY"]
    rows = {"kr_normal": [(204, [True] * 3), (404, [True] * 3)],
            "kr_append": [(204, [True] * 5), (404, [True] * 5)],
            "jp_normal": [(58, [True] * 3), (258, [True] * 3), (458, [True] * 3)],
            "jp_append": [(58, [False] * 5), (258, [True] * 5), (458, [False] + [True] * 4)]}
    report = {"opencv": cv2.__version__, "digit_threshold": threshold, "label_threshold": label_threshold,
              "source_inputs": {}, "rows": [], "views": [], "slot_checks": 0, "passed": True}
    normal_label = cv2.imread(str(ROOT / "app/src/main/assets/En/support_normal_skills.png"))
    append_label = cv2.imread(str(ROOT / "app/src/main/assets/En/support_append_skills.png"))
    for key, cases in rows.items():
        source = getattr(args, key)
        report["source_inputs"][key] = {"file": source.name, "sha256": hashlib.sha256(source.read_bytes()).hexdigest()}
        raw = cv2.imread(str(source))
        assert raw is not None, source
        template = cv2.imread(str(ROOT / "app/src/main/assets" / ("Jp" if key.startswith("jp") else "En") / "skill_ten.png"), 0)
        is_append = key.endswith("append")
        for method in ["crop_then_resize", "resize_then_crop"]:
            image = normalized(raw, method)
            gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
            # Actual SupportScreenLocations label strip at 16:9, including its +94 support offset.
            labels = image[166:712, 822:1072]
            normal_score, append_score = score(labels, normal_label), score(labels, append_label)
            view = "append" if append_score >= label_threshold and normal_score < label_threshold else "normal" if normal_score >= label_threshold and append_score < label_threshold else "ambiguous"
            assert view == ("append" if is_append else "normal"), (key, method, normal_score, append_score)
            report["views"].append({"image": key, "normalization": method, "view": view,
                                    "normal_score": normal_score, "append_score": append_score})
            for y, expected in cases:
                scores = []
                spacing = layout["APPEND_SPACING" if is_append else "NORMAL_SPACING"]
                for index in range(len(expected)):
                    x = rounded((76 + layout["LEVEL_X"] + index * spacing) / 2)
                    sy = rounded(y + layout["LEVEL_Y"] / 2)
                    w, h = rounded(layout["LEVEL_WIDTH"] / 2), rounded(layout["LEVEL_HEIGHT"] / 2)
                    scores.append(score(gray[sy:sy+h, x:x+w], template))
                detected = [value >= threshold for value in scores]
                assert detected == expected, (key, method, y, expected, detected, scores)
                report["slot_checks"] += len(expected)
                # Same AND/wildcard decision as SupportSkillLevelReader.matches.
                masks = [[True] * len(expected), [False] * len(expected)]
                if is_append:
                    masks += [[False, True, True, True, True], [False, True, False, False, True]]
                mask_results = [{"required": mask, "pass": all(not need or actual for need, actual in zip(mask, detected))} for mask in masks]
                assert mask_results[0]["pass"] == all(expected)
                assert mask_results[1]["pass"] is True
                report["rows"].append({"image": key, "normalization": method, "row_y": y,
                                       "detected_max": detected, "scores": scores, "mask_results": mask_results})
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(f"PASS: {report['slot_checks']} real image level checks, {len(report['views'])} display-state checks")


if __name__ == "__main__":
    main()
