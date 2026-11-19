#!/usr/bin/env python3
"""Pull the UI fonts, string tables, atlas textures and atlas sprites out of a game APK.

Requires Pillow and UnityPy. On Windows run it with `py -3 -E -P` so nothing in the current
folder is imported. Nothing is written next to the input; DIR gets:

    fonts/      every Font as .otf/.ttf
    text/       every Localization* TextAsset
    textures/   Texture2D with its `_alpha` texture merged in as the alpha channel
    sprites/<Atlas>/<sprite>.png   every NGUI atlas sprite, cut at its own size
    index.json  atlas -> sprites with x, y, w, h, and the texture sizes

    extract_apk_ui.py kr.apk --out-dir build/apk-kr
    extract_apk_ui.py na.xapk --out-dir build/apk-na

The output is the game's copyrighted material. Keep DIR outside the repository's tracked paths
(the scratchpad or build/) and never commit anything from it.
"""

import argparse
import collections
import io
import json
import os
import re
import shutil
import struct
import sys
import tempfile
import zipfile

try:
    import UnityPy
    from PIL import Image, ImageFont
except ImportError:
    sys.exit("Pillow and UnityPy are required: pip install pillow UnityPy")

DATA_PREFIX = "assets/bin/Data/"


def safe_name(name: str) -> str:
    return re.sub(r"[^\w.\-]+", "_", name or "unnamed")


def find_data_apk(package: str) -> zipfile.ZipFile:
    """The archive that holds assets/bin/Data/.

    An XAPK is a zip of several APKs; the Unity data sits in the base APK, but split names vary
    between stores, so look for the data instead of for a file name.
    """
    outer = zipfile.ZipFile(package)
    if any(n.startswith(DATA_PREFIX) for n in outer.namelist()):
        return outer
    for name in outer.namelist():
        if not name.lower().endswith(".apk"):
            continue
        inner = zipfile.ZipFile(io.BytesIO(outer.read(name)))
        if any(n.startswith(DATA_PREFIX) for n in inner.namelist()):
            print(f"using {name} from the XAPK")
            return inner
    sys.exit(f"{package} has no {DATA_PREFIX}: it is not a Unity game build, "
             "or the Unity data is in an expansion file that was not given")


def unpack_data(archive: zipfile.ZipFile, work: str) -> str:
    for name in archive.namelist():
        if name.startswith(DATA_PREFIX) and not name.endswith("/"):
            archive.extract(name, work)
    return os.path.join(work, *DATA_PREFIX.strip("/").split("/"))


def export_fonts(env, out_dir: str) -> list[dict]:
    os.makedirs(out_dir, exist_ok=True)
    fonts = []
    for obj in env.objects:
        if obj.type.name != "Font":
            continue
        font = obj.read()
        data = bytes(font.m_FontData or b"")
        if not data:
            continue  # a dynamic font that points at an OS font holds no file
        ext = ".otf" if data[:4] == b"OTTO" else ".ttf"
        path = os.path.join(out_dir, safe_name(font.m_Name) + ext)
        with open(path, "wb") as f:
            f.write(data)
        try:
            family, style = ImageFont.truetype(path, 20).getname()
        except OSError:
            family, style = "?", "?"
        print(f"font {os.path.basename(path)}: family {family!r}, style {style!r}, {len(data)} bytes")
        fonts.append({"file": os.path.basename(path), "family": family, "style": style, "bytes": len(data)})
    return fonts


def export_text(env, out_dir: str) -> list[dict]:
    os.makedirs(out_dir, exist_ok=True)
    tables = []
    for obj in env.objects:
        if obj.type.name != "TextAsset":
            continue
        asset = obj.read()
        if not asset.m_Name.startswith("Localization"):
            continue
        script = asset.m_Script
        # UnityPy returns str when it could decode the bytes, with the rest smuggled through
        data = script.encode("utf-8", "surrogateescape") if isinstance(script, str) else bytes(script)
        path = os.path.join(out_dir, safe_name(asset.m_Name) + ".txt")
        with open(path, "wb") as f:
            f.write(data)
        lines = data.count(b"\n") + 1
        print(f"text {os.path.basename(path)}: {lines} lines")
        tables.append({"file": os.path.basename(path), "lines": lines})
    return tables


