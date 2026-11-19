#!/usr/bin/env python3
"""Plan the templates of a new target server (NA) from the KR and JP recipes and the target's APK.

Requires Pillow, numpy and opencv-python-headless. Run on Windows with `py -3 -E -P`.
Reads every `Images` entry and the files under app/src/main/assets/<Target> and En. For each entry:
  own file     the target has its own copy: nothing to do
  fallback     the target falls back to the En copy (target other than En)
  missing      no usable file, then by recipe (make_recipes.py):
    language-free   the KR or JP copy is proposed (copied to OUT/language_free/ for review)
    sprite, layers, font, atlas
                    the recipe is replayed on the target APK into OUT/drafts/<name>.<Source>.png
    screenshot      listed: needs a screenshot of the target server
Drafts are scored on --screens (the target server's screenshots, FGA's way: best score over the game
area at 720p, grayscale) and, for shape only, against the source template. A draft whose target sprite
is more than 2 px off the source sprite is flagged: a localized label is not the same size, so the
scale and the insets may not carry over (apk-template-asset, "Scale").

    port_server.py --target En --extracted build/apk-na --recipes recipes/Kr.csv recipes/Jp.csv \\
        --screens docs/reference-images/En --out build/port-na

Writes OUT/report.csv and OUT/report.md, grouped by the script file that uses the image. Nothing is
written into app/src/main/assets/: a draft goes there only after extract-template-asset's checks.
"""

import argparse
import os
import re
import shutil
import sys

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import recipe_lib as rl  # noqa: E402

lib = rl.lib
Image = rl.Image

SIZE_SLACK = 2  # NGUI rounds an odd sprite size to even: 1-2 units
SEEN_SCORE = 0.85  # the default similarity of most calls; a draft this high on a target screenshot shows the label


def callers(repo: str) -> dict[str, list[str]]:
    """Images entry -> script files that use it (Images.Name)."""
    root = os.path.join(repo, "scripts", "src", "main")
    uses: dict[str, set[str]] = {}
    pattern = re.compile(r"\bImages\.(\w+)")
    for folder, _, files in os.walk(root):
        for file in files:
            if file.endswith(".kt") and file != "Images.kt":
                with open(os.path.join(folder, file), encoding="utf-8") as f:
                    for name in set(pattern.findall(f.read())):
                        uses.setdefault(name, set()).add(file[:-3])
    return {k: sorted(v) for k, v in uses.items()}


