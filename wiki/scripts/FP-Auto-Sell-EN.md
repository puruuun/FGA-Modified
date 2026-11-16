# FP Summon Auto-Sell

**When the Servant inventory is full, this feature selects and sells all unlocked cards on the Sell screen, then returns to the FP banner to continue summoning. Always check the in-game Sell screen filters before starting.**

What gets sold is determined by the in-game Sell screen filters. FGA does not read or change your filters or sort order, and does not restrict targets by card type, rarity, or `NEW!` status. Every visible card matching the filter without a lock icon is a target. **Exclude party, Grand, and temporary Servants using the in-game filters before starting.** FGA does not check those labels. Works on the KR and JP servers only.

Start Friend Point summoning **from the banner screen**. Starting from the summon results screen will not auto-detect Friend Point summoning.

## Settings

| Item | Default | Behavior |
|---|---|---|
| Enable auto-sell | Off | Moves to the Sell screen and sells cards when Servant inventory is full. |
| Dry-run (stop after Confirm) (beta only) | On | Stops immediately at the final confirmation dialog after tapping Confirm. Does not convert or resume summoning. |

Stable builds do not include dry-run mode; turning on **Enable auto-sell** sells cards for real. Beta builds also sell cards for real when dry-run mode is turned off.

## First-Time Testing (Beta)

1. Verify your Sell screen filters in-game so only the cards you want to sell are visible.
2. In the FP launcher, turn on **Enable auto-sell** and **Dry-run (stop after Confirm)**.
3. Start Friend Point summoning and check which cards get selected on the Sell screen after the inventory fills up.
4. Confirm that the script stops immediately at the final confirmation dialog after tapping **Confirm**. Check the listed cards yourself and cancel in-game.
5. Turn off dry-run mode only after confirming the selection is correct. Deselect everything in-game, double-check your filters, and start again.

## Behavior and Safeguards

- Only sells on a Servant inventory full warning. It will not sell on Craft Essence inventory warnings or other full-box alerts.
- After tapping the Sell button, it waits until card frames are readable regardless of column count. It checks the Servant tab once upon entering, switches to 7 columns, and scrolls while selecting. It does not check the screen title, and stops if it loses the grid during selection.
- After finishing selection on a page, if the bottom-end scrollbar reference image from upstream FGA is detected, it ends list scanning without extra swipes. It does not stop scanning merely because the scroll rail is absent, nor simply because rows stay unchanged; rejected-tap inference, the 5-page limit without new taps, and the 500-page total limit are retained.
- A cell is tapped only if all of these hold: a card is present / it has no lock / it is not already selected. Labels such as `Locked` (`잠금 중`), party membership, Grand status, and temporary Servant status are not inspected.
- It never unlocks cards, and during real selling it skips any cell whose lock state is ambiguous.
- Real selling taps fixed positions in sequence: **Confirm → 1 s → Convert → 1 s → close completion dialog → 4 s**. It does not inspect the final dialog. Dry-run mode stops with `SellDryRunDone` right after tapping Confirm.
- If 0 cards are selected, it immediately stops with nothing to sell without pressing Confirm, converting, or returning to the banner.
- Returning via **Sell screen → Workshop → MENU → Summon** always opens the Summon screen on the first banner. The script presses the left arrow the configured **FP banner position (from the end)** number of times (pressing left on the first banner wraps around to the last banner) to reach the FP banner; banner titles are not compared. Choose 1–7 on the **FP banner position (from the end)** stepper: 1 (default) presses the left arrow once to reach the last banner, 2 presses it twice for the second-to-last banner, and so on. Saved values outside this range are clamped to 1–7. If the Friend Point summon label and summon button are not visible after moving, it stops without summoning and prompts you to check the setting.
- After the completion dialog, it taps the top-left close button on the Sell screen once, waits 4 seconds, and goes to MENU. It does not check the Workshop title or retry closing, while MENU verification is kept.
- If a button or screen cannot be found, it stops and specifies where it stopped.

## Diagnostic Logs

The log tag is `FpSell`. It logs one line per cell showing presence, lock state, selection state, and decision, and logs `final-summary` when exiting.

```text
page=2 r3c4 present=true lock=Unlocked selected=false decision=select
```

`?` means unreadable/unknown. Cards whose selection is rejected by the game are logged as `tap-rejected` and excluded from the selected count.

### 1.7.0-beta.5 — End of Sell List

When the bottom-end scrollbar reference image from upstream FGA is confirmed in a small region, the script finishes selecting the current page and ends scanning with `scrollbar-end`. It does not stop merely because the rail is absent, and retains the 5-page no-new-tap limit, 500-page safety cap, and rejected-tap inference.
