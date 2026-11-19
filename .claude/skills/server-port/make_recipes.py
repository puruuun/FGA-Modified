#!/usr/bin/env python3
"""Derive a recipe for every template of a source server (KR or JP): how to draw it again from another APK.

Requires Pillow, numpy, opencv-python-headless and UnityPy's output (extract_apk_ui.py folders).
Run on Windows with `py -3 -E -P`. The pixel work is the apk-template-asset code; this script only
decides, per template, which of these holds and records the numbers:

  language-free  the art is the same on every server (evidence below), so the template is copied
  sprite         one atlas sprite at one scale, cropped by edge insets (find_sprite + --crop-like)
  layers         several sprites stacked at offsets (from overrides.csv)
  font           a localization string in the game font (overrides.csv; stored by key, not by text)
  atlas          an Atlas Academy banner at x1.25 (overrides.csv; the banner differs per server)
  manual         something a script cannot find, described in overrides.csv
  screenshot     none of the above: the best score found and why

Language-free evidence, any of: the KR and JP copies score 0.95 or more against each other; the
sprite and the same-named sprite of another APK are the same art (same size, correlation 0.98 or
more); the En copy scores 0.95 or more on a source screenshot.

    make_recipes.py --source Kr --extracted build/apk-kr --templates app/src/main/assets/Kr \\
        --screens docs/reference-images/Kr --other-templates Jp=app/src/main/assets/Jp \\
        --other-extracted NA=build/apk-na --other-extracted Jp=build/apk-jp \\
        --en-templates app/src/main/assets/En --out recipes/Kr.csv

For Jp, add `--extra-extracted Kr=build/apk-kr`: the KR APK holds the JP originals of atlases the JP
APK lacks (ResultAtlas); they are used only for atlases the primary APK does not have.
The recipes hold names and numbers only, no game art and no localized text.
"""

import argparse
import os
import sys

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import recipe_lib as rl  # noqa: E402

lib, fs, dt = rl.lib, rl.fs, rl.dt
np, cv2 = rl.np, rl.cv2

# A sprite below this against its template is not the template's source: flat patches and shared
# frames reach 0.8 (apk-template-asset audits), real sources reach 0.92 or more
SPRITE_MIN = 0.85
# Another scale replaces 1.25 only when it wins by this much; 1.25 is NGUI's own-size scale
CLEAR_WIN = 0.02
LANGUAGE_FREE_COPY = 0.95
LANGUAGE_FREE_SPRITE = 0.98


def labelled(values: list[str], what: str) -> dict[str, str]:
    result = {}
    for value in values:
        label, sep, path = value.partition("=")
        if not sep:
            sys.exit(f"{what} takes LABEL=DIR, got {value}")
        result[label] = path
    return result


def pick_sprite(sets, template_rgb):
    """Best (score, label, atlas, name, scale, scaled-set, index) over the primary and extra sprite sets,
    and the best score of a different sprite, to show how clear the choice is."""
    found = []
    for label, scaled in sets:
        for score, atlas, name, scale, i in fs.best_sprites(scaled, template_rgb, 3):
            found.append((score, label, atlas, name, scale, scaled, i))
    found.sort(key=lambda f: -f[0])
    return found


def choose_scale(best, template_rgb):
    score, _, _, _, scale, scaled, i = best
    gray = lib.to_gray(template_rgb)
    rgba = scaled.get(i, lib.DEFAULT_SPRITE_SCALE)
    th, tw = gray.shape
    at_default = None
    if fs.sizes_compatible(rgba.shape[1], rgba.shape[0], tw, th):
        at_default = fs.score_sprite(rgba, gray, lib.median_color(template_rgb))
    if at_default is None or (scale != lib.DEFAULT_SPRITE_SCALE and score - at_default >= CLEAR_WIN):
        return scale, score
    return lib.DEFAULT_SPRITE_SCALE, at_default


