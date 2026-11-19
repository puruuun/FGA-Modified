"""Shared helpers of the server-port scripts: recipe CSV, Images.kt, localization tables, replaying a recipe.

Everything that draws or scores pixels is the apk-template-asset code (template_lib, find_sprite,
draft_template, score_screens), imported from the sibling skill, so a recipe replays exactly what
`draft_template.py` would draw from the same options.
"""

import contextlib
import csv
import io
import json
import os
import re
import sys
from types import SimpleNamespace

# Keeps a __pycache__ out of both skill folders
sys.dont_write_bytecode = True

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
sys.path.insert(0, os.path.join(HERE, "..", "apk-template-asset"))

import cv2  # noqa: E402
import numpy as np  # noqa: E402
from PIL import Image  # noqa: E402

import draft_template as dt  # noqa: E402
import find_sprite as fs  # noqa: E402
import score_screens as ss  # noqa: E402
import template_lib as lib  # noqa: E402

COLUMNS = ["template", "images_entry", "kind", "quality", "sprite_apk", "sprites", "sprite_size", "insets",
           "offsets", "scale", "background", "font", "font_size", "font_stroke", "font_color",
           "localization_key", "atlas", "atlas_unverified", "evidence", "notes"]

# Kinds a script can replay on another server's APK
REPLAYABLE = ("sprite", "layers", "font", "atlas")

# Where a draft is judged good: the existing template's own 0.85 "positive" bar of score_screens.py is
# the point from which a screenshot counts as showing the label; a draft is good when it reaches the
# 0.80 that the apk-template-asset skill calls usable, on screenshots, or at its worst shift without any
GOOD_SCORE = 0.80


def read_csv(path: str) -> list[dict]:
    with open(path, encoding="utf-8-sig", newline="") as f:
        return list(csv.DictReader(f))


def write_csv(path: str, rows: list[dict], columns: list[str]) -> None:
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, columns, extrasaction="ignore", lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def parse_images(repo: str = REPO) -> list[tuple[str, str]]:
    """(enum entry, file name) of every Images entry."""
    path = os.path.join(repo, "scripts", "src", "main", "java", "io", "github", "fate_grand_automata",
                        "scripts", "Images.kt")
    with open(path, encoding="utf-8") as f:
        return re.findall(r'^\s*(\w+)\("([^"]+\.png)"', f.read(), re.M)


def entries_by_file(repo: str = REPO) -> dict[str, list[str]]:
    result: dict[str, list[str]] = {}
    for name, file in parse_images(repo):
        result.setdefault(file, []).append(name)
    return result


_LINE = re.compile(r'^\s*"((?:[^"\\]|\\.)+)"\s*:\s*"((?:[^"\\]|\\.)*)"\s*,?\s*(?://.*)?$')


def load_localization(extracted: str) -> dict[str, str]:
    """text/LocalizationJpn.txt of an extraction: widget path or key -> text.

    The file is JSON with comments and stray commas, so it is read line by line.
    """
    path = os.path.join(extracted, "text", "LocalizationJpn.txt")
    table = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            match = _LINE.match(line)
            if match:
                try:
                    table[json.loads(f'"{match.group(1)}"')] = json.loads(f'"{match.group(2)}"')
                except ValueError:
                    pass
    return table


def strip_markup(text: str) -> tuple[str, str | None]:
    """The text without NGUI [RRGGBB] / [-] markup, and the first color in it."""
    color = re.search(r"\[([0-9A-Fa-f]{6})\]", text)
    clean = re.sub(r"\[(?:[0-9A-Fa-f]{6}|-)\]", "", text)
    return clean, color.group(1) if color else None


def hex_color(rgb) -> str:
    return "".join(f"{int(v):02X}" for v in rgb)


def read_gray(path: str) -> np.ndarray:
    image = cv2.imdecode(np.fromfile(path, np.uint8), cv2.IMREAD_GRAYSCALE)
    if image is None:
        sys.exit(f"cannot read {path}")
    return image


def sprite_index(extracted: str) -> dict[str, dict]:
    """'Atlas/name' -> {w, h} of every sprite of an extraction."""
    with open(os.path.join(extracted, "index.json"), encoding="utf-8") as f:
        index = json.load(f)
    return {f"{atlas}/{e['name']}": e for atlas, entries in index["atlases"].items() for e in entries}


