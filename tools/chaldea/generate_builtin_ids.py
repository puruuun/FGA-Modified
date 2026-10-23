"""Generate identity mappings without guessing names or renaming shipped images.

Run with Python 3; Atlas JP supplies canonical numbers, NA exact CE names.
"""
import json
import re
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/main/assets/Support"


def catalog(region, kind):
    url = f"https://api.atlasacademy.io/export/{region}/basic_{kind}.json"
    with urllib.request.urlopen(url, timeout=60) as response:
        return json.load(response)


def generate():
    servants = {s["id"]: s for s in catalog("JP", "servant")
                if s["type"] in ("normal", "heroine") and s["collectionNo"] > 0}
    equips = [s for s in catalog("NA", "equip")
              if s["type"] == "servantEquip" and s["collectionNo"] > 0]
    jp_equips = {s["id"]: s for s in catalog("JP", "equip")}
    rows = []
    for folder in sorted((ASSETS / "servant").iterdir()):
        ids = set()
        for file in folder.glob("*.png"):
            match = re.fullmatch(r"f_(\d+)\d\.png", file.name)
            if match:
                ids.add(int(match[1]))
        records = [servants[i] for i in ids if i in servants]
        # Costume face IDs need not be catalog servant IDs. Only actual catalog
        # identities count; more than one identity is deliberately ambiguous.
        record = records[0] if len(records) == 1 else None
        rows.append(row("servant", folder.name, record))
    for file in sorted((ASSETS / "ce").glob("*.png")):
        matches = [s for s in equips if s["name"] == file.stem]
        record = matches[0] if len(matches) == 1 else None
        rows.append(row("ce", file.name, record, jp_equips))
    (ASSETS / "builtin-ids.json").write_text(json.dumps(rows, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    table = ["# 내장 개념예장 연결표", "", "Atlas NA 이름 완전 일치·단일 결과만 연결합니다. 번호는 JP 우선이며 JP에 없으면 NA 번호입니다.", "",
             "| 폴더(파일) | collection No | Atlas NA name |", "|---|---|---|"]
    for item in rows:
        if item["kind"] == "ce":
            table.append(f'| {item["folder"]} | {item["number"] or "미연결"} | {item["name"] or "—"} |')
    (ROOT / "docs/design/0018-builtin-ce-table.md").write_text("\n".join(table) + "\n", encoding="utf-8")
    manual = ROOT / "wiki/scripts/Chaldea-Import-KR.md"
    text = manual.read_text(encoding="utf-8")
    start, end = "<!-- builtin-ce-table:start -->", "<!-- builtin-ce-table:end -->"
    if start in text and end in text:
        before, rest = text.split(start, 1)
        _, after = rest.split(end, 1)
        manual.write_text(before + start + "\n" + "\n".join(table[4:]) + "\n" + end + after, encoding="utf-8")
    for kind in ("servant", "ce"):
        selected = [r for r in rows if r["kind"] == kind]
        mapped = sum(r["id"] is not None for r in selected)
        print(f"{kind}: {mapped} mapped / {len(selected) - mapped} unmapped")


def row(kind, folder, record, canonical=None):
    jp = (canonical or {}).get(record["id"]) if record else None
    return dict(kind=kind, folder=folder, id=record["id"] if record else None,
                number=(jp or record)["collectionNo"] if record else None,
                name=record["name"] if record else None,
                region="NA" if record and canonical is not None and jp is None else None)


if __name__ == "__main__":
    generate()
