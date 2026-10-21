"""Weekly route probe audit sheet (design 0009 D-30).

Turns a probe CSV and the evidence folder pulled from the device into ONE self-contained HTML page. Every row shows
the names the Atlas data expects, the evidence picture the probe saved (panel name band above the spot ribbon, as the
screen showed it) and the rule that accepted it, so a person can check every success by eye.

Usage (Python 3.9+, standard library only):

    adb pull /sdcard/Android/data/<package>/files/fga/diagnostics/weeklyRoute <folder>     # or copy the folders by hand
    py -3 tools/weekly-audit/contact_sheet.py probe-JP-20261021-150958.csv <folder> -o audit.html

`<folder>` is searched recursively for `q<quest id>.png` (the `evidence_file` column names the file; only its file name is
used, so the folder layout does not matter). Rows are sorted riskiest first: `spot-name+cells` successes, then
`quest-name`, then successes without evidence, then everything that failed. A success whose evidence picture is
missing is flagged. Nothing is uploaded or modified.
"""

import argparse
import base64
import csv
import html
import sys
from collections import Counter
from pathlib import Path

RULES = {
    "quest-name": "Quest name read from the panel matches the Atlas name (primary rule).",
    "spot-name+cells": "Quest name unreadable: matched by spot name + single Atlas free quest + Free Quest emblem + name width.",
}
ORDER = {"spot-name+cells": 0, "quest-name": 1}


def find_evidence(folder: Path) -> dict:
    return {p.name: p for p in folder.rglob("q*.png")} if folder.exists() else {}


def encode(path: Path) -> str:
    return "data:image/png;base64," + base64.b64encode(path.read_bytes()).decode("ascii")


def row_key(row: dict, files: dict):
    success = row["status"] == "success"
    has = Path(row.get("evidence_file", "")).name in files
    if success and row.get("by") in ORDER:
        return (0, ORDER[row["by"]], row["war_id"], row["quest_id"])
    if success:
        return (1, 0 if not has else 1, row["war_id"], row["quest_id"])
    return (2, 0, row["war_id"], row["quest_id"])


def render(rows: list, files: dict, title: str) -> str:
    counts = Counter(r["status"] for r in rows)
    by_counts = Counter(r.get("by") or "-" for r in rows if r["status"] == "success")
    missing = [r for r in rows if r["status"] == "success" and Path(r.get("evidence_file", "")).name not in files]
    out = [
        "<!doctype html><html lang='ko'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>",
        f"<title>{html.escape(title)}</title>",
        "<style>body{font:14px/1.45 system-ui,'Malgun Gothic',sans-serif;margin:16px;background:#fff;color:#1b1b1b}"
        "table{border-collapse:collapse;width:100%}th,td{border:1px solid #ccc;padding:6px 8px;vertical-align:top;text-align:left}"
        "th{background:#f0f0f0;position:sticky;top:0}img{display:block;image-rendering:auto;max-width:100%}"
        ".spot{background:#fff4d6}.fail{background:#fde3e3}.warn{background:#ffd0a0}.ok{background:#fff}"
        ".tag{font-size:12px;padding:1px 6px;border-radius:8px;background:#e6e6e6}.small{color:#555;font-size:12px}"
        "td.n{white-space:nowrap}@media (prefers-color-scheme:dark){body{background:#161616;color:#eee}th{background:#262626}"
        "th,td{border-color:#444}.spot{background:#4a3d12}.fail{background:#4a1c1c}.warn{background:#5a3512}.ok{background:#161616}"
        ".tag{background:#333}.small{color:#aaa}}</style></head><body>",
        f"<h1>{html.escape(title)}</h1>",
        "<p>" + " / ".join(f"{html.escape(k)}: <b>{v}</b>" for k, v in sorted(counts.items())) + "</p>",
        "<p>Success rules: " + " / ".join(f"{html.escape(k)}: <b>{v}</b>" for k, v in sorted(by_counts.items())) +
        (f" &mdash; <b>{len(missing)} success(es) without an evidence picture</b>" if missing else "") + "</p>",
        "<p class='small'>Check each row by eye: the picture's upper strip is the quest name on the panel, the lower strip is the spot name "
        "ribbon. Expected names must read the same. A <i>spot-name+cells</i> row is accepted although the quest name could not be read, "
        "so verify the spot name and that the quest name's length looks right. Any row that does not agree is a false success.</p>",
        "<table><tr><th>#</th><th>Quest</th><th>Expected</th><th>Evidence</th><th>Rule / OCR read</th></tr>",
    ]
    for row in sorted(rows, key=lambda r: row_key(r, files)):
        success = row["status"] == "success"
        file = files.get(Path(row.get("evidence_file", "")).name)
        by = row.get("by") or ""
        css = "fail" if not success else "warn" if file is None else "spot" if by == "spot-name+cells" else "ok"
        picture = f"<img src='{encode(file)}' alt='evidence {html.escape(row['quest_id'])}'>" if file else \
            ("<b>no evidence picture</b>" if success else "<span class='small'>-</span>")
        rule = (f"<span class='tag'>{html.escape(by)}</span><br>{html.escape(RULES.get(by, ''))}" if success else
                f"<span class='tag'>{html.escape(row['status'])}</span><br>{html.escape(row.get('note', ''))}")
        read = f"<div class='small'>read quest: {html.escape(row.get('read_quest') or '-')}<br>read spot: {html.escape(row.get('read_spot') or '-')}</div>" if success else ""
        out.append(
            f"<tr class='{css}'><td class='n'>{html.escape(row['quest_id'])}<br><span class='small'>war {html.escape(row['war_id'])}</span></td>"
            f"<td>{html.escape(row['chapter'])}</td>"
            f"<td><b>{html.escape(row['quest'])}</b><br>{html.escape(row['spot'])}<br><span class='small'>AP {html.escape(row['ap'])}</span></td>"
            f"<td>{picture}</td><td>{rule}{read}</td></tr>")
    out.append("</table></body></html>")
    return "\n".join(out)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("csv", type=Path, help="probe-<server>-<time>.csv")
    parser.add_argument("evidence", type=Path, help="folder holding the q<id>.png evidence pictures (searched recursively)")
    parser.add_argument("-o", "--output", type=Path, default=Path("weekly-audit.html"))
    args = parser.parse_args()
    with args.csv.open(encoding="utf-8-sig", newline="") as handle:
        rows = list(csv.DictReader(handle))
    if not rows or "status" not in rows[0]:
        sys.exit("not a probe CSV: no status column")
    files = find_evidence(args.evidence)
    args.output.write_text(render(rows, files, f"Weekly route probe audit: {args.csv.name}"), encoding="utf-8")
    successes = [r for r in rows if r["status"] == "success"]
    shown = sum(Path(r.get("evidence_file", "")).name in files for r in successes)
    print(f"{args.output}: {len(rows)} rows, {len(successes)} successes, {shown} with evidence")
    return 0


if __name__ == "__main__":
    sys.exit(main())