def size_flags(row: dict, target_index: dict) -> str:
    """Sprites whose size in the target APK differs from the source's by more than rounding."""
    if not row["sprite_size"]:
        return ""
    flags = []
    for sprite, size in zip(row["sprites"].split(";"), row["sprite_size"].split(";")):
        entry = target_index.get(sprite)
        if not size or not entry:
            continue
        sw, sh = (int(v) for v in size.split("x"))
        if abs(entry["w"] - sw) > SIZE_SLACK or abs(entry["h"] - sh) > SIZE_SLACK:
            flags.append(f"{sprite} {entry['w']}x{entry['h']} vs {sw}x{sh}")
    return "; ".join(flags)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--target", required=True, help="asset folder of the target server, e.g. En")
    parser.add_argument("--extracted", required=True, help="extract_apk_ui.py folder of the target server's APK")
    parser.add_argument("--recipes", nargs="+", required=True, help="recipes/Kr.csv recipes/Jp.csv (file stem = source)")
    parser.add_argument("--screens", nargs="*", default=[], help="folders of target-server screenshots")
    parser.add_argument("--atlas-dir", help="Atlas Academy banners of the target server, for atlas recipes")
    parser.add_argument("--repo", default=rl.REPO)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    assets = os.path.join(args.repo, "app", "src", "main", "assets")
    listing = lambda server: set(os.listdir(os.path.join(assets, server))) if os.path.isdir(os.path.join(assets, server)) else set()
    own_files, en_files = listing(args.target), listing("En")
    recipes = []
    for path in args.recipes:
        source = os.path.splitext(os.path.basename(path))[0]
        recipes.append((source, {r["template"]: r for r in rl.read_csv(path)}))
    users = callers(args.repo)
    target_index = rl.sprite_index(args.extracted)

    drafts_dir = os.path.join(args.out, "drafts")
    free_dir = os.path.join(args.out, "language_free")
    os.makedirs(drafts_dir, exist_ok=True)
    os.makedirs(free_dir, exist_ok=True)

    # One draft per (file, source); entries sharing a file share it
    results: dict[str, list[dict]] = {}
    sources_of = lambda file: [(s, r[file]) for s, r in recipes if file in r]
    drafts: dict[tuple[str, str], object] = {}
    entries = rl.parse_images(args.repo)
    for _, file in entries:
        if file in results or file in own_files or (args.target != "En" and file in en_files):
            continue
        out = []
        for source, row in sources_of(file):
            item = {"source": source, "kind": row["kind"], "quality": row["quality"], "note": row["notes"],
                    "draft": "", "flag": "", "class": "", "shape": None}
            if row["kind"] == "language-free":
                item["class"] = "language-free"
                src = os.path.join(assets, source, file)
                item["draft"] = os.path.join("language_free", f"{os.path.splitext(file)[0]}.{source}.png")
                shutil.copyfile(src, os.path.join(args.out, item["draft"]))
            elif row["kind"] in rl.REPLAYABLE:
                try:
                    draft, text = rl.replay(row, args.extracted, args.target, args.atlas_dir)
                except rl.Replay as exc:
                    item["class"] = "screenshot-only"
                    item["note"] = f"recipe {row['kind']} cannot be replayed: {exc}"
                else:
                    item["class"] = "drafted" if row["quality"] == "good" else "drafted-weak"
                    item["flag"] = size_flags(row, target_index)
                    if item["flag"]:
                        item["class"] = "drafted-flagged"
                    if text:
                        item["note"] = f"text '{text}'; {item['note']}"
                    name = f"{os.path.splitext(file)[0]}.{source}.png"
                    item["draft"] = os.path.join("drafts", name)
                    Image.fromarray(draft).save(os.path.join(args.out, item["draft"]))
                    drafts[(file, source)] = draft
                    source_png = os.path.join(assets, source, file)
                    shifts = lib.shift_scores(lib.load_rgb(source_png), draft)
                    item["shape"] = None if shifts is None else min(shifts)
            else:
                item["class"] = "screenshot-only"
            out.append(item)
        results[file] = out

    # Target screenshots: the best score of every draft over the folders
    best: dict[tuple[str, str], tuple[float, str, int]] = {}
    if args.screens and drafts:
        grays = {f"{f}|{s}": lib.to_gray(d) for (f, s), d in drafts.items()}
        merged: dict[str, list[tuple[str, float]]] = {k: [] for k in grays}
        for folder in args.screens:
            for key, found in rl.screen_scores(folder, grays).items():
                merged[key] += [(f"{os.path.basename(os.path.normpath(folder))}/{n}", s) for n, s in found]
        for key, found in merged.items():
            file, source = key.split("|")
            if found:
                top = max(found, key=lambda t: t[1])
                best[(file, source)] = (top[1], top[0], sum(1 for _, s in found if s >= SEEN_SCORE))
        print(f"scored {len(drafts)} drafts on {sum(len(v) for v in merged.values()) // max(1, len(grays))} screenshots")

    rows = []
    for name, file in entries:
        feature = "/".join(users.get(name, ["(unused in scripts)"])[:3])
        if file in own_files:
            status, cls = "own", "own"
        elif args.target != "En" and file in en_files:
            status, cls = "fallback", "fallback"
        else:
            status, cls = "missing", ""
        base = {"entry": name, "file": file, "feature": feature, "status": status}
        if status != "missing":
            rows.append({**base, "class": cls})
            continue
        found = results.get(file, [])
        if not found:
            rows.append({**base, "class": "no-source", "note": "no KR or JP template of this name"})
            continue
        for item in found:
            top = best.get((file, item["source"]))
            rows.append({**base, "class": item["class"], "source": item["source"], "kind": item["kind"],
                         "quality": item["quality"], "draft": item["draft"],
                         "shape_worst_shift": "" if item["shape"] is None else f"{item['shape']:.3f}",
                         "screen_best": f"{top[0]:.3f}" if top else "", "screen_best_name": top[1] if top else "",
                         "screens_at_least_0.85": top[2] if top else "", "size_flag": item["flag"],
                         "note": item["note"]})

    columns = ["entry", "file", "feature", "status", "class", "source", "kind", "quality", "draft",
               "shape_worst_shift", "screen_best", "screen_best_name", "screens_at_least_0.85", "size_flag", "note"]
    rl.write_csv(os.path.join(args.out, "report.csv"), rows, columns)
    write_markdown(args, rows, entries)


def write_markdown(args, rows, entries) -> None:
    status = {}
    for row in rows:
        status.setdefault(row["entry"], row["status"])
    count = lambda s: sum(1 for v in status.values() if v == s)
    missing = [r for r in rows if r["status"] == "missing"]
    # An entry with two source recipes counts once, under its best class
    order = ["language-free", "drafted", "drafted-flagged", "drafted-weak", "screenshot-only", "no-source"]
    best_class = {}
    for row in missing:
        current = best_class.get(row["entry"])
        if current is None or order.index(row["class"]) < order.index(current):
            best_class[row["entry"]] = row["class"]
    lines = [f"# Port to {args.target}\n",
             f"{len(entries)} Images entries: own file {count('own')}, falls back to En {count('fallback')}, "
             f"missing {count('missing')}.\n", "## Missing entries by class\n", "| class | entries |", "|---|---|"]
    for cls in order:
        lines.append(f"| {cls} | {sum(1 for c in best_class.values() if c == cls)} |")
    files = {r["file"] for r in missing}
    lines.append(f"\n{len(files)} distinct files are missing.\n")
    lines.append("## By feature (script file that uses the image)\n")
    by_feature: dict[str, list[dict]] = {}
    for row in missing:
        by_feature.setdefault(row["feature"], []).append(row)
    for feature in sorted(by_feature):
        lines += [f"### {feature}\n", "| entry | file | class | source | kind | shape | screens | flag |", "|---|---|---|---|---|---|---|---|"]
        for r in by_feature[feature]:
            lines.append(f"| {r['entry']} | {r['file']} | {r['class']} | {r.get('source', '')} | {r.get('kind', '')}"
                         f"{' (' + r['quality'] + ')' if r.get('quality') else ''} | {r.get('shape_worst_shift', '')} | "
                         f"{r.get('screen_best', '')} | {r.get('size_flag', '')} |")
        lines.append("")
    flagged = [r for r in missing if r.get("size_flag")]
    lines.append(f"## Size mismatches ({len(flagged)})\n")
    for r in flagged:
        lines.append(f"- {r['file']} from {r['source']}: {r['size_flag']}")
    with open(os.path.join(args.out, "report.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines[:16]))


if __name__ == "__main__":
    main()
