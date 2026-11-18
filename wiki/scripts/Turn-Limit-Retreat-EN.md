# Retreat and Restart

When the party is wiped out or the battle reaches a set turn, FGA retreats from the quest and restarts it from the beginning. This is toggled per battle config, and one switch handles both behaviors.

| Setting | Party wipe | Turn N reached |
|---|---|---|
| Off (default) | Stops (`"All servants have been defeated and auto-withdrawing is disabled."`) | Does nothing |
| On, N = 0 | Retreats and restarts | - |
| On, N ≥ 1 | Retreats and restarts | Retreats and restarts (KR and JP servers only) |

For example, if you set `N` to `6` and the party is wiped out on Turn 4, it is handled as a wipe retreat and the quest restarts. As soon as Turn 6 begins, FGA retreats without using skills or cards.

## Servers

- Wipe retreat works on all servers.
- Turn-limit retreat (`N ≥ 1`) works on the KR and JP servers only. You can still set `N` in the dialog on any server, but it is only applied on KR and JP. On other servers, even if `N` is 1 or higher, it is ignored and the battle continues (same behavior as `N = 0`). Wipe retreat follows the switch regardless of server.

## How to configure

Open **Retreat and Restart** on the config screen under **Battle Configs → <config>**. Settings are saved separately per battle config.

| Item | Description |
|---|---|
| On / Off | Enables or disables **Retreat and Restart** for this battle config. |
| Turn N | The turn `N` to retreat at (`0–99`, default `0`). At `0`, FGA only retreats when the party is wiped out. |
| Stop after N retreats in a row | When enabled, stops the script after `N` consecutive retreats, counting both wipe retreats and turn-limit retreats. Use the stepper next to the checkbox to pick `N` from `1–99` (default `3`). Adjustable only when both the checkbox and the **Retreat and Restart** switch are on. Existing configs with the checkbox enabled keep working with `N = 3`. |

The config row summary displays `Off` when off, `Retreat on wipe` when `N = 0`, and `Turn N` when `N ≥ 1`. All four settings are included when exporting and importing battle configs. (If the older **More options → Battle → Enable Withdraw** switch was enabled, the first launch after updating migrates any configs with **Retreat and Restart** off to **On, N = 0** once.)

## Behavior on party wipe

The existing tap sequence on the wipe screen (Withdraw → Confirm → Close) is unchanged. What changed is how it is accounted for:

- Like turn-limit retreat, a wipe retreat **is not counted as a run**. It is excluded from run count, run limit, turn count, and average time stats, resets the run state, and restarts the quest.
- It increments the retreat count and consecutive retreat streak, and checks **Stop after N retreats in a row** if enabled.
- Because a wipe is not a normal clear, it does not reset the consecutive retreat streak to `0`; only a normally cleared run resets it.

## Behavior when Turn N is reached

1. At the start of each turn, before checking skills, cards, or Command start conditions, FGA checks whether the current turn has reached `N`. Turns are counted continuously across waves matching the in-game `TURN` counter (without reading the screen).
2. Turns spent waiting under **Command start** (**Command card count mode**) are not counted. If you set `N` to `4` and wait one turn, FGA retreats as soon as the in-game `TURN` becomes `5`.
3. Retreat proceeds in the following steps:
   1. Taps the battle menu button to open the menu, waits 0.5 seconds, and waits up to 3 seconds for the retreat button (`"후퇴하기"` in KR, `"撤退する"` in JP). If it does not appear, FGA taps the menu button once more, and stops if it still does not appear.
   2. Taps the retreat button, waits 0.5 seconds, and checks up to 3 seconds for the confirmation dialog (`확인` / `決定`). If it does not open, FGA does not tap confirm; instead, it taps the menu's `X` button to close the menu and stops, because tapping the confirm spot while only the menu is open would toggle the game's skill-confirmation setting.
   3. Taps confirm, waits 1 second, and waits up to 15 seconds for the Close button on the `"Withdrew from the quest"` (`"퀘스트에서 철수했습니다"`) dialog.
   4. Taps Close, waits 6 seconds until the quest list appears, and returns to the normal farming loop. If the Close button does not appear within 15 seconds, FGA first checks for a connection error (Retry) dialog: if present, it returns to the normal loop so existing error handling takes over, and still counts this as a retreat. Otherwise, if the confirmation dialog is still visible, FGA taps confirm once more and waits up to 15 seconds again after 1 second; if Close still does not appear, it stops as shown below without resetting run state since the battle is still active.
4. Afterwards, FGA selects the top quest as usual, refills AP with the configured item if needed, picks a support, and starts again.

## Runs and statistics

- Any retreated battle—whether by wipe or turn limit—**is not counted as a run**. It is not added to run count, run limits, turn counts, or time stats.
- Retreats still consume AP. The next run uses the normal AP refill logic and stops as usual if AP runs out.
- **Stop after this run** and the run limit are checked as usual once you return to the quest list after retreating.
- The exit screen shows a line **`Retreated N times (wipe a, turn limit b)`** (hidden when `0`).
- A normal quest clear resets the consecutive retreat streak to `0`.

## Stop conditions

| Situation | Result |
|---|---|
| Party wipe while **Retreat and Restart** is off | Stops with `"All servants have been defeated and auto-withdrawing is disabled."` |
| Battle menu or retreat button does not appear even after two attempts | Stops with `"Could not retreat from the quest at the turn limit. Stopped before touching anything else."` |
| Confirmation dialog does not open | Closes the menu and stops with `"Could not retreat from the quest at the turn limit. Stopped before touching anything else."` |
| Confirm tapped, but neither Close (within 15 s), connection error, nor confirmation dialog is present | Leaves retreat counts and run state untouched and stops with `"Could not retreat from the quest at the turn limit. Stopped before touching anything else."` |
| Confirm tapped, Close missing, and confirmation dialog is still visible | Taps confirm once more and waits 15 s again; if still missing, stops as above |
| Confirm tapped, Close missing, and a connection error dialog is showing | Counts as a retreat and returns to the normal loop for connection error handling |
| Attempts a third retreat in a single run without returning to the quest list | Stops with `"Could not retreat from the quest at the turn limit. Stopped before touching anything else."` |
| **Stop after N retreats in a row** is on and combined wipe + turn-limit retreats reach `N` in a row | Stops with `"Retreated N times in a row."` (where `N` is the configured count) |

## Notes

- [Weekly mission clear](Weekly-Mission-Route-EN.md) routes do not use turn-limit retreat. On a party wipe, it retreats or stops following the selected battle config's switch, and if it retreats, that route quest ends.
- The retreat button for turn-limit retreat is matched using KR and JP client images. If a game update changes the battle menu layout or text, it may stop recognizing the button.
- Because retreating mid-battle spends AP and yields no drops, start with a large `N` to verify behavior on your first use.
