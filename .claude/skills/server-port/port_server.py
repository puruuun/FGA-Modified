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
scale and the insets may not carry over (apk-template-asset, "Scale"). Further flags: the atlas may be
replaced by a download on the target (--asset-storage), a font key whose source text maps to several target
texts or that KR and JP chose differently (--source-extracted), an atlas banner id not verified on the target.

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


def recipe_row(recipes, source, file):
    return dict(recipes)[source][file]


def download_flags(row: dict, asset_paths) -> str:
    """Atlases of a sprite recipe that the target's download list may replace."""
    if row["kind"] not in ("sprite", "layers"):
        return ""
    found = []
    for sprite in row["sprites"].split(";"):
        atlas = sprite.partition("/")[0]
        status = rl.download_status(atlas, asset_paths)
        if status == "replaceable":
            found.append(f"{atlas} may be replaced by a download on the target")
        elif status == "unknown":
            found.append(f"{atlas}: download replacement unknown")
    return "; ".join(found)


def key_candidates(row: dict, source: str, source_text: dict, target_text: dict) -> list[tuple[str, str]]:
    """(key, target text) of every key that holds the recipe key's source text and exists on the target."""
    if source not in source_text:
        return []
    table, by_text = source_text[source]
    text = rl.strip_markup(table.get(row["localization_key"], ""))[0].strip()
    result = []
    for key in sorted(by_text.get(text, [])):
        target = rl.strip_markup(target_text.get(key, ""))[0].strip()
        if target:
            result.append((key, target))
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--target", required=True, help="asset folder of the target server, e.g. En")
    parser.add_argument("--extracted", required=True, help="extract_apk_ui.py folder of the target server's APK")
    parser.add_argument("--recipes", nargs="+", required=True, help="recipes/Kr.csv recipes/Jp.csv (file stem = source)")
    parser.add_argument("--screens", nargs="*", default=[], help="folders of target-server screenshots")
    parser.add_argument("--atlas-dir", help="Atlas Academy banners of the target server, for atlas recipes")
    parser.add_argument("--asset-storage", help="the target server's asset_storage.json (its download list): says which "
                        "atlases the game may replace by a downloaded localized one")
    parser.add_argument("--source-extracted", action="append", default=[], metavar="LABEL=DIR",
                        help="extractions of the recipe sources (Kr=..., Jp=...): lets a font recipe's key be checked for "
                             "other keys holding the same source text")
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
    asset_paths = rl.load_asset_paths(args.asset_storage) if args.asset_storage else None
    target_text = rl.load_localization(args.extracted)
    source_text = {}
    for value in args.source_extracted:
        label, _, folder = value.partition("=")
        table = rl.load_localization(folder)
        by_text: dict[str, list[str]] = {}
        for key, text in table.items():
            clean = rl.strip_markup(text)[0].strip()
            if clean:
                by_text.setdefault(clean, []).append(key)
        source_text[label] = (table, by_text)

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
                    "draft": "", "flag": "", "class": "", "shape": None, "download": "", "key": "",
                    "candidates": [], "atlas_flag": ""}
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
                    item["flag"] = size_flags(row, target_index)
                    item["download"] = download_flags(row, asset_paths)
                    item["candidates"] = key_candidates(row, source, source_text, target_text) if row["kind"] == "font" else []
                    targets = {t for _, t in item["candidates"]}
                    item["key"] = ("ambiguous key: " + str(len(targets)) + " distinct target texts among "
                                   + str(len(item["candidates"])) + " keys") if len(targets) > 1 else ""
                    if row["kind"] == "font" and source not in source_text:
                        item["key"] = "key ambiguity unknown (no --source-extracted for " + source + ")"
                    item["atlas_flag"] = ("atlas id not verified on the target" if args.target in
                                          row.get("atlas_unverified", "").replace(";", ",").split(",") else "")
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

    # Two sources that chose different keys for the same label: at least one is wrong
    for file, items in results.items():
        fonts = [(i, recipe_row(recipes, i["source"], file)) for i in items if i["kind"] == "font" and i["draft"]]
        if len({row["localization_key"] for _, row in fonts}) > 1:
            for item, row in fonts:
                others = [f"{o['source']} {orow['localization_key']} -> '{target_text.get(orow['localization_key'], '')}'"
                          for o, orow in fonts if o is not item]
                item["key"] = (item["key"] + "; " if item["key"] else "") + "key differs from " + ", ".join(others)
        for item in items:
            if not item["draft"] or item["class"] == "language-free":
                continue
            item["class"] = ("atlas-unverified" if item.get("atlas_flag") else
                             "drafted-ambiguous-key" if item.get("key") and "unknown" not in item["key"] else
                             "drafted-download-risk" if "replaced" in item.get("download", "") else
                             "drafted-size-flag" if item["flag"] else
                             "drafted" if item["quality"] == "good" else "drafted-weak")

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
                         "key_flag": item["key"], "download_flag": item["download"], "atlas_flag": item["atlas_flag"],
                         "note": item["note"]})

    columns = ["entry", "file", "feature", "status", "class", "source", "kind", "quality", "draft",
               "shape_worst_shift", "screen_best", "screen_best_name", "screens_at_least_0.85", "size_flag", "key_flag", "download_flag", "atlas_flag", "note"]
    rl.write_csv(os.path.join(args.out, "report.csv"), rows, columns)
    write_markdown(args, rows, entries, results, recipes)


