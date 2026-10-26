"""Compare two complete, native skillLevelPanel device reports (no host classifier)."""
import argparse
import collections
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def table(headers, rows):
    return "\n".join(["| " + " | ".join(headers) + " |", "|" + "---|" * len(headers)] +
                     ["| " + " | ".join(map(str, row)) + " |" for row in rows])


def counts(rows, phase):
    statuses = collections.Counter(row[phase]["status"] for row in rows)
    return "/".join(str(statuses[s]) for s in ("correct", "abstained", "wrong"))


def compare(before, after):
    cases = json.loads((ROOT / "docs/reference-images/cases/skillLevelPanel.json").read_text(encoding="utf-8"))["cases"]
    phases = {}
    for phase, path in (("before", before), ("after", after)):
        report = json.loads(path.read_text(encoding="utf-8"))
        assert report["passed"], path
        rows = report["results"]
        assert len(rows) == len(cases) and all(r["passed"] for r in rows), path
        phases[phase] = {r["name"]: r["actual"] for r in rows}
        assert set(phases[phase]) == {c["name"] for c in cases}, path
    rows = [dict(**case, before=phases["before"][case["name"]], after=phases["after"][case["name"]]) for case in cases]
    for row in rows:
        for phase in phases:
            actual = row[phase]
            expected = row["expected"]["level"]
            assert actual["status"] != "wrong"
            if expected == 0:
                assert actual["locked"] and actual["level"] is None
            elif row["mode"] == "Skill":
                assert actual["level"] == expected
    artifact = dict(beforeReport=before.relative_to(ROOT).as_posix(), afterReport=after.relative_to(ROOT).as_posix(), cases=rows)
    (ROOT / "docs/qa/append-panel-whiteness-cases.json").write_text(json.dumps(artifact, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    opened = [r for r in rows if r["mode"] == "Append" and r["expected"]["level"] > 0]
    print(table(["슬롯", "이전 정답/기권/오독", "이후 정답/기권/오독"],
                [(slot, counts([r for r in opened if r["slot"] == slot-1], "before"),
                  counts([r for r in opened if r["slot"] == slot-1], "after")) for slot in range(1, 6)]))
    print()
    groups = sorted({(r["layout"], r["server"], r["slot"]) for r in opened})
    print(table(["레이아웃", "서버", "슬롯", "이전 정답/기권/오독", "이후 정답/기권/오독"],
                [(layout, server, slot+1, counts([r for r in opened if (r["layout"], r["server"], r["slot"]) == (layout, server, slot)], "before"),
                  counts([r for r in opened if (r["layout"], r["server"], r["slot"]) == (layout, server, slot)], "after"))
                 for layout, server, slot in groups]))
    print()
    print(table(["선택된 어펜드", "이전 정답/기권/오독", "이후 정답/기권/오독"],
                [(layout + " " + server, counts([r for r in opened if r["selected"] and r["layout"] == layout and r["server"] == server], "before"),
                  counts([r for r in opened if r["selected"] and r["layout"] == layout and r["server"] == server], "after"))
                 for layout, server in sorted({(r["layout"], r["server"]) for r in opened if r["selected"]})]))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before", type=Path, required=True)
    parser.add_argument("--after", type=Path, required=True)
    args = parser.parse_args()
    compare(args.before.resolve(), args.after.resolve())
