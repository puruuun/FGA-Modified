# FGA(mod) Servant Condition Mode (up to three targets)

## How to configure

1. Under **Battle Configs → <config> → Command start**, turn on the **Command card count mode** toggle. Turning it off runs **Basic mode**.
2. In card-count mode, select the first target Servant and add **Start conditions** rows to build the condition. Each row has the form `[Any/B/A/Q ▾] − N +`, where `N` is 1–5 cards.
   - **Any** counts all usable face cards belonging to that Servant; `B/A/Q` count Buster, Arts, and Quick cards respectively. Each color can only be added once, and tapping the color chip (`B` red, `A` blue, `Q` green) switches it to an unused color.
   - **Add condition** appends the first unused color with a count of 1. Tap `✕` to remove a row (`✕` is hidden when only one row remains).
   - The **AND / OR** toggle appears in the header only when there are 2 or more rows. Below the rows, a one-line summary such as `"Start when Buster 2+ and Arts 1+"` is displayed.
   - Settings from the previous version (`beta.9`) are shown directly as rows: any non-zero card count `N` or color minimum becomes its own row, and if there is no `Any` row, no total-card-count condition is used.
3. To also use a second target, select a Servant under **Second condition: target Servant** and configure its condition rows independently in the same way. Choosing **Not used** runs with a single condition.
   - A third target option (**Third condition: target Servant**) appears **only while the second condition is in use**. Configure its rows the same way; if the second condition is switched back to **Not used**, the third condition row is hidden and treated as off during execution (saved values are preserved).
   - Conditions are checked in the order **1st → 2nd → 3rd**, starting with the first setting whose condition is met. If any two active targets are set to the same Servant, the script stops instead of starting.
   - Enabling the third target also reveals Command 3 and Card Priority 3 (including the Skill Maker). When disabled, existing behavior and notifications remain unchanged.
4. Set **Maximum skipped turns**. **0 = Unlimited**.
5. The **Command start** box shows only the items above. Per-branch commands and priorities are edited in the original config boxes. **Spam mode (automatic Skill/NP use) is edited in one shared location** and applies identically regardless of which condition triggers.

| Setting location | Display in card-count mode |
|---|---|
| Existing Command box | Displays the existing entry as **Edit command for setting 1**, with **Edit command for setting 2** added below |
| Existing Card Priority entry | Displays the existing entry as **Card and Servant priorities for setting 1**, with setting 2 added below |
| Existing Spam mode | Shows one shared setting |
| Existing Shuffle cards box | Shows one shared card-shuffle setting for all conditions |

The basic setting and setting 1 are not shown twice. Setting 2 appears when a second target is selected. In Basic mode, only the original settings appear. You can check each command's summary and priority state at its respective location.

The original basic setting and setting 1 share the same stored data. Setting 2's command and priorities are stored separately, and setting 1's command is not copied automatically. Turning off the mode toggle or switching the second target to **Not used** preserves your saved settings. Previous setting 1 Spam and shuffle values remain as the shared settings, and old setting 2 Spam and shuffle values are no longer used.

Face-card attacks while waiting use setting 1's priority. Party, Server, Support, Auto choose target, Spam mode, and Shuffle cards are all shared. The old long description text has been removed from the settings screen.

## Shuffle and recheck when the first-turn condition is not met

Select **Shuffle cards → When → Start condition not met on first turn** in the shared settings. **Command card count mode** must be enabled for this to run.

1. On the first turn of battle (Wave 1, Turn 1), FGA recognizes the face cards and checks the configured start conditions.
2. If the first, second, or third condition is met, it starts that setting's command without shuffling. If multiple are met at the same time, the lowest-numbered setting wins.
3. If none of the conditions are met, FGA returns to the skill screen and attempts one card shuffle using the third Master Skill. When the second condition is not used, only the first condition is checked.
4. After shuffling, all conditions are rechecked against the new hand. If a condition is now met, its command starts **from the beginning on that same turn**.
5. If the new hand still does not meet any condition, FGA attacks with the new face cards and advances to the next turn. Subsequent turns keep checking the start conditions without shuffling again.

The shuffle itself does not add to the waiting turn count—the counter only increments when a turn is actually passed with face-card attacks. It is attempted at most once per battle on the first turn, and resets on the next run. If the hand already qualifies before shuffling, the shuffle is not used. Once a command starts, this option will not shuffle again.

You must equip a Mystic Code whose third Master Skill shuffles cards. If card recognition is unclear, FGA checks the same hand up to three times before shuffling and up to three times after shuffling; if it still fails, the script stops without attacking or running commands. Setting 1 retains priority during the post-shuffle recheck as well.

Because this option is fixed to the first turn, the wave number selector is hidden. The existing **None**, **No effective cards**, and **No cards matching NP** options remain unchanged, and those two automatic options run on the specified command wave after a command has started. Selecting this new option in Basic mode does not trigger start-condition shuffling.

## Selection rules