def read_textures(env) -> tuple[dict[str, Image.Image], int]:
    """Every decodable Texture2D by name, and how many Texture2D objects there are.

    A repeated name gets _2, _3, ... in file order. A few textures point at streaming data that
    is not in the APK and cannot be decoded; they are counted but skipped.
    """
    textures: dict[str, Image.Image] = {}
    seen = collections.Counter()
    total = 0
    for obj in env.objects:
        if obj.type.name != "Texture2D":
            continue
        total += 1
        try:
            tex = obj.read()
            image = tex.image
        except Exception as e:  # noqa: BLE001 - a format the decoder lacks must not stop the run
            print(f"skipped texture {getattr(obj, 'path_id', '?')}: {e}")
            continue
        name = safe_name(tex.m_Name)
        seen[name] += 1
        if seen[name] > 1:
            name = f"{name}_{seen[name]}"
        textures[name] = image
    return textures, total


def merge_alpha(textures: dict[str, Image.Image]) -> dict[str, Image.Image]:
    """NGUI atlases keep alpha in a second texture: X_alpha holds it in its red channel.

    A repeated name's suffix goes after `_alpha` (X_2 pairs with X_alpha_2).
    """
    merged = {}
    for name, image in textures.items():
        if "_alpha" in name:
            continue
        m = re.fullmatch(r"(.*)_(\d+)", name)
        alpha_name = f"{name}_alpha"
        if m and f"{m.group(1)}_alpha_{m.group(2)}" in textures:
            alpha_name = f"{m.group(1)}_alpha_{m.group(2)}"
        rgba = image.convert("RGBA")
        if alpha_name in textures:
            alpha = textures[alpha_name].convert("RGB").split()[0]
            if alpha.size != rgba.size:
                alpha = alpha.resize(rgba.size)
            rgba.putalpha(alpha)
        merged[name] = rgba
    return merged


def atlas_texture(atlas: str, entries: list[tuple], textures: dict[str, Image.Image]) -> Image.Image | None:
    """The texture of an atlas: the one named like it, else a close name that holds every rect.

    Atlas and texture names differ for a few atlases (WarBoardAtlas -> WarBoardAtlasTexture,
    ef_ultimate_a -> ef_ultimate). The rect check keeps a wrong guess from cutting garbage.
    """
    if atlas in textures:
        return textures[atlas]
    base = re.sub(r"_\d+$", "", atlas)
    for name in (atlas + "Texture", base + "Texture", re.sub(r"_?a$", "", base)):
        sheet = textures.get(name)
        if sheet and all(x + w <= sheet.width and y + h <= sheet.height for _, x, y, w, h in entries):
            return sheet
    return None


def parse_sprite_list(raw: bytes, start: int) -> list[tuple] | None:
    """NGUI UISpriteData[]: string name; int x, y, width, height; int border[4]; int padding[4]."""
    count = struct.unpack_from("<i", raw, start)[0]
    if not 3 <= count <= 5000:
        return None
    p = start + 4
    sprites = []
    for _ in range(count):
        length = struct.unpack_from("<i", raw, p)[0]
        if not 0 < length < 128:
            return None
        name = raw[p + 4:p + 4 + length].decode("utf-8")
        p += 4 + length
        p += (-p) % 4
        x, y, w, h = struct.unpack_from("<4i", raw, p)
        p += 48
        if not (0 <= x <= 4096 and 0 <= y <= 4096 and 0 < w <= 4096 and 0 < h <= 4096):
            return None
        sprites.append((name, x, y, w, h))
    return sprites


