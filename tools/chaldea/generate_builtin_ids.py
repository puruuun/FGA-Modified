"""Generate identity mappings without guessing names or renaming shipped images.

Run with Python 3; Atlas JP supplies canonical numbers, NA/KR/JP verified records supply CE matches.
"""
import json
import re
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/main/assets/Support"

NA_CE_ALIASES = {
    "Black Grail.png": ("The Black Grail", 48, 9400480),
}

KR_CE_MATCHES = {
    "Beyond the Night Without the Snow Flower.png": ("눈꽃 없는 밤의 너머로", 1410, 9405170),
    "Detective Fou.png": ("명탐정 폼즈", 1080, 9403990),
    "Marshal of the Sorcerors.png": ("마도원수", 1458, 9405320),
    "Needle of Sincerity.png": ("지성의 한 땀", 2209, 9408390),
    "Report Check.png": ("리포트 체크", 2052, 9407850),
    "Rising Against the Azure Sky.png": ("창공에 서다", 1605, 9406200),
    "Secret Mission.png": ("시크릿 미션", 2142, 9408220),
    "The Chaldean.png": ("칼데아의 남자", 1797, 9406960),
    "Wings of Manuscript.png": ("수고의 날개", 2124, 9408060),
}

JP_CE_MATCHES = {
    "Blessed Bride.png": (2233, 9408590),
    "Foreign God.png": (2437, 9408800),
    "Great Library of Memories.png": (2583, 9409210),
    "Gun Dealer.png": (2701, 9409490),
    "Sword Saints of Wandering.png": (2607, 9409320),
}


def catalog(region, kind):
    url = f"https://api.atlasacademy.io/export/{region}/basic_{kind}.json"
    with urllib.request.urlopen(url, timeout=60) as response:
        return json.load(response)


def match_ce(file, na_equips, kr_equips, jp_equips):
    matches = [s for s in na_equips if s["name"] == file.stem]
    if len(matches) == 1:
        return matches[0]
    if file.name in NA_CE_ALIASES:
        na_name, number, ce_id = NA_CE_ALIASES[file.name]
        matches = [s for s in na_equips if s["name"] == na_name and s["collectionNo"] == number and s["id"] == ce_id]
        if len(matches) == 1:
            return matches[0]
    if file.name in KR_CE_MATCHES:
        kr_name, number, ce_id = KR_CE_MATCHES[file.name]
        matches = [s for s in kr_equips if s["name"] == kr_name and s["collectionNo"] == number and s["id"] == ce_id]
        if len(matches) == 1:
            return matches[0]
    if file.name in JP_CE_MATCHES:
        number, ce_id = JP_CE_MATCHES[file.name]
        record = jp_equips.get(ce_id)
        if record and record["collectionNo"] == number:
            return record
    return None


def generate():
    servants = {s["id"]: s for s in catalog("JP", "servant")
                if s["type"] in ("normal", "heroine") and s["collectionNo"] > 0}
    na_equips = [s for s in catalog("NA", "equip")
                 if s["type"] == "servantEquip" and s["collectionNo"] > 0]
    kr_equips = [s for s in catalog("KR", "equip")
                 if s["type"] == "servantEquip" and s["collectionNo"] > 0]
    jp_equips = {s["id"]: s for s in catalog("JP", "equip")
                 if s["type"] == "servantEquip" and s["collectionNo"] > 0}
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
        record = match_ce(file, na_equips, kr_equips, jp_equips)
        rows.append(row("ce", file.name, record, jp_equips))
    (ASSETS / "builtin-ids.json").write_text(json.dumps(rows, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    table = ["# 내장 개념예장 연결표", "", "Atlas NA/KR/JP 데이터로 검증된 내장 개념예장 23종 연결표입니다. 번호는 JP 도감 번호 기준입니다.", "",
             "| 폴더(파일) | collection No | Atlas name |", "|---|---|---|"]
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