Both the total card count and per-color counts count **only usable face cards belonging to that condition's target Servant**. Other Servants' cards, unusable cards such as stunned cards, and Noble Phantasm cards are excluded. Checks use the existing card-recognition results without adding new screen checks or taps.

Each condition applies a single **AND** or **OR** operator across the total card count and all color minimums greater than 0.

- **AND**: Requires both the total card count and every active color minimum to be met.
- **OR**: Starts as soon as either the total card count or any active color minimum is met.
- If all rows are removed, it is treated as **1 Any card**. If the `Any` row is removed and only color rows exist, only those color conditions are checked. If no color rows exist, both AND and OR check only the total card count. Because the default is **B/A/Q all 0 with AND**, existing configs and older exported files behave the same as before.

| Any | Combine | B / A / Q minimum | Start condition |
|---|---|---|---|
| 2 | AND | 1 / 0 / 0 | At least 2 target cards AND at least 1 Buster |
| 2 | OR | 1 / 0 / 0 | At least 2 target cards OR at least 1 Buster |
| 1 | AND | 1 / 0 / 0 | **Starts whenever the target has a Buster card** |
| 2 | AND | 1 / 1 / 0 | Meets all of: 2+ target cards, 1+ Buster, and 1+ Arts |
| 3 | OR | 1 / 1 / 0 | Meets any of: 3+ target cards, 1+ Buster, or 1+ Arts |
| None | AND | 2 / 0 / 0 | No `Any` row; starts with 2+ Buster cards |
| None | OR | 2 / 0 / 2 | Starts with 2+ Buster cards OR 2+ Quick cards |

**Any 1 + AND + Buster 1** requires only a Buster card, since having 1 Buster automatically satisfies `Any 1`. Arts and Quick work the same way. Deleting the `Any` row removes the total card count requirement.

Waiting notifications and logs display each target's actual per-color counts (for example, `B1 A0 Q0`, with `-` as the minimum total when there is no `Any` row), and start notifications and logs show the selected target's color counts. With **OR**, commands can start from a color condition even when the total card count is below the shown minimum.

Both conditions are evaluated against the same card-screen recognition result, and each condition can use a different card count.

Example when both conditions require 2 or more cards:

| First target (A) | Second target (B) | Behavior |
|---|---|---|
| 2 or more | Fewer than 2 | Starts command and priority for A |
| Fewer than 2 | 2 or more | Starts command and priority for B |
| 2 or more | 2 or more | Starts only the first setting (A) |
| Fewer than 2 | Fewer than 2 | Advances with face-card attacks and checks next turn |

Even if B has more cards, A is chosen whenever A meets its condition. **Once a setting is chosen, it stays locked until the battle ends**—if you start with B and A gets more cards on a later turn, it does not switch. On the next run, all conditions are checked again.

While waiting, scripted skills, NPs, Spam mode, and standard automatic shuffles are held. Only the new first-turn shuffle option applies as an exception in the order above. Waiting continues across wave changes. On the same turn a condition is met, FGA returns to the skill screen and runs the chosen command from the beginning.

## Shifting waves and turns

Example when a condition is met at **actual Wave 2, Turn 3**:

| Position in chosen command | Actual execution position |
|---|---|
| Wave 1, Turn 1 | Wave 2, Turn 3 |
| Wave 1, Turn 2 | Wave 2, Turn 4 |
| Wave 2, Turn 1 | Wave 3, Turn 1 |

Per-wave Card Priority and Brave Chains of the chosen setting, as well as the shared Spam and standard auto-shuffle wave settings, follow the shifted command waves. The new first-turn shuffle option applies only on the actual first turn of battle before waiting. Actual turn statistics include waited turns. Because shifting does not add extra waves to the quest, later waves in the command may not be reached.

## Edge cases and exception handling

- If the same Servant is selected for two active conditions, an error is shown and the script stops instead of starting.
- If one target is defeated or leaves the frontline, the script keeps waiting with the remaining condition(s). If none of the configured targets remain deployed, waiting is cancelled and the script stops.
- Unusable face cards (such as stunned cards) and NP cards are excluded from the count.
- If card owners or types are unclear, FGA rechecks the same turn up to three times; if it still fails, it stops without attacking. In this case, return from the card screen and restart.
- If **Maximum skipped turns** is set and still unmet after waiting that many turns, the script stops. A hand that satisfies a condition immediately after the last allowed skipped turn can still start.
- If the quest ends before any condition is met, the command is recorded as not started and resets on the next run.

## Tip: NP + 2 face cards

In each setting, include one NP for that condition's target, set the required card count to 2 or more, set **Cards before NP** to `0`, and set **Brave Chains** to **With NP**. Also check NP Spam in the shared **Spam mode** settings so extra NPs do not cut in.

Start conditions do not check whether the NP gauge is full or whether NP use is sealed. If a skill triggers a card shuffle, type change, or party swap, the actual hand may change and FGA selects from the updated hand. Commands never re-apply start conditions or repeat skills that have already been used.
