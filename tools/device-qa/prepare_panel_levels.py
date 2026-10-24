"""Prepare 0021 native panel references, 720p template cells and measurement cases.

Requires numpy/opencv-python. Labels are copied unchanged; no inferred label corrections.
Run from any directory: python tools/device-qa/prepare_panel_levels.py [--input DIR].
"""
from pathlib import Path
import argparse
import json
import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--input", type=Path, default=ROOT / "build/orchestration/inputs/0021-append-panel")


def prepare(inputs: Path) -> None:
    labels = json.loads((inputs / "labels.json").read_text(encoding="utf-8"))
    frames, cases, bank = [], [], {}
    centers = [673, 850, 1028, 1205, 1383]
    for index, frame in enumerate(labels["frames"]):
        server = frame["file"][:2].title()
        assert server in ("Kr", "Jp")
        mode = "Skill" if frame["screen"] == "skill" else "Append"
        path = inputs / frame["file"]
        encoded = np.fromfile(path, np.uint8)
        color = cv2.imdecode(encoded, cv2.IMREAD_COLOR)
        assert color.shape[:2] == (1080, 2560)
        # FgoGameAreaManager: 2560x1080 -> centered 2520x1080 -> 1680x720.
        color = cv2.resize(color[:, 20:2540], (1680, 720), interpolation=cv2.INTER_AREA)
        gray = cv2.imdecode(encoded, cv2.IMREAD_GRAYSCALE)
        gray = cv2.resize(gray[:, 20:2540], (1680, 720), interpolation=cv2.INTER_AREA)
        reference = ROOT / f"docs/reference-images/{server}/skill-level/0021/{index:02d}-{frame['screen']}.png"
        reference.parent.mkdir(parents=True, exist_ok=True)
        assert cv2.imwrite(str(reference), color)
        relative = reference.relative_to(ROOT).as_posix()
        frames.append(dict(source=frame["file"], file=relative, screen=frame["screen"], levels=frame["levels"]))
        for slot, level in enumerate(frame["levels"]):
            assert level in range(11)
            cases.append(dict(name=f"{server}-{index:02d}-{mode}-{slot+1}", suite="skillLevelPanel", server=server,
                              file=relative, mode=mode, slot=slot, screen=frame["screen"], expected=dict(level=level)))
            if mode == "Append" and level > 0 and slot != 1:
                box = [centers[slot] - 5, 273, 33, 21]
                bank.setdefault((server, level, slot), (gray[273:294, box[0]:box[0]+33], frame, relative, box))

    provenance = []
    for server in ("Kr", "Jp"):
        sheet = np.zeros((210, 132), np.uint8)
        for level in range(1, 11):
            for column, slot in enumerate((0, 2, 3, 4)):
                patch, frame, relative, box = bank[(server, level, slot)]
                sheet[(level-1)*21:level*21, column*33:(column+1)*33] = patch
                provenance.append(dict(server=server, level=level, sourceSlot=slot+1, source=frame["file"],
                                       reference=relative, region720=box, cell=[column*33, (level-1)*21, 33, 21]))
        assert cv2.imwrite(str(ROOT / f"app/src/main/assets/{server}/skill_level_panel_append_samples.png"), sheet)

    native = cases.copy()
    for case in native:
        if case["mode"] == "Append":
            cases.extend(dict(case, name=case["name"]+"-"+stress.replace(":", "x"), stress=stress)
                         for stress in ("16:9", "16:10"))
    artifacts = {
        "docs/reference-images/panel-0021-sources.json": dict(note=labels["note"], frames=frames),
        "docs/reference-images/panel-0021-template-sources.json": provenance,
        "docs/reference-images/cases/skillLevelPanel.json": dict(schema=1, suite="skillLevelPanel", cases=cases),
    }
    for relative, value in artifacts.items():
        (ROOT / relative).write_text(json.dumps(value, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    print(f"{len(frames)} frames, {len(provenance)} source cells, {len(native)} native + {len(cases)-len(native)} stress cases")


if __name__ == "__main__":
    prepare(parser.parse_args().input)