def sprite_size(indexes, label, sprite) -> str:
    entry = indexes[label].get(sprite)
    return f"{entry['w']}x{entry['h']}" if entry else ""


def same_art(sprites, extracted, other_extracted, label, own_indexes_label, indexes) -> str:
    """Correlation of the same sprites in another APK, or a note on why they cannot be compared."""
    parts = []
    for sprite in sprites:
        mine = rl.load_sprite_rgba(extracted, sprite)
        theirs = rl.load_sprite_rgba(other_extracted, sprite)
        if mine is None or theirs is None:
            return f"{label}: sprite absent"
        if mine.shape != theirs.shape:
            return f"{label}: size {mine.shape[1]}x{mine.shape[0]} vs {theirs.shape[1]}x{theirs.shape[0]}"
        flat = [lib.to_gray(lib.sprite_on_background(a, (128, 128, 128))) for a in (mine, theirs)]
        score = lib.best_score(flat[0], flat[1])
        if score is None:
            return f"{label}: flat sprite"
        parts.append(score)
    return f"{label}: {min(parts):.3f}"


def font_row(row, extracted, template_rgb, background):
    """Fill a font override: estimate the ink color, replay, return (row, draft)."""
    if not row["font_color"]:
        row["font_color"] = rl.hex_color(dt.estimate_colors(template_rgb)[0])
    row["font_stroke"] = row["font_stroke"] or "0"
    draft, text = rl.replay_font(extracted, row, background)
    return row, draft


def atlas_row(row, source, atlas_dir, template_path, template_rgb):
    banner = rl.banner_for(row["atlas"], source)
    rgba = rl.load_banner(atlas_dir, banner) if banner and atlas_dir else None
    if rgba is None:
        raise rl.Replay(f"banner {banner} not available (--atlas-dir)")
    scale = rl.parse_scale(row["scale"] or f"{lib.DEFAULT_SPRITE_SCALE:g}")
    draft = lib.sprite_on_background(lib.scale_sprite(rgba, scale), (40, 40, 40))
    cropped, printed = rl.quiet(dt.crop_to_reference, draft, template_rgb, template_path)
    insets = rl.re.search(r"insets (-?\d+,-?\d+,-?\d+,-?\d+)", printed)
    row["scale"] = rl.scale_text(scale)
    row["insets"] = insets.group(1) if insets else ""
    return row, cropped


def font_recipe(row, override, args, template_rgb, background, seen):
    """A font override on a template no sprite explains. It must reach the apk-template-asset bar for
    plain labels (0.80 at the worst shift); below that the template stays a screenshot case."""
    evidence, notes = [seen], [override["notes"]]
    row.update({k: override[k] for k in ("font", "font_size", "font_stroke", "font_color", "localization_key")})
    try:
        row, draft = font_row(row, args.extracted, template_rgb, lib.parse_color(background))
    except rl.Replay as exc:
        row["kind"] = "screenshot"
        notes.append(f"font override not measured: {exc}")
        return row, None, evidence, notes
    shifts = lib.shift_scores(template_rgb, draft)
    worst = None if shifts is None else min(shifts)
    if worst is None or worst < rl.GOOD_SCORE:
        shown = "flat" if worst is None else f"{worst:.3f}"
        evidence.append(f"font {row['font_size']} worst-shift {shown} below {rl.GOOD_SCORE}")
        for column in ("font", "font_size", "font_stroke", "font_color", "localization_key"):
            row[column] = ""
        row["kind"] = "screenshot"
        return row, None, evidence, notes
    row["kind"] = "font"
    return row, draft, evidence, notes


