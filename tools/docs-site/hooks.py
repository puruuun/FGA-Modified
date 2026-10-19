"""MkDocs hooks for the FGA(mod) manual site (see mkdocs.yml and docs/agents/docs-site.md).

The site is built straight from wiki/, which is also the GitHub wiki source. These hooks keep both working:
- the navigation is generated from the files, so a new page appears without editing mkdocs.yml;
- GitHub-wiki style links ("Page-Name", no folder, no .md) are resolved to the real file;
- links that leave wiki/ (README, obtainium.json, ...) point to the file on GitHub;
- the landing page comes from tools/docs-site/index.md, so the wiki itself gets no extra page.
"""
import posixpath
import re
from pathlib import Path, PurePosixPath

from mkdocs.structure.files import File

HERE = Path(__file__).parent
REPO_BLOB = "https://github.com/puruuun/FGA-Modified/blob/main/"

# Pages that belong to "시작하기", in this order. Everything else is grouped by its path and name.
GETTING_STARTED = [
    "scripts/Modified-Features-KR.md",
    "scripts/Original-FGA-Import-KR.md",
    "Running-on-Emulators.md",
    "Troubleshooting.md",
    "Game-Area-detection.md",
    "Fine-tune.md",
]
# Research notes: KR pages whose name contains one of these words go to "참고 자료" instead of the feature list.
REFERENCE_WORDS = ("Study", "Research")

_LINK = re.compile(r"(\]\()([^)\s]+)((?:\s+\"[^\"]*\")?\))")
_IMG_SRC = re.compile(r"(<img\s[^>]*src=\")([^\"]+)(\")")


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


def _version_key(path: str):
    return [int(p) if p.isdigit() else p for p in re.split(r"[.\-]", PurePosixPath(path).stem)]


def _build_nav(docs: Path) -> list:
    pages = sorted(
        p.relative_to(docs).as_posix()
        for p in docs.rglob("*.md")
        if not p.name.startswith("_")
    )
    used = set()

    def entry(rel):
        used.add(rel)
        return {_title(docs / rel): rel}

    getting_started = [entry(p) for p in GETTING_STARTED if p in pages]
    korean = [p for p in pages if p not in used and p.startswith("scripts/") and p.endswith("-KR.md")]
    reference = [entry(p) for p in korean if any(w in p for w in REFERENCE_WORDS)]
    features = sorted((entry(p) for p in korean if p not in used), key=lambda e: next(iter(e)))
    releases = [entry(p) for p in sorted((p for p in pages if p.startswith("releases/")), key=_version_key, reverse=True)]
    japanese = [entry(p) for p in pages if p not in used and p.endswith("-JA.md")]
    english = [entry(p) for p in pages if p not in used]

    nav = [{"홈": "index.md"}]
    for name, items in (("시작하기", getting_started), ("기능 설명서", features), ("릴리스 노트", releases),
                        ("참고 자료", reference), ("日本語", japanese), ("English (원본 FGA)", english)):
        if items:
            nav.append({name: items})
    return nav


_docs = Path(".")


def on_config(config):
    global _docs
    _docs = Path(config["docs_dir"])
    config["nav"] = _build_nav(_docs)
    return config


_pages: dict = {}


def on_files(files, config):
    _pages.clear()
    for f in files.documentation_pages():
        _pages.setdefault(PurePosixPath(f.src_uri).stem, f.src_uri)
    index = (HERE / "index.md").read_text(encoding="utf-8")
    files.append(File.generated(config, "index.md", content=index))
    _pages["index"] = "index.md"
    return files


def _rewrite(target: str, page_uri: str) -> str:
    if re.match(r"^[a-z][a-z0-9+.-]*:|^#|^/", target, re.I):
        return target
    path, _, anchor = target.partition("#")
    anchor = "#" + anchor if anchor else ""
    page_dir = PurePosixPath(page_uri).parent
    # GitHub wiki links name a page without folder or extension.
    if path and "." not in PurePosixPath(path).name and PurePosixPath(path).name in _pages:
        dest = PurePosixPath(_pages[PurePosixPath(path).name])
        return _relative(dest, page_dir) + anchor
    # The GitHub wiki resolves image paths from the wiki root, whatever folder the page is in.
    if path and not (_docs / page_dir / path).exists() and (_docs / path).exists():
        return _relative(PurePosixPath(path), page_dir) + anchor
    # Links that climb out of wiki/ go to the repository on GitHub.
    if path:
        in_repo = posixpath.normpath(posixpath.join("wiki", str(page_dir), path))
        if not in_repo.startswith("wiki/") and not in_repo.startswith(".."):
            return REPO_BLOB + in_repo + anchor
    return target


def _relative(dest: PurePosixPath, page_dir: PurePosixPath) -> str:
    up = [".."] * len(page_dir.parts)
    return "/".join(up + [str(dest)]) if up else str(dest)


def on_page_markdown(markdown, page, config, files):
    uri = page.file.src_uri
    markdown = _LINK.sub(lambda m: m.group(1) + _rewrite(m.group(2), uri) + m.group(3), markdown)
    return _IMG_SRC.sub(lambda m: m.group(1) + _rewrite(m.group(2), uri) + m.group(3), markdown)
