# Clear Mission Popups Before Repeat

When too many mission-completion popups stack up at the top center of the screen after a quest ends, they can remain visible after the next battle begins and cover the wave and turn display in the top right. Turning this option on makes FGA rapidly tap the popup area at the top center for a configured duration before tapping **Repeat** on the Repeat dialog.

## How to configure

Configure this under **More options → Battle**. It is a global setting and applies to all battle configs.

| Item | Description |
|---|---|
| Clear mission popups before Repeat | Turns the feature on or off. Defaults to off. |
| Tapping time | Sets how long to tap the popup area (`1–60 s`). Defaults to `10 s`, and can only be changed when the switch above is on. |

## How it works

- **Only runs on the Repeat dialog.** It does not rapid-tap on other screens or during battle.
- It taps only one spot: the top center of the screen (where mission popups appear).
- Because it does not check whether popups are actually visible, **every run takes longer by the configured tapping time even when there are no popups.**
- It taps in batches of 10 and checks the elapsed time after each batch. If click delays in **Fine-tune** are set high, the actual duration may run up to about `1–2` seconds longer than configured.
- Once tapping finishes, FGA searches the refreshed screen for the Repeat button again:
  - If the Repeat button is present, it taps Repeat as usual (including Boost Item handling).
  - If the Repeat button is not visible afterward, it returns to the main loop without tapping any button. Even if it finds the button again on the same Repeat dialog, it will not rapid-tap for another `N` seconds—it proceeds straight to Repeat.
- If a stop request arrives while rapid-tapping, it stops immediately.

## Notes

- When the switch is off, there are no rapid taps, extra waits, or extra screen checks—behavior is identical to before.
- Regardless of server (KR or JP) or aspect ratio (16:9, 21:9, etc.), it taps the top center of the screen.