def build_recipe(name, args, source, template_rgb, sets, indexes, overrides, atlas_dir):
    """(recipe row, draft or None, find-sprite evidence text)."""
    path = os.path.join(args.templates, name)
    background = rl.hex_color(lib.median_color(template_rgb))
    row = {c: "" for c in rl.COLUMNS}
    row.update(template=name, background=background)
    notes, evidence = [], []
    override = overrides.get(name)

    try:
        if override and override["kind"] == "atlas":
            row.update(kind="atlas", atlas=override["atlas"], scale=override["scale"])
            row, draft = atlas_row(row, source, atlas_dir, path, template_rgb)
            notes.append(override["notes"])
            return row, draft, evidence, notes
        if override and override["kind"] == "manual":
            row.update(kind="manual")
            notes.append(override["notes"])
            return row, None, evidence, notes
    except rl.Replay as exc:
        row["kind"] = "screenshot"
        notes.append(f"override {override['kind']} not measured: {exc}")
        return row, None, evidence, notes

    if override and override["sprites"]:
        sprites = override["sprites"].split(";")
        label = override["sprite_apk"] or source
        extracted = args.extracted if label == source else args.extra[label]
        scale = rl.parse_scale(override["scale"]) if override["scale"] else lib.DEFAULT_SPRITE_SCALE
        notes.append(override["notes"])
        best_text = "override"
    else:
        found = pick_sprite(sets, template_rgb)
        best = found[0] if found else None
        others = [f for f in found[1:] if (f[2], f[3]) != (best[2], best[3])] if best else []
        second = f"; next {others[0][0]:.3f} {others[0][2]}/{others[0][3]}" if others else ""
        if best is None or best[0] < SPRITE_MIN:
            seen = (f"best sprite {best[0]:.3f} {best[2]}/{best[3]} x{best[4]:g} below {SPRITE_MIN}{second}"
                    if best else "no sprite fits (size or flat)")
            if override and override["kind"] == "font":
                return font_recipe(row, override, args, template_rgb, background, seen)
            row["kind"] = "screenshot"
            evidence.append(seen)
            return row, None, evidence, notes
        scale, score_at_scale = choose_scale(best, template_rgb)
        sprites = [f"{best[2]}/{best[3]}"]
        label = best[1]
        extracted = args.extracted if label == source else args.extra[label]
        best_text = f"find {score_at_scale:.3f} x{scale:g}{second}"
        if label != source:
            notes.append(f"sprite from the {label} APK: the {source} APK lacks the atlas")
    try:
        draft, insets, offsets = rl.derive_sprite_draft(extracted, sprites, scale, path, lib.parse_color(background))
    except rl.Replay as exc:
        row["kind"] = "screenshot"
        notes.append(f"not drafted: {exc}")
        return row, None, evidence, notes
    row.update(kind="layers" if len(sprites) > 1 else "sprite", sprite_apk=label, sprites=";".join(sprites),
               insets=insets, offsets=offsets, scale=rl.scale_text(scale),
               sprite_size=";".join(sprite_size(indexes, label, s) for s in sprites))
    evidence.append(best_text)
    return row, draft, evidence, notes


