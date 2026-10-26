"""Compare device product banks and Kotlin leave-one-frame-out measurements; fail on any wrong read."""
import argparse
from collections import Counter
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def load(path):
    return json.loads(path.read_text(encoding="utf-8"))


def table(headers, rows):
    return "\n".join(["| " + " | ".join(headers) + " |", "|" + "---|" * len(headers)] +
                     ["| " + " | ".join(map(str, row)) + " |" for row in rows])


def counts(rows):
    c = Counter(r["status"] for r in rows)
    return "/".join(str(c[k]) for k in ("correct", "abstained", "wrong"))


def report(before, after, host):
    cases = load(ROOT / "docs/reference-images/cases/skillLevelPanel.json")["cases"]
    opened = {c["name"]: c for c in cases if c["mode"] == "Append" and c["expected"]["level"] > 0}
    measurements = {}
    for version, path in (("beta.3", before), ("beta.4", after)):
        device = load(path)
        assert device["passed"] and len(device["results"]) == len(cases)
        assert all(r["passed"] for r in device["results"])
        assert {r["name"] for r in device["results"]} == {c["name"] for c in cases}
        measurements[("device-product-bank", version)] = [dict(name=r["name"], **r["actual"]) for r in device["results"] if r["name"] in opened]
    host_rows = load(host)
    for version in ("beta.3", "beta.4"):
        full = {r["name"]: r for r in host_rows if r["version"] == version and r["measurement"] == "product-bank"}
        # Same pixels + matcher must reproduce the real device path before trusting host LOFO.
        for r in measurements[("device-product-bank", version)]:
            h = full[r["name"]]
            assert (r["level"], r["candidate"], r["status"]) == (h["level"], h["candidate"], h["status"]), r["name"]
            assert abs(r["score"] - h["score"]) < 1e-12 and abs(r["margin"] - h["margin"]) < 1e-12, r["name"]
        measurements[("host-leave-one-frame-out", version)] = [r for r in host_rows if r["version"] == version and r["measurement"] == "leave-one-frame-out"]
    for rows in measurements.values():
        assert len(rows) == len(opened) and not any(r["status"] == "wrong" for r in rows)
    groups = [(layout, server, slot) for layout in ("native-wide", "native-16:9") for server in ("Kr", "Jp") for slot in range(5)]
    lines = []
    for measurement in ("device-product-bank", "host-leave-one-frame-out"):
        lines += [f"### {measurement}", "", "정답/기권/오독. 열린 슬롯이 없는 셀은 0/0/0입니다.", ""]
        rows = []
        for layout, server, slot in groups:
            phases = []
            for version in ("beta.3", "beta.4"):
                selected = [r for r in measurements[(measurement, version)] if
                    (opened[r["name"]]["layout"], opened[r["name"]]["server"], opened[r["name"]]["slot"]) == (layout, server, slot)]
                phases.append(counts(selected))
            rows.append((layout, server, slot+1, *phases))
        lines += [table(["레이아웃", "서버", "슬롯", "beta.3", "beta.4"], rows), ""]
        for version in ("beta.3", "beta.4"):
            data = measurements[(measurement, version)]
            n = sum(r["status"] == "correct" for r in data)
            lines += [f"- {version}: **{counts(data)}**, 적중률 **{n}/{len(data)} = {n/len(data):.2%}**."]
        lines += [""]
    own = load(ROOT / "docs/reference-images/panel-0021-own-template-sources.json")
    lines += ["### 샘플 수 (레벨 1→10 순)", ""]
    lines += [table(["서버", "슬롯", "레벨별 원본 샘플 수"], [(server, slot,
        "/".join(str(sum(s["server"] == server and s["sourceSlot"] == slot and s["level"] == level for s in own["samples"])) for level in range(1, 11)))
        for server in ("Kr", "Jp") for slot in range(1, 6)]), ""]
    assert own["missing"] == [], own["missing"]
    lofo_abstains = [r for r in measurements[("host-leave-one-frame-out", "beta.4")] if r["status"] == "abstained"]
    missing_after_holdout = [r for r in lofo_abstains if not any(s["server"] == r["server"] and s["sourceSlot"] == r["slot"] and
        s["level"] == r["expected"] and s["reference"] not in r["heldOutReferences"] for s in own["samples"])]
    lines += [f"호스트 잔여 기권 {len(lofo_abstains)}건 중 {len(missing_after_holdout)}건은 프레임 제외 후 해당 슬롯·레벨의 샘플이 없습니다.", ""]
    lines += [table(["케이스", "기대", "이유", "score", "margin"], [(r["name"], r["expected"], r["reason"], f'{r["score"]:.4f}', f'{r["margin"]:.4f}') for r in lofo_abstains]), ""]
    artifact = dict(beforeReport=str(before.relative_to(ROOT)), afterReport=str(after.relative_to(ROOT)),
                    device={version: measurements[("device-product-bank", version)] for version in ("beta.3", "beta.4")},
                    host=host_rows, missingLevelAfterHoldout=[r["name"] for r in missing_after_holdout])
    (ROOT / "docs/qa/append-panel-own-slot-cases.json").write_text(json.dumps(artifact, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    return "\n".join(lines)


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--before", type=Path, required=True)
    p.add_argument("--after", type=Path, required=True)
    p.add_argument("--host", type=Path, default=ROOT / "build/panel-append-evaluation.json")
    p.add_argument("--output", type=Path, required=True)
    args = p.parse_args()
    args.output.write_text(report(args.before.resolve(), args.after.resolve(), args.host.resolve()), encoding="utf-8")
