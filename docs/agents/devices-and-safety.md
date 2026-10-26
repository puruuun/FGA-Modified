# Devices and game safety

Read this before any `adb` command, any run of `tools/device-qa/run.py`, any APK install, or any work
that looks at a live game screen. These rules come from the owner; breaking one has cost real
in-game resources or a working emulator before.

## Devices

- **BlueStacks** on the work PC runs FGO (KR and JP), logged in, with FGA installed. Its serial is
  `127.0.0.1:5555`. If `adb devices` does not list it, the only allowed fix is
  `adb connect 127.0.0.1:5555`.
- **`emulator-5554`** is not ours to use: never install to it, read from it or send it commands.
- **The owner's tablet** (Lenovo Legion Y700, 3040×1904, 16:10) is used only when the owner has
  connected it and asks for it. Then installs, logs and read-only screencaps are allowed.

## adb

Several agents share one adb server. Never run `adb kill-server`, `start-server`, `disconnect`,
`usb`, `tcpip` or `reconnect`. Always pass `-s <serial>`.

## The game

- Agents never operate the game. Taps, swipes and script runs on a live game are the owner's.
  Read-only screencaps and logcat are fine. The owner may have the game open while you work.
- The one exception, and only when a task explicitly asks for a live run: the test quest
  Main Record → Fuyuki → 미확인 좌표 X-A (AP 3), on naturally recovered AP.
- Never use apples or Saint Quartz, never spend servant coins, and never unlock a locked append
  skill, not even in a test.
- Device QA (`tools/device-qa/run.py`) replays recorded frames through the product code and does not
  touch the game. Its scope per beta is set in `AGENTS.md`.

## Screen shapes

Live failures have come from shapes the fixtures did not cover: 16:10 on the tablet, 21:9 and other
wide shapes on BlueStacks. When you reason about a reader or template bug, check 16:9, wide and
16:10 frames, and KR and JP. See [`coordinates.md`](coordinates.md) for the wide model.
