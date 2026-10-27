"""Run the on-device reader fixtures on a BlueStacks (or other) instance.

Development aid. The authoritative check is `tools/device-qa/run.py`.

Usage:
    py -3 tools/device-qa/run.py [--build] [--serial 127.0.0.1:5555] [--suite support] [--cases FILE]

Python only moves files and calls adb/gradle. Reader logic stays in the app's androidTest code.
"""

import argparse
import datetime
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DEVICE_ROOT = "/sdcard/Download/fga-device-qa"
RUNNER_CLASS = "io.github.fate_grand_automata.qa.SupportDeviceTestRunner"
SAFE_DEVICE_PATH = re.compile(r"^[A-Za-z0-9._/-]+$")
MAX_REPORT_BYTES = 256 * 1024
DEFAULT_SERIAL = "127.0.0.1:5555"
# Legacy device-side case file used until docs/reference-images/cases/<suite>.json exists (0002 stage 2).
LEGACY_DEVICE_CASES = {"support": f"{DEVICE_ROOT}/cases-final.json"}
KNOWN_SUITES = {"support", "bond", "questItems", "noblePhantasm", "fpSell", "skillLevel", "weeklyRoute", "rankUp", "skillLevelPanel", "ceEnhance"}
APK_GLOBS = {
    "app": "app/build/outputs/apk/deviceTest/*.apk",
    "test": "app/build/outputs/apk/androidTest/deviceTest/*.apk",
}


def find_adb() -> str:
    """Use exactly one adb for the whole run. Mixing versions makes them kill each other's server."""
    candidates = []
    sdk = os.environ.get("ANDROID_HOME") or os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
    candidates.append(os.path.join(sdk, "platform-tools", "adb.exe"))
    found = shutil.which("adb")
    if found:
        candidates.append(found)
    candidates.append(r"C:\Program Files\BlueStacks_nxt\HD-Adb.exe")
    for path in candidates:
        if os.path.isfile(path):
            return path
    sys.exit("adb not found. Install Android platform-tools or set ANDROID_HOME.")


def run(cmd, **kwargs) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", **kwargs)


def adb(adb_path: str, serial: str, *args: str) -> str:
    result = run([adb_path, "-s", serial, *args])
    if result.returncode != 0:
        sys.exit(f"adb {' '.join(args)} failed: {result.stdout}{result.stderr}")
    return result.stdout


def gradle_build() -> None:
    gradlew = REPO / ("gradlew.bat" if os.name == "nt" else "gradlew")
    cmd = [str(gradlew), "-PfgaDeviceTests=true", ":app:assembleDeviceTest", ":app:assembleDeviceTestAndroidTest", "--console=plain"]
    if subprocess.run(cmd, cwd=REPO).returncode != 0:
        sys.exit("gradle build failed")


def find_apk(kind: str) -> Path:
    matches = sorted(REPO.glob(APK_GLOBS[kind]))
    if len(matches) != 1:
        sys.exit(f"expected one {kind} APK, found {len(matches)}: run with --build first")
    return matches[0]