def write_markdown(args, rows, entries, results, recipes) -> None:
    status = {}
    for row in rows:
        status.setdefault(row["entry"], row["status"])
    count = lambda s: sum(1 for v in status.values() if v == s)
    missing = [r for r in rows if r["status"] == "missing"]
    # An entry with two source recipes counts once, under its best class
    order = ["language-free", "drafted", "drafted-weak", "drafted-size-flag", "drafted-download-risk",
             "drafted-ambiguous-key", "atlas-unverified", "screenshot-only", "no-source"]
    best_class = {}
    for row in missing:
        current = best_class.get(row["entry"])
        if current is None or order.index(row["class"]) < order.index(current):
            best_class[row["entry"]] = row["class"]
    lines = [f"# Port to {args.target}\n",
             f"{len(entries)} Images entries: own file {count('own')}, falls back to En {count('fallback')}, "
             f"missing {count('missing')}.\n", "## Missing entries by class (best class of the entry's sources)\n",
             "| class | entries |", "|---|---|"]
    for cls in order:
        lines.append(f"| {cls} | {sum(1 for c in best_class.values() if c == cls)} |")
    lines.append(f"\n{len({r['file'] for r in missing})} distinct files are missing.\n")
    lines.append("## By feature (script file that uses the image)\n")
    by_feature: dict[str, list[dict]] = {}
    for row in missing:
        by_feature.setdefault(row["feature"], []).append(row)
    for feature in sorted(by_feature):
        lines += [f"### {feature}\n", "| entry | file | class | source | kind | shape | screens |", "|---|---|---|---|---|---|---|"]
        for r in by_feature[feature]:
            lines.append(f"| {r['entry']} | {r['file']} | {r['class']} | {r.get('source', '')} | {r.get('kind', '')}"
                         f"{' (' + r['quality'] + ')' if r.get('quality') else ''} | {r.get('shape_worst_shift', '')} | "
                         f"{r.get('screen_best', '')} |")
        lines.append("")

    def section(title, column):
        hits = [r for r in missing if r.get(column)]
        lines.append(f"## {title} ({len(hits)})\n")
        for r in hits:
            lines.append(f"- {r['file']} from {r['source']}: {r[column]}")
        lines.append("")
    section("Size mismatches", "size_flag")
    section("Atlas may be replaced by a download on the target", "download_flag")
    section("Atlas ids not verified on the target", "atlas_flag")

    lines.append("## Font keys with more than one target text, and keys the sources disagree on\n")
    for file, items in sorted(results.items()):
        for item in items:
            if item["kind"] != "font" or not item["draft"] or not item["key"]:
                continue
            row = recipe_row(recipes, item["source"], file)
            lines.append(f"- {file} from {item['source']}: key {row['localization_key']}; {item['key']}")
            for key, text in item["candidates"]:
                lines.append(f"    - {key} -> '{text}'")
    lines.append("")

    lines.append("## Contradictions in the recipes: copies disagree, sprites agree\n")
    for source, table in recipes:
        for file, row in sorted(table.items()):
            if "CONTRADICTION" in row["notes"]:
                note = row["notes"].split("CONTRADICTION: ")[1].split(";")[0]
                lines.append(f"- {source} {file}: {note}")
    with open(os.path.join(args.out, "report.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines[:18]))


if __name__ == "__main__":
    main()
