"""MkDocs hooks for the FGA(mod) multi-language manual site (see mkdocs.yml and docs/agents/docs-site.md).

The site is built straight from wiki/, supporting Korean (root /), English (/en/), and Japanese (/ja/):
- navigation is generated dynamically from wiki/ files, grouped into categories under the Guides tab;
- each logical page selects -KR.md, -EN.md, or -JA.md for the active build language, falling back to -KR.md with a notice banner when untranslated;
- only the active language's pages are kept in each build so search indexes stay isolated per language;
- Material's header language switcher links directly to the corresponding page in each language;
- GitHub-wiki style bare links and links leaving wiki/ (README, obtainium.json, ...) resolve properly;
- when building the primary Korean site, on_post_build also builds /en/ and /ja/ under site_dir in --strict mode.
"""
import os
import posixpath
import re
import subprocess
import sys
import tempfile
from pathlib import Path, PurePosixPath

from mkdocs.structure.files import File

HERE = Path(__file__).parent
ROOT = HERE.parent.parent
REPO_BLOB = "https://github.com/puruuun/FGA-Modified/blob/main/"
REPO_EDIT_ROOT = "https://github.com/puruuun/FGA-Modified/edit/main/"

LANG = os.environ.get("FGA_DOCS_LANG", "ko")
LANG_SUFFIX = {"ko": "KR", "en": "EN", "ja": "JA"}[LANG]

LOCALE_META = {
    "ko": {
        "site_name": "FGA(mod) 사용 설명서",
        "site_description": "Fate/Grand Automata 수정판 FGA(mod)의 기능별 사용 설명서",
        "theme_lang": "ko",
        "search_lang": ["ko", "en"],
        "toggle_dark": "어두운 화면",
        "toggle_light": "밝은 화면",
        "index_file": "index.md",
        "tabs": {
            "home": "홈",
            "getting_started": "시작하기",
            "guides": "기능 설명서",
            "releases": "릴리스 노트",
        },
        "categories": {
            "battle": "전투 · 카드",
            "support": "서포트 선택",
            "farming": "소환 · 강화 · 주회",
            "logs": "로그 · Chaldea 연동",
            "other": "화면 · 기타 기능",
        },
        "fallback_title": "",
        "fallback_body": "",
    },
    "en": {
        "site_name": "FGA(mod) User Manual",
        "site_description": "Feature-by-feature user manual for Fate/Grand Automata modified edition FGA(mod)",
        "theme_lang": "en",
        "search_lang": ["en"],
        "toggle_dark": "Dark mode",
        "toggle_light": "Light mode",
        "index_file": "index.en.md",
        "tabs": {
            "home": "Home",
            "getting_started": "Getting Started",
            "guides": "Guides",
            "releases": "Release Notes",
        },
        "categories": {
            "battle": "Battle & Cards",
            "support": "Support Selection",
            "farming": "Summon, Enhance & Farming",
            "logs": "Logs & Chaldea Sync",
            "other": "Screen & Other Features",
        },
        "fallback_title": "Translation pending",
        "fallback_body": "This page has not been translated into English yet and is currently displayed in Korean.",
    },
    "ja": {
        "site_name": "FGA(mod) ユーザーマニュアル",
        "site_description": "Fate/Grand Automata 修正版 FGA(mod) の機能別ユーザーマニュアル",
        "theme_lang": "ja",
        "search_lang": ["ja", "en"],
        "toggle_dark": "ダークモード",
        "toggle_light": "ライトモード",
        "index_file": "index.ja.md",
        "tabs": {
            "home": "ホーム",
            "getting_started": "はじめに",
            "guides": "機能ガイド",
            "releases": "リリースノート",
        },
        "categories": {
            "battle": "バトル・カード",
            "support": "サポート選択",
            "farming": "召喚・強化・周回",
            "logs": "ログ・Chaldea連携",
            "other": "画面・その他の機能",
        },
        "fallback_title": "未翻訳ページ",
        "fallback_body": "このページはまだ日本語に翻訳されていないため、韓国語のまま表示されています。",
    },
}

