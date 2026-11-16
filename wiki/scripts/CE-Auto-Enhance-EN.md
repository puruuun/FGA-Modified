# CE Auto-Enhance

Below the title of the launcher dialog, the notice **Check the in-game filter settings before starting.** is displayed. Before starting, make sure your in-game filter settings select the enhancement materials you intend to use.

On the JP or KR Craft Essence enhancement screen, choose the CE you want to enhance, configure the game's **Recommended Selection** filter first, and then start FGA. Tap **OK** in the **CE enhancement** launcher dialog to begin, or **Cancel** to exit without running.

On JP and KR, you can switch the run mode using **Switch to servant level-up** / **Switch to CE enhancement** at the bottom of the launcher dialog. This changes only the dialog contents without re-detecting the screen, and tapping **OK** runs whichever mode is currently displayed. If auto-detection picked the wrong mode, switch it to match your actual game screen.

FGA turns Auto Fill (auto-set) ON and repeats **Execute → Enhance → OK/Confirm**. Recommended Selection uses your in-game filters as-is, and lock/favorite CE protections follow your in-game settings. Excess-EXP refund popups are closed and enhancement continues.

If the filter includes ★4/★5 or already-enhanced CEs, the game displays a confirmation dialog after **Execute**. FGA automatically confirms this dialog (**Confirm** / **決定** on JP) and continues enhancing.

- **Max level** (`CE reached its maximum level.`): Stops when the grayed-out Recommended Selection button is detected.
- **No matching CE** (`No CE matches the recommended-selection filters.`): Check your filters and available materials.
- **Insufficient materials or QP** (`No enhancement materials or QP remain.`): Check your remaining materials and QP. Also stops with this reason if no confirmation dialog appears within 3 seconds after enhancing.
- **Stopped / unexpected error** (`CE enhancement stopped.`): Check the screen and start again.

Screens with no enhancement target selected and other servers are not supported. The legacy CE bomb mode has been removed.
