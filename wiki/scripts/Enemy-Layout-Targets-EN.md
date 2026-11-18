# Enemy Layout Targeting

Enemy targeting in skill commands originally supported only `t1`, `t2`, and `t3` (the standard 3-enemy layout). In 6-enemy and Raid layouts, enemies sit at different positions, causing `t1`–`t3` to tap the wrong spots. This feature adds fixed target codes for both layouts while keeping `t1`–`t3` positions and behavior unchanged.

## Target codes

| Layout | Code | Tap location |
|---|---|---|
| Standard 3 enemies | `t1` `t2` `t3` | Left, center, and right enemy |
| 6 enemies (top row) | `t4` `t5` `t6` | Top-row left, center, and right enemy HP bars |
| 6 enemies (bottom row) | `t7` `t8` `t9` | Bottom-row left, center, and right enemy HP bars |
| Raid (boss) | `tR` | Boss with the full-width HP bar at the top of the screen |
| Raid (lower row 1) | `tA` `tB` `tC` | Left, center, and right enemy on the first row below the boss |
| Raid (lower row 2) | `tD` `tE` `tF` | Left, center, and right enemy on the second row below the boss |

There are two Raid layouts:

- A boss with enemies having only HP bars on the left and right below it (e.g., Grand Marie)
- A boss with two rows of 3 enemy slots below it

`tA`–`tC` are positioned on the overlapping area so they tap enemies in both layouts. The second row (`tD`–`tF`) is used only in the 6-slot layout.

- `t10`, `t11`, and `t12` from earlier versions are read as `tA`, `tB`, and `tC`, and re-saving in the Skill Maker converts them to `tA`, `tB`, and `tC`. Combinations where another digit follows `t1`, such as `t14`, keep their original meaning (`t1` followed by Servant 4 NP).
- Unknown codes (`t0`, `tX`, etc.) stop with a skill command parse error.
- The same codes work on wide screens such as 21:9; positions shift automatically to match the aspect ratio.

## Using it in the Skill Maker

1. Tap the orange **Set target** button in the top-left of the Skill Maker main screen. The currently selected target is not displayed on this button.
2. A dedicated **Choose Enemy Target** screen opens (similar to the skill target screen), showing target buttons arranged in the center like the battle screen:
   - **3 enemies**: one row of `t1` `t2` `t3`
   - **6 enemies**: `t4` `t5` `t6` with `t7` `t8` `t9` below
   - **Raid**: wide `tR` above `tA` `tB` `tC`, with `tD` `tE` `tF` below
3. Switch layouts using **3 enemies / 6 enemies / Raid** at the bottom of the screen. The screen opens on the layout that contains the current target.
4. Tap a target to append it to the command and return to the main screen. Tapping the already-selected target again adds nothing. Pressing the system Back button without picking returns to the main screen unchanged.

## Notes

- In the second Raid row, `tD` (left) and `tE` (center) positions are **estimated**: only the right slot `tF` was measured from a real capture, and the others were calculated using equal slot spacing. Please report if they are misaligned.
- **Auto choose target** only knows the positions of the standard 3-enemy layout. It cannot pick targets automatically in 6-enemy or Raid layouts, so specify them directly with the codes above when needed.
- FGA does not check the layout on screen. Using a code from another layout simply taps that coordinate, so make sure the code matches the quest's layout.
- Chaldea import continues to generate `t1`–`t3` only.