def read_overrides(path, source):
    rows = {}
    if os.path.exists(path):
        for row in rl.read_csv(path):
            if row["source"] in (source, "*"):
                rows[row["template"]] = row
    return rows


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--source", required=True, choices=["Kr", "Jp"])
    parser.add_argument("--extracted", required=True, help="extract_apk_ui.py folder of the source server's APK")
    parser.add_argument("--templates", required=True, help="the source server's templates, app/src/main/assets/<Source>")
    parser.add_argument("--screens", help="the source server's screenshots, docs/reference-images/<Source>")
    parser.add_argument("--other-templates", action="append", default=[], metavar="LABEL=DIR",
                        help="the other server's templates (Jp for Kr and the reverse): language-free evidence")
    parser.add_argument("--other-extracted", action="append", default=[], metavar="LABEL=DIR",
                        help="other APKs' extractions (NA, the other of KR/JP): same-art evidence")
    parser.add_argument("--extra-extracted", action="append", default=[], metavar="LABEL=DIR",
                        help="APK whose atlases the source APK lacks are also searched for sprites")
    parser.add_argument("--en-templates", help="app/src/main/assets/En: its copies are scored on the screenshots")
    parser.add_argument("--atlas-dir", help="Atlas Academy banners of the source server (atlas_banners.py), for atlas rows")
    parser.add_argument("--names", help="comma-separated template files to process; default: every .png")
    parser.add_argument("--overrides", default=os.path.join(rl.HERE, "overrides.csv"))
    parser.add_argument("--out", help="default: recipes/<Source>.csv next to this script")
    args = parser.parse_args()

    source = args.source
    out = args.out or os.path.join(rl.HERE, "recipes", f"{source}.csv")
    others_t = labelled(args.other_templates, "--other-templates")
    others_x = labelled(args.other_extracted, "--other-extracted")
    args.extra = labelled(args.extra_extracted, "--extra-extracted")
    overrides = read_overrides(args.overrides, source)
    entries = rl.entries_by_file()

    names = sorted(f for f in os.listdir(args.templates) if f.endswith(".png"))
    if args.names:
        names = [n for n in args.names.split(",") if n in names]
    primary = fs.load_sprites(args.extracted)
    have = {atlas for atlas, _, _ in primary}
    sets = [(source, fs.Scaled(primary, lib.DEFAULT_DEVICE_HEIGHT))]
    indexes = {source: rl.sprite_index(args.extracted)}
    for label, folder in args.extra.items():
        extra = [s for s in fs.load_sprites(folder) if s[0] not in have]
        sets.append((label, fs.Scaled(extra, lib.DEFAULT_DEVICE_HEIGHT)))
        indexes[label] = rl.sprite_index(folder)
        print(f"{label}: {len(extra)} sprites of atlases the {source} APK lacks")
    print(f"{len(primary)} {source} sprites, {len(names)} templates")

    rows, drafts, existing = {}, {}, {}
    for n, name in enumerate(names, 1):
        template_rgb = lib.load_rgb(os.path.join(args.templates, name))
        row, draft, evidence, notes = build_recipe(name, args, source, template_rgb, sets, indexes, overrides,
                                                   args.atlas_dir)
        row["images_entry"] = "+".join(entries.get(name, []))
        row["evidence"] = evidence
        row["notes"] = [x for x in notes if x]
        rows[name] = row
        existing[name] = template_rgb
        if draft is not None:
            drafts[name] = draft
            shifts = lib.shift_scores(template_rgb, draft)
            row["_worst"] = None if shifts is None else min(shifts)
        print(f"{n:3d}/{len(names)} {name:40s} {row['kind']:10s} {row['sprites'] or row['localization_key'] or ''}")

    # Language-free evidence from the other server's copies and from the other APKs' sprites
    for name, row in rows.items():
        proof = []
        for label, folder in others_t.items():
            other = os.path.join(folder, name)
            if os.path.exists(other):
                other_rgb = lib.load_rgb(other)
                (oh, ow), (mh, mw) = other_rgb.shape[:2], existing[name].shape[:2]
                near = fs.sizes_compatible(ow, oh, mw, mh)
                score = lib.best_score(lib.to_gray(existing[name]), lib.to_gray(other_rgb)) if near else None
                if score is not None:
                    proof.append(("copy", label, score, score >= LANGUAGE_FREE_COPY))
                else:
                    row["_notes_extra"] = row.get("_notes_extra", []) + [f"{label} copy size differs or flat"]
        if row["sprites"]:
            own = args_extracted_of(row, args, source)
            for label, folder in others_x.items():
                # The sprite's own APK would match itself
                if os.path.abspath(folder) == os.path.abspath(own):
                    continue
                text = same_art(row["sprites"].split(";"), own, folder, label,
                                source, indexes)
                row.setdefault("_art", []).append(text)
                if not text.endswith(("absent", "flat sprite")) and ": " in text:
                    tail = text.split(": ")[1]
                    try:
                        proof.append(("art", label, float(tail), float(tail) >= LANGUAGE_FREE_SPRITE))
                    except ValueError:
                        pass
        row["_proof"] = proof

    # One pass over the screenshots: the draft, the existing template and the En copy
    stats = {}
    if args.screens:
        grays = {}
        for name in drafts:
            grays[f"x:{name}"] = lib.to_gray(existing[name])
            grays[f"d:{name}"] = lib.to_gray(drafts[name])
        if args.en_templates:
            for name in rows:
                en = os.path.join(args.en_templates, name)
                if os.path.exists(en):
                    grays[f"e:{name}"] = rl.read_gray(en)
        scores = rl.screen_scores(args.screens, grays)
        for name in rows:
            entry = {}
            if f"d:{name}" in scores:
                ex = dict(scores[f"x:{name}"])
                draft_scores = scores[f"d:{name}"]
                pos = [(s, d) for s, d in draft_scores if ex.get(s, 0) >= 0.85]
                neg = [(s, d) for s, d in draft_scores if ex.get(s, 1) < 0.6]
                entry["pos"] = len(pos)
                entry["pos_min"] = min((d for _, d in pos), default=None)
                entry["pos_mean"] = float(np.mean([d for _, d in pos])) if pos else None
                entry["ex_min"] = min((ex[s] for s, _ in pos), default=None)
                worst = max(neg, key=lambda t: t[1], default=None)
                entry["false"] = worst
            if f"e:{name}" in scores and scores[f"e:{name}"]:
                entry["en_best"] = max(scores[f"e:{name}"], key=lambda t: t[1])
            stats[name] = entry

    final = []
    for name, row in rows.items():
        st = stats.get(name, {})
        evidence = list(row["evidence"])
        worst = row.pop("_worst", None)
        if worst is not None:
            evidence.append(f"worst-shift {worst:.3f}")
        if st.get("pos"):
            evidence.append(f"screens: {st['pos']} positives, existing min {st['ex_min']:.3f}, draft min "
                            f"{st['pos_min']:.3f} mean {st['pos_mean']:.3f}")
        elif args.screens and name in drafts:
            evidence.append("screens: no positive")
        if st.get("false"):
            evidence.append(f"highest false {st['false'][1]:.3f} ({st['false'][0]})")
        if name in drafts:
            if st.get("pos"):
                row["quality"] = "good" if st["pos_min"] >= rl.GOOD_SCORE else "weak"
            else:
                row["quality"] = "good" if worst is not None and worst >= rl.GOOD_SCORE else "weak"
        proof = row.pop("_proof", [])
        for kind, label, score, ok in proof:
            evidence.append(f"{'copy' if kind == 'copy' else 'same art'} {label} {score:.3f}")
        if st.get("en_best"):
            evidence.append(f"En copy best {st['en_best'][1]:.3f} on {st['en_best'][0]}")
            if st["en_best"][1] >= LANGUAGE_FREE_COPY:
                proof.append(("en", "En", st["en_best"][1], True))
        for text in row.pop("_art", []):
            if "size" in text or "absent" in text or "flat" in text:
                evidence.append(f"art {text}")
        notes = list(row["notes"]) + row.pop("_notes_extra", [])
        # A template whose art is the same on every server needs no recipe: the copy is the answer
        if any(ok for *_, ok in proof) and row["kind"] != "manual":
            if row["kind"] != "language-free":
                notes.append(f"recipe kept for reference: {row['kind']}")
            row["kind"] = "language-free"
        row["evidence"] = "; ".join(evidence)
        row["notes"] = "; ".join(n for n in notes if n)
        final.append(row)

    rl.write_csv(out, final, rl.COLUMNS)
    counts = {}
    for row in final:
        counts[row["kind"]] = counts.get(row["kind"], 0) + 1
    print(f"wrote {out}: {len(final)} templates; " + ", ".join(f"{k} {v}" for k, v in sorted(counts.items())))


def args_extracted_of(row, args, source):
    return args.extracted if row["sprite_apk"] == source else args.extra[row["sprite_apk"]]


if __name__ == "__main__":
    main()