def sprite_path(extracted: str, sprite: str) -> str:
    atlas, _, name = sprite.partition("/")
    return os.path.join(extracted, "sprites", fs.lib_safe(atlas), fs.lib_safe(name) + ".png")


def load_sprite_rgba(extracted: str, sprite: str) -> np.ndarray | None:
    path = sprite_path(extracted, sprite)
    return np.array(Image.open(path).convert("RGBA")) if os.path.exists(path) else None


def parse_scale(text: str):
    values = tuple(float(v) for v in text.split(","))
    return values[0] if len(values) == 1 else values


def scale_text(scale) -> str:
    return ",".join(f"{v:g}" for v in scale) if isinstance(scale, tuple) else f"{scale:g}"


def parse_ints(text: str) -> tuple[int, ...]:
    return tuple(int(v) for v in text.split(",")) if text else ()


def banner_file(atlas_dir: str, banner: str) -> str:
    """Atlas Academy banner 'Banner/name.png' as saved by atlas_banners.py."""
    return os.path.join(atlas_dir, banner.replace("/", "__"))


def banner_for(atlas_field: str, server: str) -> str | None:
    """The banner of `server` in an Atlas field such as 'Kr=Banner/a.png;Jp=Banner/b.png'."""
    for part in atlas_field.split(";"):
        key, _, value = part.partition("=")
        if key.strip() == server:
            return value.strip()
    return None


def load_banner(atlas_dir: str, banner: str) -> np.ndarray | None:
    path = banner_file(atlas_dir, banner)
    if not os.path.exists(path):
        return None
    return np.array(Image.open(path).convert("RGBA"))


class Replay(Exception):
    """A recipe cannot be replayed on this extraction; the message says why."""


def sprite_args(extracted, sprites, scale, offsets=(), inset=None, layers_like=None, crop_like=None):
    return SimpleNamespace(extracted=extracted, sprite=list(sprites), scale=scale,
                           device_height=lib.DEFAULT_DEVICE_HEIGHT, offset=list(offsets), layers_like=layers_like,
                           crop=None, inset=inset, crop_like=crop_like)


def quiet(function, *args):
    """(result, printed text) of a draft_template function, which prints what it found."""
    out = io.StringIO()
    try:
        with contextlib.redirect_stdout(out):
            return function(*args), out.getvalue()
    except SystemExit as exc:
        raise Replay(str(exc.code)) from None


def derive_sprite_draft(extracted, sprites, scale, template_path, background):
    """Draft `sprites` against an existing template: returns (draft, insets, offsets) found with
    crop-like and layers-like, as draft_template.py prints them."""
    for sprite in sprites:
        if not os.path.exists(sprite_path(extracted, sprite)):
            raise Replay(f"no sprite {sprite} in {extracted}")
    reference = lib.load_rgb(template_path)
    layered = len(sprites) > 1
    args = sprite_args(extracted, sprites, scale, layers_like=template_path if layered else None,
                       crop_like=template_path)
    draft, printed = quiet(dt.draft_sprite, args, background, reference)
    insets = re.search(r"insets (-?\d+,-?\d+,-?\d+,-?\d+)", printed)
    offsets = re.findall(r"--offset (-?\d+,-?\d+)", printed)
    return draft, insets.group(1) if insets else "", ";".join(offsets)


def replay_sprite(extracted, row: dict, background) -> np.ndarray:
    sprites = row["sprites"].split(";")
    for sprite in sprites:
        if not os.path.exists(sprite_path(extracted, sprite)):
            raise Replay(f"sprite {sprite} is not in the APK")
    offsets = [tuple(int(v) for v in o.split(",")) for o in row["offsets"].split(";") if o]
    inset = parse_ints(row["insets"]) or None
    args = sprite_args(extracted, sprites, parse_scale(row["scale"] or f"{lib.DEFAULT_SPRITE_SCALE:g}"),
                       offsets, inset)
    draft, _ = quiet(dt.draft_sprite, args, background, None)
    return draft