# Logical pages belonging to Getting Started, in display order.
GETTING_STARTED = [
    "scripts/Installation",
    "scripts/Log-Collection-Setup",
    "scripts/Original-FGA-Import",
]

# Sidebar categories inside the Guides tab. Any future scripts/* page not listed here
# is appended automatically under "other" so new pages still appear without touching hooks.py.
GUIDE_GROUPS = [
    (
        "battle",
        [
            "scripts/Card-Priority",
            "scripts/Card-Count-Start",
            "scripts/Hakuno-Shuffle",
            "scripts/Direct-Cards",
            "scripts/Enemy-Layout-Targets",
            "scripts/Turn-Limit-Retreat",
            "scripts/Command-Spell-Tap",
            "scripts/Mission-Popup-Clear",
        ],
    ),
    (
        "support",
        [
            "scripts/Support-Score-62",
            "scripts/Support-NP-Level",
            "scripts/Support-Append-Skills",
            "scripts/Support-Grand-CE",
            "scripts/Ascension-Select",
            "scripts/Servant-Number-Portraits",
            "scripts/Default-Support-Images",
        ],
    ),
    (
        "farming",
        [
            "scripts/FP-Auto-Sell",
            "scripts/Auto-Skill-Level",
            "scripts/CE-Auto-Enhance",
            "scripts/Weekly-Mission-Route",
            "scripts/Quest-Items",
            "scripts/Bond-Tracking",
        ],
    ),
    (
        "logs",
        [
            "scripts/Battle-Log",
            "scripts/Gains-Tracker",
            "scripts/Friend-Manager",
            "scripts/Capture-AdGuard",
            "scripts/Capture-Root",
            "scripts/Chaldea-Import",
            "scripts/Chaldea-Sync",
        ],
    ),
    (
        "other",
        [
            "scripts/Battle-Config-Folders",
            "scripts/Game-Area-Preview",
            "scripts/Added-Features-Fine-Tune",
            "scripts/Widgets",
            "scripts/Modified-Features",
        ],
    ),
]

# Research notes whose logical name contains one of these words are kept reachable by link
# but omitted from navigation and search.
REFERENCE_WORDS = ("Study", "Research")

_SUFFIX_RE = re.compile(r"-(KR|EN|JA)$")
_LINK = re.compile(r"(\]\()([^)\s]+)((?:\s+\"[^\"]*\")?\))")
_IMG_SRC = re.compile(r"(<img\s[^>]*src=\")([^\"]+)(\")")
_ALT_LINK = re.compile(r'<a\b[^>]*class="md-select__link"[^>]*>')
_HREF_ATTR = re.compile(r'\bhref="([^"]*)"')
_HREFLANG_ATTR = re.compile(r'\bhreflang="([^"]*)"')

_docs = Path(".")
_variants: dict[str, dict[str, str]] = {}
_chosen: dict[str, str] = {}
_rel_to_logical: dict[str, str] = {}
_active_files: set[str] = set()
_pages: dict[str, str] = {}
_in_nav: set[str] = set()


def _logical_and_lang(rel: str) -> tuple[str, str]:
    pure = PurePosixPath(rel)
    m = _SUFFIX_RE.search(pure.stem)
    if m:
        lang = m.group(1)
        base_stem = pure.stem[: m.start()]
    else:
        lang = "OTHER"
        base_stem = pure.stem
    logical = str(pure.with_name(base_stem)) if pure.parent != PurePosixPath(".") else base_stem
    return logical, lang


def _title(path: Path) -> str:
    """First H1 of the page without a trailing version ("— 0.9.2", "(1.4.0-beta.10)"), else the file name."""
    try:
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.startswith("# "):
                title = line[2:].strip()
                title = re.sub(r"\s*[—-]\s*v?\d+(\.\d+)+\S*$", "", title)
                title = re.sub(r"\s*\(v?\d+(\.\d+)+[^)]*\)$", "", title)
                return title
    except OSError:
        pass
    return path.stem.replace("-", " ")


