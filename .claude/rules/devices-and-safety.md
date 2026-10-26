---
paths: "tools/device-qa/**,app/src/androidTest/**,build/orchestration/**"
description: Devices, adb limits and game-safety rules (never operate the game, never unlock appends, never spend apples or quartz).
---

BlueStacks is `127.0.0.1:5555`; never touch `emulator-5554`; the tablet only when the owner asks. No adb
server commands; agents never operate the game.

Read [`docs/agents/devices-and-safety.md`](../../docs/agents/devices-and-safety.md).