def read_atlases(env) -> dict[str, list[tuple]]:
    """UIAtlas sprite lists by atlas name.

    The game is an il2cpp build with no type tree, so the MonoBehaviour's raw bytes are scanned for
    a list with the UISpriteData layout. After the 32-byte header (m_GameObject, m_Enabled,
    m_Script, empty m_Name) the list starts within the first few hundred bytes. The atlas is named
    after its GameObject, which is also the name of its texture. Rects are top-left origin in the
    texture as UnityPy exports it (already flipped).
    """
    atlases: dict[str, list[tuple]] = {}
    for obj in env.objects:
        if obj.type.name != "MonoBehaviour":
            continue
        try:
            raw = obj.get_raw_data()
        except Exception:  # noqa: BLE001
            continue
        if len(raw) < 200:
            continue
        found = None
        for start in range(32, min(len(raw) - 8, 600), 4):
            try:
                found = parse_sprite_list(raw, start)
            except (struct.error, UnicodeDecodeError):
                found = None
            if found:
                break
        if not found:
            continue
        game_object = struct.unpack_from("<q", raw, 4)[0]
        try:
            name = obj.assets_file.objects[game_object].read().m_Name
        except Exception:  # noqa: BLE001
            name = f"pid{obj.path_id}"
        key, n = name, 2
        while key in atlases and atlases[key] != found:
            key = f"{name}_{n}"
            n += 1
        atlases[key] = found
    return atlases


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("package", help=".apk or .xapk")
    parser.add_argument("--out-dir", required=True, help="empty or new folder for the extracted files")
    args = parser.parse_args()

    out = os.path.abspath(args.out_dir)
    if os.path.abspath(args.package).startswith(out + os.sep):
        sys.exit("--out-dir must not contain the input file")
    os.makedirs(out, exist_ok=True)

    archive = find_data_apk(args.package)
    work = tempfile.mkdtemp(prefix="unpack-", dir=out)
    try:
        env = UnityPy.load(unpack_data(archive, work))
        fonts = export_fonts(env, os.path.join(out, "fonts"))
        tables = export_text(env, os.path.join(out, "text"))
        raw_textures, texture_total = read_textures(env)
        textures = merge_alpha(raw_textures)
        atlases = read_atlases(env)
        del env  # on Windows its open files keep the temp folder locked
    finally:
        shutil.rmtree(work, ignore_errors=True)

    tex_dir = os.path.join(out, "textures")
    os.makedirs(tex_dir, exist_ok=True)
    for name, image in textures.items():
        image.save(os.path.join(tex_dir, name + ".png"))

    index = {"fonts": fonts, "text": tables,
             "textures": {n: [i.width, i.height] for n, i in textures.items()}, "atlases": {}}
    sprite_count = 0
    for atlas, entries in atlases.items():
        sheet = atlas_texture(atlas, entries, textures)
        if sheet is None:
            print(f"atlas {atlas} has no texture, skipped")
            continue
        folder = os.path.join(out, "sprites", safe_name(atlas))
        os.makedirs(folder, exist_ok=True)
        listed = []
        for name, x, y, w, h in entries:
            if x + w > sheet.width or y + h > sheet.height:
                continue
            sheet.crop((x, y, x + w, y + h)).save(os.path.join(folder, safe_name(name) + ".png"))
            listed.append({"name": name, "x": x, "y": y, "w": w, "h": h})
        index["atlases"][atlas] = listed
        sprite_count += len(listed)

    with open(os.path.join(out, "index.json"), "w", encoding="utf-8") as f:
        json.dump(index, f, ensure_ascii=False, indent=1)
    print(f"{texture_total} Texture2D objects, {len(raw_textures)} decoded")
    print(f"{len(fonts)} fonts, {len(tables)} text tables, {len(textures)} merged textures, "
          f"{len(index['atlases'])} atlases, {sprite_count} sprites -> {out}")


if __name__ == "__main__":
    main()