def replay_atlas(atlas_dir, server: str, row: dict) -> np.ndarray:
    banner = banner_for(row["atlas"], server)
    if not banner:
        raise Replay(f"the recipe names no {server} banner")
    if not atlas_dir:
        raise Replay("needs --atlas-dir (Atlas Academy banners, downloaded with the owner's approval)")
    rgba = load_banner(atlas_dir, banner)
    if rgba is None:
        raise Replay(f"{banner} is not in {atlas_dir}")
    scale = parse_scale(row["scale"] or f"{lib.DEFAULT_SPRITE_SCALE:g}")
    scaled = lib.scale_sprite(rgba, scale)
    draft = lib.sprite_on_background(scaled, (40, 40, 40))
    inset = parse_ints(row["insets"])
    return dt.apply_inset(draft, inset) if inset else draft


def replay_font(extracted, row: dict, background) -> tuple[np.ndarray, str]:
    key = row["localization_key"]
    text = load_localization(extracted).get(key)
    if not text:
        raise Replay(f"key {key} is not in the APK's localization table")
    text, markup = strip_markup(text)
    text = text.strip()
    font_path = os.path.join(extracted, "fonts", row["font"])
    if not os.path.exists(font_path):
        raise Replay(f"font {row['font']} is not in the APK")
    color = lib.parse_color(row["font_color"] or markup or "FFFFFF")
    draft = lib.render(font_path, text, color, float(row["font_size"]), float(row["font_stroke"] or 0), background)
    return draft, text


def replay(row: dict, extracted: str, server: str, atlas_dir: str | None = None):
    """(draft RGB, note) of a sprite, layers, font or atlas recipe on another server's extraction."""
    background = lib.parse_color(row["background"]) if row["background"] else (40, 40, 40)
    kind = row["kind"]
    if kind in ("sprite", "layers"):
        return replay_sprite(extracted, row, background), ""
    if kind == "atlas":
        return replay_atlas(atlas_dir, server, row), ""
    if kind == "font":
        draft, text = replay_font(extracted, row, background)
        return draft, text
    raise Replay(f"kind {kind} cannot be replayed")


def screen_grays(folder: str):
    """(relative name, grayscale game area at 720p) of every readable screenshot, one at a time."""
    ct = lib.crop_template_module()
    for path in ss.screenshot_paths(folder):
        gray = ss.game_area_gray(path, ct)
        if gray is not None:
            yield os.path.relpath(path, folder).replace(os.sep, "/"), gray


def screen_scores(folder: str, templates: dict[str, np.ndarray]) -> dict[str, list[tuple[str, float]]]:
    """name -> [(screenshot, best score)] the way FGA matches: grayscale 720p game area, TM_CCOEFF_NORMED."""
    result: dict[str, list[tuple[str, float]]] = {name: [] for name in templates}
    for shown, gray in screen_grays(folder):
        for name, template in templates.items():
            score = ss.best(gray, template)
            if score is not None:
                result[name].append((shown, score))
    return result


def to_text(value) -> str:
    return "" if value is None else str(value)


# Atlases the game replaces by a downloaded localized one live in the server's asset list under
# _Data/Scenes/<Scene>/Atlas; the scene names the atlases by prefix (CommonUIAtlas is Common's)
SCENE_PREFIX_EXTRA = {"Summon": ("Gacha",)}


def load_asset_paths(path: str) -> set[str]:
    with open(path, encoding="utf-8") as f:
        return {entry["path"] for entry in json.load(f)}


def download_status(atlas: str, asset_paths: set[str] | None) -> str:
    """'replaceable' when the target's asset list holds a downloaded atlas of this name or of its scene,
    'not listed' when it holds none, 'unknown' without a list."""
    if asset_paths is None:
        return "unknown"
    for path in asset_paths:
        parts = path.split("/")
        if parts[-1] == atlas:
            return "replaceable"
        if len(parts) == 4 and parts[0] == "_Data" and parts[1] == "Scenes" and parts[3] == "Atlas":
            scene = parts[2]
            if atlas.startswith((scene,) + SCENE_PREFIX_EXTRA.get(scene, ())):
                return "replaceable"
    return "not listed"
