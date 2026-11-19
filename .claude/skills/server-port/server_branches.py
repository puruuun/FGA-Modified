#!/usr/bin/env python3
"""List every place the Kotlin code branches on the game server, for a port to decide per branch.

Pure Python, no dependencies. A branch is a line under scripts/src/main (and app/src/main) that names
GameServer.Kr, GameServer.Jp, GameServer.En, GameServer.Cn, GameServer.Tw or `is GameServer`. Per line:
file, line, enclosing function, servers named, the source line as the summary of what changes, and an
empty `decision` column where the port records whether the new server takes the KR, the JP or the
default behaviour (OCR settings included).

Lines that only declare or label servers are listed apart (--skip-declarations hides them): the
server enum and its package names, and the display-name mappings. They are not behaviour.

    server_branches.py --out-csv build/branches.csv --out-md build/branches.md
"""

import argparse
import csv
import os
import re
import sys

sys.dont_write_bytecode = True
REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".."))

ROOTS = [os.path.join("scripts", "src", "main"), os.path.join("app", "src", "main")]
MENTION = re.compile(r"GameServer\.(Kr|Jp|En|Cn|Tw)\b|\bis GameServer\b|!is GameServer\b")
SERVER_NAME = re.compile(r"GameServer\.(Kr|Jp|En|Cn|Tw)\b")
# Files that only name servers to define or label them
DECLARATIONS = ("GameServerEnum.kt", "LocalizationExtensions.kt")
DECLARATION_LINES = re.compile(r"R\.string\.game_server|GameServer\.\w+(\.\w+)? to listOf|^\s*GameServer\.\w+(\.\w+)?,?\s*$")
SCOPE = re.compile(r"^\s*(?:@\w+\s+)*(?:(?:private|internal|public|protected|override|inline|suspend|operator|"
                   r"abstract|open|data|sealed|companion|const|lateinit)\s+)*(fun|val|var|class|object|init)\b\s*"
                   r"(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?(\w+)?")


def indent(line: str) -> int:
    return len(line) - len(line.lstrip())


def enclosing(lines: list[str], index: int) -> str:
    """The innermost function around the line (or, with none, the innermost declaration).

    Walks up through ever less indented declarations; the line's own declaration counts, so
    `private fun f() = if (server is GameServer.Kr)` names f.
    """
    here = indent(lines[index]) + 1
    found = []
    for i in range(index, -1, -1):
        text = lines[i]
        if not text.strip() or indent(text) >= here:
            continue
        match = SCOPE.match(text)
        if match:
            kind, name = match.group(1), match.group(2) or ""
            found.append("init" if kind == "init" else f"{kind} {name}".strip())
            here = indent(text)
    functions = [f for f in found if f.startswith("fun ")]
    return (functions or found or ["(file level)"])[0]


def scan(repo: str) -> list[dict]:
    rows = []
    for root in ROOTS:
        for folder, _, files in os.walk(os.path.join(repo, root)):
            for file in sorted(files):
                if not file.endswith(".kt"):
                    continue
                path = os.path.join(folder, file)
                with open(path, encoding="utf-8") as f:
                    lines = f.read().splitlines()
                for i, line in enumerate(lines):
                    if line.lstrip().startswith(("//", "*", "/*")) or not MENTION.search(line):
                        continue
                    servers = sorted(set(SERVER_NAME.findall(line)), key=["En", "Jp", "Kr", "Cn", "Tw"].index)
                    declaration = file in DECLARATIONS or bool(DECLARATION_LINES.search(line))
                    rows.append({
                        "file": os.path.relpath(path, repo).replace(os.sep, "/"),
                        "line": i + 1,
                        "function": enclosing(lines, i),
                        "servers": "+".join(servers) or "any",
                        "summary": re.sub(r"\s+", " ", line.strip())[:160],
                        "declaration": "yes" if declaration else "",
                        "decision": "",
                    })
    rows.sort(key=lambda r: (r["file"], r["line"]))
    return rows


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--repo", default=REPO)
    parser.add_argument("--skip-declarations", action="store_true", help="leave out lines that only declare or label servers")
    parser.add_argument("--out-csv")
    parser.add_argument("--out-md")
    args = parser.parse_args()

    rows = scan(args.repo)
    if args.skip_declarations:
        rows = [r for r in rows if not r["declaration"]]
    behaviour = [r for r in rows if not r["declaration"]]
    files = {r["file"] for r in behaviour}
    print(f"{len(rows)} lines, of which {len(behaviour)} in {len(files)} files are behaviour "
          f"({len(rows) - len(behaviour)} only declare or label servers)")

    columns = ["file", "line", "function", "servers", "summary", "declaration", "decision"]
    if args.out_csv:
        os.makedirs(os.path.dirname(os.path.abspath(args.out_csv)), exist_ok=True)
        with open(args.out_csv, "w", encoding="utf-8", newline="") as f:
            writer = csv.DictWriter(f, columns, lineterminator="\n")
            writer.writeheader()
            writer.writerows(rows)
    if args.out_md:
        os.makedirs(os.path.dirname(os.path.abspath(args.out_md)), exist_ok=True)
        with open(args.out_md, "w", encoding="utf-8") as f:
            f.write(f"# Server branches\n\n{len(behaviour)} behaviour lines in {len(files)} files; "
                    f"{len(rows) - len(behaviour)} lines only declare or label servers.\n\n")
            f.write("| file:line | function | servers | what changes | decision |\n|---|---|---|---|---|\n")
            for r in rows:
                summary = r["summary"].replace("|", "\\|")
                tag = " (declaration)" if r["declaration"] else ""
                f.write(f"| {r['file']}:{r['line']} | {r['function']} | {r['servers']} | `{summary}`{tag} | |\n")


if __name__ == "__main__":
    main()