def _version_key(logical_key: str):
    return [int(p) if p.isdigit() else p for p in re.split(r"[.\-]", PurePosixPath(logical_key).name)]


def _scan_pages(docs: Path) -> None:
    _variants.clear()
    _chosen.clear()
    _rel_to_logical.clear()
    _active_files.clear()

    md_files = sorted(
        p.relative_to(docs).as_posix()
        for p in docs.rglob("*.md")
        if not p.name.startswith("_")
    )
    for rel in md_files:
        logical, lang = _logical_and_lang(rel)
        _variants.setdefault(logical, {})[lang] = rel
        _rel_to_logical[rel] = logical

    for logical, by_lang in _variants.items():
        selected = by_lang.get(LANG_SUFFIX) or by_lang.get("KR")
        if selected:
            _chosen[logical] = selected
            _active_files.add(selected)


def _build_nav(docs: Path) -> list:
    meta = LOCALE_META[LANG]
    tabs = meta["tabs"]
    cats = meta["categories"]
    used: set[str] = set()

    def entry(logical_key: str):
        rel = _chosen[logical_key]
        used.add(logical_key)
        return {_title(docs / rel): rel}

    getting_started = [entry(k) for k in GETTING_STARTED if k in _chosen]

    # Mark research notes as used so they stay out of navigation and search.
    for k in list(_chosen):
        if k.startswith("scripts/") and any(w in k for w in REFERENCE_WORDS):
            used.add(k)

    categorized_guides = []
    assigned_in_groups = {k for _, keys in GUIDE_GROUPS for k in keys}

    for cat_key, keys in GUIDE_GROUPS:
        items = [entry(k) for k in keys if k in _chosen and k not in used]
        if cat_key == "other":
            # Append any uncategorized scripts/* page automatically, sorted by title.
            extra_keys = [
                k
                for k in _chosen
                if k.startswith("scripts/") and k not in used and k not in assigned_in_groups
            ]
            extra_items = sorted((entry(k) for k in extra_keys), key=lambda e: next(iter(e)))
            items.extend(extra_items)
        if items:
            categorized_guides.append({cats[cat_key]: items})

    release_keys = sorted(
        (k for k in _chosen if k.startswith("releases/") and k not in used),
        key=_version_key,
        reverse=True,
    )
    releases = [entry(k) for k in release_keys]

    nav = [{tabs["home"]: "index.md"}]
    if getting_started:
        nav.append({tabs["getting_started"]: getting_started})
    if categorized_guides:
        nav.append({tabs["guides"]: categorized_guides})
    if releases:
        nav.append({tabs["releases"]: releases})
    return nav


def _collect(items):
    for item in items:
        for value in item.values():
            _collect(value) if isinstance(value, list) else _in_nav.add(value)


def on_config(config):
    global _docs
    meta = LOCALE_META[LANG]
    _docs = Path(config["docs_dir"])

    if LANG != "ko":
        config["site_name"] = meta["site_name"]
        config["site_description"] = meta["site_description"]
        config["theme"]["language"] = meta["theme_lang"]
        palette = config["theme"].get("palette") or []
        if len(palette) >= 2:
            if "toggle" in palette[0]:
                palette[0]["toggle"]["name"] = meta["toggle_dark"]
            if "toggle" in palette[1]:
                palette[1]["toggle"]["name"] = meta["toggle_light"]

    search_plugin = config.get("plugins", {}).get("material/search")
    if search_plugin is not None and hasattr(search_plugin, "config"):
        search_plugin.config.lang = meta["search_lang"]

    _scan_pages(_docs)
    config["nav"] = _build_nav(_docs)
    _in_nav.clear()
    _collect(config["nav"])
    return config