def test_package(apk: Path) -> str:
    """The instrumentation package comes from the built APK, never from a hardcoded string."""
    build_tools = Path(os.environ.get("ANDROID_HOME", os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk"))) / "build-tools"
    aapt = sorted(build_tools.glob("*/aapt.exe"))
    if not aapt:
        sys.exit("aapt not found under the Android SDK build-tools")
    badging = run([str(aapt[-1]), "dump", "badging", str(apk)]).stdout
    match = re.search(r"package: name='([^']+)'", badging)
    if not match:
        sys.exit(f"could not read package name from {apk.name}")
    return match.group(1)


def load_cases(path: Path) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("schema", 1) != 1 and "schema" in data:
        sys.exit(f"unsupported case schema {data['schema']}")
    return data


def device_path_for(repo_relative: str) -> str:
    if not SAFE_DEVICE_PATH.match(repo_relative) or ".." in repo_relative:
        sys.exit(f"fixture path not allowed on device: {repo_relative}")
    return f"{DEVICE_ROOT}/{repo_relative}"


def stage_repo_cases(adb_path: str, serial: str, case_file: Path) -> tuple[str, list[str]]:
    """Push a repo-side case file and the images it references; return the rewritten device path and skipped case names.

    Cases marked "skip" in the case file are left out of the run and reported as skipped.
    """
    data = load_cases(case_file)
    skipped = [c["name"] for c in data.get("cases", []) if "skip" in c]
    staged = json.loads(json.dumps(data))
    staged["cases"] = [c for c in staged.get("cases", []) if "skip" not in c]
    pushed = set()

    def push(repo_relative: str) -> str:
        target = device_path_for(repo_relative)
        if repo_relative not in pushed:
            adb(adb_path, serial, "shell", "mkdir", "-p", f"{DEVICE_ROOT}/{os.path.dirname(repo_relative)}")
            adb(adb_path, serial, "push", str(REPO / repo_relative), target)
            pushed.add(repo_relative)
        return target

    for case in staged.get("cases", []):
        if "file" in case and not case["file"].startswith("/"):
            case["file"] = push(case["file"])
        for piece in case.get("pieces", []):
            piece["file"] = push(piece["file"])
    for key, files in staged.get("imports", {}).items():
        staged["imports"][key] = [push(f) if not f.startswith("/") else f for f in files]
    local = Path(f"build/device-qa-staged-{case_file.stem}.json")
    local = REPO / local
    local.parent.mkdir(parents=True, exist_ok=True)
    local.write_text(json.dumps(staged, ensure_ascii=False, indent=2), encoding="utf-8")
    device_case = f"{DEVICE_ROOT}/run/{case_file.stem}.json"
    adb(adb_path, serial, "shell", "mkdir", "-p", f"{DEVICE_ROOT}/run")
    adb(adb_path, serial, "push", str(local), device_case)
    return device_case, skipped


def instrument(adb_path: str, serial: str, package: str, device_case: str) -> dict:
    cmd = ["shell", "am", "instrument", "-w", "-r", "-e", "cases", device_case, f"{package}/{RUNNER_CLASS}"]
    output = adb(adb_path, serial, *cmd)
    line = next((l for l in output.splitlines() if l.startswith("INSTRUMENTATION_RESULT: report=")), None)
    if line is None:
        sys.exit(f"no report from instrumentation:\n{output[-2000:]}")
    raw = line[len("INSTRUMENTATION_RESULT: report="):]
    if len(raw.encode("utf-8")) > MAX_REPORT_BYTES:
        sys.exit("instrumentation report exceeds 256KB")
    return json.loads(raw)


def write_summary(report: dict, out_dir: Path, suite: str, serial: str, adb_version: str, skipped: list[str]) -> bool:
    results = report.get("results", [])
    failed = [r for r in results if not r.get("passed")]
    lines = [
        f"# 기기 판독 검증 결과 ({suite})",
        "",
        f"- 기기: {serial}",
        f"- adb: {adb_version}",
        f"- 케이스: {len(results)}개, 통과 {len(results) - len(failed)}개, 실패 {len(failed)}개, skip {len(skipped)}개",
        f"- 전체 결과: {'통과' if report.get('passed') and not failed else '실패'}",
        "",
    ]
    if report.get("error"):
        lines += ["## 실행 오류", "", "```", report["error"], "```", ""]
    if failed:
        lines += ["## 실패 케이스", ""]
        for r in failed:
            lines += [f"### {r['name']}", "", "```", (r.get("error") or "")[:2000], "```", ""]
    if skipped:
        lines += ["## Skip 케이스 (원본 범위 불일치 등)", ""] + [f"- {name}" for name in skipped] + [""]
    ce_rows = [
        (result["name"], row["y"], check)
        for result in results if result.get("actual", {}).get("view") == "CESelection"
        for row in result["actual"]["rows"] for check in row["checks"]
    ]
    if ce_rows:
        lines += ["## Grand CE 인연 효과 점수", "", "| 케이스 / 행 y720 | 설정 | NP | Default | 선택 |",
                  "|---|---|---:|---:|---|"]
        for name, y, check in ce_rows:
            np_score = (check.get("bondNP") or {}).get("score")
            default_score = (check.get("bondDefault") or {}).get("score")
            lines.append(f"| {name} / {y} | {check['effect']} | {np_score} | {default_score} | {check['matched']} |")
        lines.append("")
    top = sorted(results, key=lambda r: r.get("ocrMs", 0), reverse=True)[:10]
    lines += ["## OCR 비용 상위 10개", "", "| 케이스 | OCR 호출 | OCR ms | 전체 ms |", "|---|---|---|---|"]
    for r in top:
        lines.append(f"| {r['name']} | {r.get('ocrCalls', '-')} | {r.get('ocrMs', '-')} | {r.get('elapsedMs', '-')} |")
    (out_dir / f"summary-{suite}.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    return not report.get("error") and not failed and bool(report.get("passed"))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", default=DEFAULT_SERIAL)
    parser.add_argument("--build", action="store_true", help="build the deviceTest APKs first")
    parser.add_argument("--suite", default="support,bond,questItems,noblePhantasm,fpSell,skillLevel,weeklyRoute,rankUp", help="comma-separated suites (default: all)")
    parser.add_argument("--cases", help="repo-relative case JSON to use instead of the suite default")
    args = parser.parse_args()

    if args.build:
        gradle_build()
    adb_path = find_adb()
    adb_version = adb(adb_path, args.serial, "version").splitlines()[0]
    print(f"adb: {adb_path} ({adb_version})")
    app_apk, test_apk = find_apk("app"), find_apk("test")
    package = test_package(test_apk)
    out_dir = REPO / "build" / "device-qa" / datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    out_dir.mkdir(parents=True, exist_ok=True)

    for apk in (app_apk, test_apk):
        # File-backed install avoids a stalled streamed-install pipe on BlueStacks TCP transports.
        adb(adb_path, args.serial, "install", "--no-streaming", "-r", "-t", str(apk))

    suites = [s.strip() for s in args.suite.split(",") if s.strip()]
    overall = True
    for suite in suites:
        if suite not in KNOWN_SUITES:
            sys.exit(f"unknown suite: {suite}")
        case_file = REPO / (args.cases or f"docs/reference-images/cases/{suite}.json")
        if case_file.exists():
            device_case, skipped = stage_repo_cases(adb_path, args.serial, case_file)
        elif suite in LEGACY_DEVICE_CASES:
            print(f"warning: {case_file.relative_to(REPO)} not found; using legacy device file (0002 stage 1 only)")
            device_case, skipped = LEGACY_DEVICE_CASES[suite], []
        else:
            sys.exit(f"no case file for suite {suite}: {case_file.relative_to(REPO)}")
        report = instrument(adb_path, args.serial, package, device_case)
        (out_dir / f"report-{suite}.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        ok = write_summary(report, out_dir, suite, args.serial, adb_version, skipped)
        print(f"{suite}: {'PASS' if ok else 'FAIL'} -> {out_dir / f'summary-{suite}.md'}")
        overall = overall and ok
    # summary.md joins every suite summary so one file shows the whole run.
    (out_dir / "summary.md").write_text("".join((out_dir / f"summary-{s}.md").read_text(encoding="utf-8") for s in suites), encoding="utf-8")
    return 0 if overall else 1


if __name__ == "__main__":
    sys.exit(main())
