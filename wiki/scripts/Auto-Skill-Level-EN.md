# Auto Skill / Append Skill Upgrade

**On the Skill Enhancement / Append Skill Enhancement screen with a Servant already selected, this script repeatedly upgrades each slot up to its configured target level. Enhancing consumes QP and materials irreversibly. For your first test, use one slot with target = current level + 1.**

Supports the KR and JP servers only.

## How to Start

1. In the game, open **Enhance → Skill Enhancement** (or **Append Skill Enhancement**) and **select a Servant first**.
2. Tap the FGA Play button. The script inspects the instruction text above the material list on the middle-left of the screen (`스킬 강화에 필요한 아이템` / `어펜드 스킬 강화/해방 시 필요 아이템`, or `スキル強化に必要なアイテム` / `アペンドスキルの強化/解放に必要なアイテム` on JP) to automatically detect whether you are on Skill Enhancement or Append Skill Enhancement. The top-right title banner is no longer used.
3. In the setup dialog, set the **target level** for each slot and tap **OK**.

If no Servant is selected (the top-left lacks the `서번트 재선택` / `サーヴァント再選択` button), no setup dialog is shown; it only displays `"No servant is selected. Select a servant on the skill enhancement screen first."` and exits.

## Settings

| Item | Default | Behavior |
|---|---|---|
| Skill Enhancement slots 1–3 | **Off (0)** | Target level 1–10. If the current level is already at or above the target, the slot is left untouched. |
| Append Skill Enhancement slots 1–5 | **Off (0)** | Same as above. Locked slots are skipped even if a target is set. |

If all slots are off, the **OK** button is disabled. Every time the setup dialog opens, **all slots start at Off (0)** and chosen targets are not saved. You must set your targets each time you run the script.

## Behavior and Safeguards

### Beta Only: Check Level Reading Only (1.6.8-beta.2)

To compare panel and dialog level readings without spending materials, select a Servant on the Skill/Append Enhancement screen, tap the FGA Play button, and check **Check level reading only (no enhancement)**. Target levels are ignored and every slot is checked once from left to right. This option always defaults to unchecked and is not saved. It is not present in Stable builds.

Locked slots are never unlocked. Any slot showing a lock icon is not tapped, and if after selecting a slot the panel read detects an Unlock button or lock evidence on the panel, nothing further is tapped. For remaining slots, it taps the Enhance button, reads the confirmation dialog, and **always cancels**. Even when the panel reads 10, it still attempts the dialog comparison. Any unexpected dialog is also canceled, and if canceling fails, the entire run aborts. Confirmation of enhancement is blocked in code. If no confirmation dialog appears, it logs `max` or `no-response` depending on whether the Max Level button is visible. Insufficient materials are not distinguished separately.

The exit dialog and `SkillLevel` log record one line per slot as shown below. `slot` is 1-based, and Append slots also show `score`/`margin`.

```text
slot=1 panel=3 dialog=unread verdict=panel-only reason=cancelled score=0.9 margin=0.2
```

| verdict | Meaning |
|---|---|
| `match` / `mismatch` | Both readings match / differ |
| `panel-only` / `dialog-only` | Only panel / only dialog was read |
| `both-unread` | Neither could be read |
| `no-dialog` | No dialog after tapping Enhance (`reason=max` or `no-response`) |
| `locked` | Locked |

`panel=abstain:<reason>` means panel reading abstained, `dialog=unread` means no valid level was read from the open dialog, and `dialog=-` means the dialog did not open or was not opened. `reason=unexpected-dialog` means the dialog was canceled because it was not an enhancement confirmation dialog, and `cancel-failed` means the run stopped because canceling failed.

**If you get `mismatch` or `both-unread`**, please send: app version, KR/JP server, Skill/Append mode, slot number, actual level, screen resolution, raw screenshots of the enhancement screen showing the selected slot and (if possible) the confirmation dialog, plus the full `SkillLevel` log and exit summary from that run. Do not tap Confirm enhancement to reproduce it. Live in-game verification of check mode is performed by the owner.

From 1.6.8-beta.3, panel digit reading for yellow/gold Append icons has been improved. If the two color readings disagree or score/margin is insufficient, it still abstains.

From 1.6.8-beta.4, Append slots are read first using each slot's own sample. It abstains if the reading differs from a confirmed reading obtained from another slot's sample, and self-reading also requires score 0.70, margin 0.10, and agreement between the two color candidates. When self-reading abstains, the existing beta.3 reader serves as fallback, which must also pass the same two-color agreement, score, and margin criteria. Please verify KR and JP 16:9 and wide screens—especially slots 2 and 4—using **Check level reading only (no enhancement)** mode. It may still abstain on some saved screens or other unseen layouts.

From 1.6.8-beta.5, text and color of the banner above the slots are not checked. Slot 1 also attempts dialog comparison in the same order as the other slots.