def on_files(files, config):
    _pages.clear()
    kept = []
    for f in files:
        if f.src_uri.endswith(".md") and f.src_uri not in _active_files:
            continue
        kept.append(f)

    files._files = kept  # filter in place while preserving MkDocs Files container
    if hasattr(files, "_src_uris"):
        files._src_uris = {f.src_uri: f for f in kept}

    for f in files.documentation_pages():
        _pages.setdefault(PurePosixPath(f.src_uri).stem, f.src_uri)

    # Map every inactive variant's stem (e.g. FP-Auto-Sell-KR) to the active language's file.
    for logical, by_lang in _variants.items():
        active_rel = _chosen.get(logical)
        if not active_rel:
            continue
        for rel in by_lang.values():
            _pages.setdefault(PurePosixPath(rel).stem, active_rel)
        _pages.setdefault(PurePosixPath(logical).name, active_rel)

    index_path = HERE / LOCALE_META[LANG]["index_file"]
    if not index_path.exists():
        index_path = HERE / "index.md"
    index_content = index_path.read_text(encoding="utf-8")
    files.append(File.generated(config, "index.md", content=index_content))
    _pages["index"] = "index.md"
    return files


def _resolve_logical_target(rel_in_wiki: str) -> str | None:
    logical = _rel_to_logical.get(rel_in_wiki)
    if not logical:
        logical, _ = _logical_and_lang(rel_in_wiki)
    if logical in _chosen:
        return _chosen[logical]
    return None


def _rewrite(target: str, page_uri: str) -> str:
    if re.match(r"^[a-z][a-z0-9+.-]*:|^#|^/", target, re.I):
        return target
    path, _, anchor = target.partition("#")
    anchor = "#" + anchor if anchor else ""
    page_dir = PurePosixPath(page_uri).parent

    # GitHub wiki bare links (no folder, no extension) or stem matches.
    if path and "." not in PurePosixPath(path).name and PurePosixPath(path).name in _pages:
        dest = PurePosixPath(_pages[PurePosixPath(path).name])
        return _relative(dest, page_dir) + anchor

    # Links that climb out of wiki/ go to the repository on GitHub.
    if path:
        in_repo = posixpath.normpath(posixpath.join("wiki", str(page_dir), path))
        if not in_repo.startswith("wiki/") and not in_repo.startswith(".."):
            return REPO_BLOB + in_repo + anchor

    # Relative markdown links inside wiki/ map onto the active language's version if available.
    if path and path.endswith(".md"):
        rel_in_wiki = posixpath.normpath(posixpath.join(str(page_dir), path))
        mapped = _resolve_logical_target(rel_in_wiki)
        if mapped:
            return _relative(PurePosixPath(mapped), page_dir) + anchor

    # The GitHub wiki resolves image paths from the wiki root, whatever folder the page is in.
    if path and not (_docs / page_dir / path).exists() and (_docs / path).exists():
        return _relative(PurePosixPath(path), page_dir) + anchor

    return target


def _relative(dest: PurePosixPath, page_dir: PurePosixPath) -> str:
    up = [".."] * len(page_dir.parts)
    return "/".join(up + [str(dest)]) if up else str(dest)


def _inject_fallback_notice(markdown: str, meta: dict) -> str:
    banner = f'!!! note "{meta["fallback_title"]}"\n    {meta["fallback_body"]}\n\n'
    lines = markdown.splitlines(keepends=True)
    for i, line in enumerate(lines):
        if line.startswith("# "):
            return "".join(lines[: i + 1]) + "\n" + banner + "".join(lines[i + 1 :]).lstrip("\r\n")
    return banner + markdown


