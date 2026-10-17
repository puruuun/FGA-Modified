# tools/

Helper scripts for development. Scripts that check reader output are listed below with their role.

## Device reader QA (authoritative)

```bash
py -3 tools/device-qa/run.py --build --suite support
```

- Builds the isolated `deviceTest` APKs (`-PfgaDeviceTests=true`), installs them, runs the instrumentation on the device, and writes `build/device-qa/<timestamp>/summary.md` and `report-<suite>.json`.
- Needs Python 3 (use `py -3`; `python` may resolve to the Windows Store stub), the Android SDK platform-tools, and BlueStacks with ADB enabled.
- The `--serial` default is `127.0.0.1:5555`. Pass `--serial` if your instance uses another port.
- The script uses one adb binary for the whole run and prints its path. Do not mix the SDK adb and `HD-Adb.exe` in one session: they kill each other's server.

## Python validators (development aids)

The following scripts read Python-side labels and run on the PC with OpenCV/Tesseract. They are development aids. The authoritative check is `tools/device-qa/run.py`.

- `verify_support_score_images.py`, `verify_support_grand_score_images.py`, `verify_support_np_images.py`, `validate_support_np.py`, `verify_support_append_images.py`
- `verify_bond_images.py`, `verify_quest_item_images.py`, `verify_imported_ce_images.py`
- `bond-image-cases.json`: labels for `verify_bond_images.py`