From 1.6.8-beta.6, if only one side of the confirmation dialog (current or next) is reliably read, the other side is inferred as **next = current + 1**. Inferred current levels appear in the exit dialog and logs as `dialog=9(inferred)`, and inferred next levels as `dialog=8(next-inferred)`. If both sides are read and are not consecutive, inference never overwrites them. A read or inferred current level of 0 cancels to prevent unlocking, and normal enhancement's panel mismatch and level-increase checks still apply.

### Normal Enhancement

For each slot, the script repeats the following:

1. If a lock icon is visible, skip the slot. Otherwise, tap the slot icon to select it and wait for the screen to settle.
2. Read the selected slot's panel level and check for the Unlock button and panel lock evidence. If locked, skip the slot.
3. If the panel reads 10 reliably, stop that slot with `"max level"`; if at or above the target, `"target reached"`; if not exactly 1 higher than the level before the previous enhancement, `"level did not go up, stopped"`. In these cases, the Enhance button is not tapped. Ambiguous panel values are not used.
4. Check right before tapping that the button is not an Unlock button, then tap the bottom-right button **position**. After **waiting 0.4 s**, wait up to 2 s for the enhancement confirmation dialog. If none appears, finish the slot with `"max level"` if the max-level button is visible, or `"cannot enhance"` otherwise—except if the previous confirmation already reached the target, in which case `"target reached"` is kept. Insufficient materials, QP, or coins are not distinguished separately. In a dialog verified to have an enhancement title, read `Lv.<current> › Lv.<next>` and compare with the panel; if they differ, cancel. If dialog current 1–9 and next = current + 1 are confirmed, use those values. If only one side is confirmed, infer the other by ±1; if neither side can be read but the panel is reliable, use panel current and current + 1. If the panel is ambiguous, rely on the dialog alone. Confirm is tapped **only when** the chosen current level is **less than the target** and, after the first enhancement, **is exactly 1 higher than before the previous enhancement**.
5. No extra delay is added after tapping Confirm. When **Cancel is tapped** upon reaching the target, **wait 0.4 s** before moving to the next slot. After Confirm, tap the top-right title area every 0.5 s to skip the animation and result screen, waiting up to 20 s for the skill enhancement screen to return; if exceeded, stop. If the connection error dialog's retry button (KR `다시 시도`, JP `やり直す`) appears during this wait, tap it up to 3 times and restart the 20 s count; if it appears a 4th time, stop without tapping it. In Skill mode, if during this wait the instruction text is visible while the `서번트 재선택` / `サーヴァント再選択` button is absent on two consecutive checks (0.5 s apart), it stops right there with the deselected reason below—because the game deselects the Servant once all three skills reach level 10. Append mode is unchanged.

For temporary welfare Servants joining for a limited time, an extra notice dialog appears before the enhancement confirmation dialog. When that dialog's right button (`실행` / `実行する`) is visible, it taps it to proceed (whenever visible, same as upstream FGA's Servant enhancement), and then applies the same level checks above to the subsequent enhancement confirmation dialog. If another dialog appears without the confirm/execute button, it cancels without confirming. Verified on JP and KR servers; recognition reuses the same `실행` / `実行する` button image as limited-time Servant level-up.

Safeguards:

- **The Unlock (`해방` / `解放する`) button is never tapped under any circumstances.** Append slots are checked via the lock icon, panel lock evidence, and button text. Button text is re-checked right before tapping. If `0` is read in the confirmation dialog, it cancels even if a panel value exists.
- **It never exceeds the target level.** Confirm is tapped only when the current level is strictly below the target.
- If neither panel nor dialog can be read, if the two confirmed readings disagree, or if the chosen current level is not exactly 1 higher than before the previous enhancement, it does not confirm and stops that slot. Any open confirmation dialog is canceled.
- If an unknown confirmation dialog (not titled as enhancement) opens, it cancels.
- Saint Quartz, apples, and shops are never used. It will not farm or acquire materials or QP for you.

## Exit Messages

At exit, each slot displays `Slot N: Lv.<start> → Lv.<end> (target <target>) <step>: <reason>`.

| Reason | Meaning |
|---|---|
| `target reached` | Raised to the target level, or already at or above the target. |
| `max level` | Already Lv. 10 and cannot be raised further. |
| `cannot enhance` | No confirmation dialog appeared. Materials, QP, and coins are not distinguished separately; check the game screen. |
| `locked, skipped` | Locked Append slot. It was not unlocked. |
| `level not read, nothing was enhanced` | Could not obtain a usable level from the panel or dialog, or read `0` in the dialog. |
| `panel and dialog levels differ, canceled` | Canceled because the two readings differed. Do not auto-retry; check the actual level in-game and send the diagnostics listed below. |
| `level did not go up, stopped` | Confirm was tapped, but the level did not increase. Check the game screen. |
| `unexpected dialog, canceled` | Canceled because it was not an enhancement confirmation dialog. |
| `the game did not return to the enhancement screen` | Did not return within 20 seconds after the animation. Check the game: an enhancement may already have happened. |
| `connection errors kept happening, stopped` | Stopped after the connection error dialog's retry button appeared 4 times following Confirm. Check the game screen: an enhancement may have happened. |
| `all skills reached level 10 and the game deselected the servant.` | All three skills reached level 10 on the last enhancement and the game deselected the Servant. Enhancement is complete. |
| `off` | Slot target was left at 0 (Off). |