def on_page_markdown(markdown, page, config, files):
    uri = page.file.src_uri
    if uri not in _in_nav:
        page.meta.setdefault("search", {})["exclude"] = True

    if uri != "index.md" and LANG_SUFFIX != "KR" and uri.endswith("-KR.md"):
        markdown = _inject_fallback_notice(markdown, LOCALE_META[LANG])

    markdown = _LINK.sub(lambda m: m.group(1) + _rewrite(m.group(2), uri) + m.group(3), markdown)
    return _IMG_SRC.sub(lambda m: m.group(1) + _rewrite(m.group(2), uri) + m.group(3), markdown)


def on_page_context(context, page, config, nav):
    if page.file.src_uri == "index.md":
        index_name = LOCALE_META[LANG]["index_file"]
        if not (HERE / index_name).exists():
            index_name = "index.md"
        page.edit_url = f"{REPO_EDIT_ROOT}tools/docs-site/{index_name}"
    return context


def _alt_href(page, target_lang: str) -> str:
    depth_in_lang = len([p for p in page.url.split("/") if p])
    total_depth = depth_in_lang + (0 if LANG == "ko" else 1)
    up = "../" * total_depth
    prefix = "" if target_lang == "ko" else f"{target_lang}/"

    src_uri = page.file.src_uri
    if src_uri == "index.md":
        rel = f"{up}{prefix}"
        return rel or "./"

    logical = _rel_to_logical.get(src_uri)
    if logical:
        target_suffix = {"ko": "KR", "en": "EN", "ja": "JA"}[target_lang]
        target_src = _variants.get(logical, {}).get(target_suffix) or _variants.get(logical, {}).get("KR")
        if target_src:
            page_dir_url = target_src[:-3] + "/"
            return f"{up}{prefix}{page_dir_url}"

    return f"{up}{prefix}" or "./"


def on_post_page(output, page, config):
    def replace_alt(match):
        tag = match.group(0)
        lang_m = _HREFLANG_ATTR.search(tag)
        if not lang_m:
            return tag
        target_lang = lang_m.group(1)
        if target_lang not in ("ko", "en", "ja"):
            return tag
        new_href = _alt_href(page, target_lang)
        return _HREF_ATTR.sub(f'href="{new_href}"', tag, count=1)

    return _ALT_LINK.sub(replace_alt, output)


def on_post_build(config):
    if os.environ.get("FGA_DOCS_SUBBUILD") == "1" or LANG != "ko":
        return

    base_site_dir = Path(config["site_dir"]).resolve()
    base_url = (config.get("site_url") or "https://puruuun.github.io/FGA-Modified/").rstrip("/")
    base_cfg = Path(config.config_file_path).resolve()

    with tempfile.TemporaryDirectory() as tmpdir:
        tmp = Path(tmpdir)
        for sub_lang in ("en", "ja"):
            sub_site_dir = base_site_dir / sub_lang
            sub_cfg = tmp / f"mkdocs.{sub_lang}.yml"
            search_langs = "\n".join(f"        - {l}" for l in LOCALE_META[sub_lang]["search_lang"])
            sub_cfg.write_text(
                "\n".join(
                    [
                        f"INHERIT: {base_cfg.as_posix()}",
                        f"site_url: {base_url}/{sub_lang}/",
                        f"docs_dir: {Path(config['docs_dir']).resolve().as_posix()}",
                        f"site_dir: {sub_site_dir.as_posix()}",
                        "hooks:",
                        f"  - {(HERE / 'hooks.py').resolve().as_posix()}",
                        "plugins:",
                        "  - search:",
                        "      lang:",
                        search_langs,
                        "      separator: '[\\s\\-,:!=\\[\\]()\"`/]+|\\.(?!\\d)'",
                        "",
                    ]
                ),
                encoding="utf-8",
            )
            env = {
                **os.environ,
                "FGA_DOCS_LANG": sub_lang,
                "FGA_DOCS_SUBBUILD": "1",
            }
            subprocess.run(
                [sys.executable, "-m", "mkdocs", "build", "--strict", "-f", str(sub_cfg)],
                check=True,
                cwd=str(ROOT),
                env=env,
            )
