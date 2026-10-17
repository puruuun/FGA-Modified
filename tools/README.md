# tools/

Helper scripts for development. The device check is authoritative; the Python validators are development aids.

## Device reader QA (authoritative)

```bash
py -3 tools/device-qa/run.py --build --suite support,bond,questItems
```

- Builds the isolated `deviceTest` APKs (`-PfgaDeviceTests=true`), installs them, runs the instrumentation on the device, and writes `build/device-qa/<timestamp>/summary-<suite>.md`, a combined `summary.md`, and `report-<suite>.json`.
- Suites: `support` (screens and crops of score, NP and skill), `bond` (result and level-up frames), `questItems` (Teapot and Storm Pod buttons and dialogs). Each case reads `docs/reference-images/cases/<suite>.json`.
- Run one suite with `--suite bond` or one case file with `--cases <repo-relative path>`.
- Needs Python 3 (use `py -3`; `python` may resolve to the Windows Store stub), the Android SDK platform-tools, and BlueStacks with ADB enabled.
- `--serial` defaults to `127.0.0.1:5555`. Pass `--serial` if your instance uses another port.
- The script uses one adb binary for the whole run and prints its path. Do not mix the SDK adb and `HD-Adb.exe` in one session: they kill each other's server.

## Python validators (development aids)

The following scripts read Python-side labels and run on the PC with OpenCV/Tesseract. The labels they use now live in `docs/reference-images/cases/` and are checked on the device.

- `verify_support_score_images.py`, `verify_support_grand_score_images.py`, `verify_support_np_images.py`, `validate_support_np.py`, `validate_support_scores.py`, `verify_support_append_images.py`
- `verify_bond_images.py`, `verify_quest_item_images.py`, `verify_imported_ce_images.py`
- `bond-image-cases.json`: labels for `verify_bond_images.py`; migrated to `docs/reference-images/cases/bond.json`