The overall message begins with one of: `Finished.`, `No servant is selected…`, `The skill / append skill enhancement screen is not showing. Stopped.`, `Every slot is off. Nothing to do.`, or `Stopped by the user.`.

## Diagnostic Logs

The log tag is `SkillLevel`. Even in beta, it writes INFO logs, recording slot selection, panel reading, Enhance tap, dialog presence, current/next levels, confirm/cancel, and skip/return step by step. Stop messages and per-slot summaries also show the step: **Select / Enhance / Dialog / OCR / Confirm / cancel / Skip / return**.

When screen detection fails and stops with `WrongScreen` / `NoServantSelected`, or when auto-detection runs on KR/JP, it logs one line with the instruction label scores. Please include this line when reporting (`<0.30` means score is below 0.30).

```text
scores label-append=1.000 label-skill=0.680 reselect=1.000 detected=Append
```

```text
adb -s 127.0.0.1:5555 logcat -s SkillLevel
slot=1 step=ocr current=3 next=4
slot=1 step=ocr panel=3 dialog=3 source=both score=0.9 margin=0.2
slot=1 step=confirm action=confirm
slot=1 step=skip return=true
stop slot=1 step=ocr reason=Reached
summary slot=1 step=ocr target=4 start=3 end=4 reason=Reached
```

Even on errors or manual stops, the summary for the active slot is preserved. `slot` is 1-based; `slot=0` in pre-start checks means no slot had started yet. If an error occurs, collect logs using the command above and verify the actual enhancement result in-game.

Each round logs `panel=<level|abstain:<reason>|locked> dialog=<level|unread> source=<dialog|panel|both>`, and Append slots also log panel `score` and `margin`. `abstain` means panel reading was not used. For rounds that stopped before opening the dialog, `dialog=unread source=panel` means the dialog was not read. Rounds where both failed to read still log `source=panel`, without enhancing.

**If a mismatch occurs**, send the app version, KR/JP server, Skill/Append mode, slot number, target and actual levels, screen resolution, raw screenshots of the enhancement screen showing the selected slot and (if possible) the confirmation dialog, plus the full `SkillLevel` log and exit summary from that run. You do not need to press Confirm enhancement to reproduce the dialog.

## Known Limitations

- Verified by device QA across 16 confirmation dialogs (KR/JP) covering current levels 1–9 and next levels 2–10, including two-digit `10`. For digit `9`, when OCR fails, shape matching is used as a fallback and rejected if ambiguous. From 1.6.8-beta.1, failed dialog reads fall back to a reliable panel level (1–9); the dialog `0` check and enhancement-title check remain intact.
- In beta.2 JP, the owner confirmed one slot upgraded by target = current + 1, though intermediate errors also occurred whose cause is not yet finalized. In beta.3, the owner confirmed normal operation on both JP and KR Skill screens. Append enhancement has not yet been run live in-game. 1.6.1-beta.2 separately checked positions of the five slots, four locks, dialog, and results on a 2560×1080 KR Append capture; locked slots are skipped and never unlocked. Device QA for this version and wide-screen live in-game runs are pending.

### 1.6.2-beta.4

Widened the digit region where the top of next level `10` was clipped in JP tablet Append Skill confirmation dialogs. `9→10` can now be confirmed, and it still stops as before if current and next levels are not confirmed.

### 1.6.2-beta.9 — Tablet Append 2→3

Narrowed the current-digit region to the digit column on KR tablet confirmation dialogs, reducing misreads of `2` as `4` caused by the `Lv.` prefix and background slot art. Confirmed reading of current/next levels and the **next = current + 1** rule remain unchanged, canceling if ambiguous.

### 1.6.2 — KR/JP Append Digit Reading

Added Append-specific binarization conditions where backgrounds merged into digits such as KR `7→8`. The current-digit box is shifted slightly left without increasing width to give contour margin. Checked on KR/JP wide screens from `1→2` through `9→10` and a saved BlueStacks `7→8` screen; the wide-screen samples were downscaled by upload. Normal Skill mode keeps existing thresholds. Multiple agreeing reads and the **next = current + 1** condition remain required, canceling whenever uncertain.

### 1.6.8-beta.1 — Panel and Dialog Cross-Check

When a semi-transparent KR confirmation dialog cannot be read, the selected slot's panel level is used as a fallback. When both readings are confirmed, they must match; otherwise it cancels without enhancing. Panel acceptance criteria were not relaxed. In particular, 16:9 Append slot 4 may still abstain, and real 16:10 captures have not yet been verified. Live in-game KR Skill/Append fallback behavior and JP regression checks still require owner verification.
