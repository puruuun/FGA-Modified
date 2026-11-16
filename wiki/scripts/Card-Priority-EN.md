# Card Priority Extensions (10 entries, Wave/Turn, per-entry style)

The Card Priority screen lets you configure the following three features. Existing configs continue to work as before.

## Screen layout

From top to bottom, the screen is arranged as **Split by** → Wave/Turn tabs (select or add) → the page for the selected entry. **Style** and **Use Servant Priority** are no longer a single global option at the top of the screen; instead, they appear **per entry** in the settings card below the priority list. The order inside that card is **Style**, **Use Servant Priority**, **Brave Chains**, and **Rearrange Cards**.

## Up to 10 entries

You can add **up to 10 entries** per wave (or turn). If the battle progresses past the last entry, the **last entry repeats**. Tap `+` to the right of the tabs to add an entry, and `x` on the last entry to remove it. Each new entry copies **Style** and **Use Servant Priority** from the entry right before it, and copies the priority list from the first entry.

## Split by: Wave / Turn

Choose what each entry follows under **Card Priority → Split by**. This is saved separately per profile (Setting 1 and Setting 2).

| Criterion | Entry name | Behavior |
|---|---|---|
| Wave (default) | `WAVE 1…10` | Uses the entry matching the current wave number. Same as before. |
| Turn | `TURN 1…10` | Uses the entry matching the cumulative turn number counted from the start of the quest. The counter continues across wave changes. |

- Under Turn splitting, the first turn is `TURN 1`.
- When using **Command card count mode**, waited turns are not counted; the turn in which the command starts is counted as `TURN 1`. Face-card attacks while waiting use the `TURN 1` entry.
- Card priority, Servant priority, Rearrange Cards, and Brave Chains all follow the same entry. Skill commands, card shuffle, and other behaviors are unchanged.

## Style: Affinity / Servant

Choose this at the top of each entry's settings card under **Style**. Because it is saved per entry, you can mix styles—for example, `Affinity` for wave 1 and `Servant` for wave 2. It is saved separately per profile (Setting 1 and Setting 2), and defaults to **Affinity** (the original style). Switching the style updates that entry's priority list right away.

In **Servant** style, hold and drag the 9 items `1B 1A 1Q 2B 2A 2Q 3B 3A 3Q` to set their order.

- The number is the on-screen **Servant position** (1–3 from left to right), and the letter is the card type (`B` Buster, `A` Arts, `Q` Quick). Affinity such as Weak or Resist is not used.
- Cards in hand are picked in list order. For example, setting `1B 2B 1A` clicks Servant 1's Buster, then Servant 2's Buster, then Servant 1's Arts, allowing you to alternate between Servants.
- If multiple cards match the same item, their left-to-right order in hand is preserved.
- Stunned cards and cards whose Servant position or type cannot be read are placed at the end.
- Each wave/turn entry saves its own list separately.
- **Only in an entry set to Servant style**, **Use Servant Priority**, **Brave Chains**, and **Rearrange Cards** are grayed out and **ignored**—the list order is the exact click order. Other entries set to Affinity style still apply these settings normally. Saved values are not deleted, so switching back to Affinity style restores them.
- **Use Servant Priority** is also toggled on and off per entry. When enabled on an entry, the Servant priority order list appears; cards are grouped by Servant order first, then ordered by affinity within each Servant.
- On subsequent waves or turns beyond the last entry, the last entry's **Style** and **Use Servant Priority** settings repeat along with its list.
- When filling **fallback** slots in Direct cards, the current entry's style is also followed.
- Noble Phantasm (NP) usage, fixed attack orders, and other behaviors remain unchanged.

## Compatibility with existing configs

Configs saved in earlier versions have no per-entry values, so all entries inherit the old single **Style** and **Use Servant Priority** values. Existing configs behave identically to before, and once you open and save the Card Priority screen, settings are stored per entry. Exporting, importing, and copying configs preserve all per-entry values.

## Summary display

In the Battle Config editor, the Card Priority summary shows each entry's list according to its style—for example, `W1: WB, WA, WQ…` for Affinity entries and `W2: 1B, 1A, 1Q…` for Servant entries—with a `Servant priority` label on entries where Servant priority is enabled. The summary updates immediately when returning from the Card Priority screen.

## Servant face-card check (auto)

The Servant face-card check identifies which Servant owns each command card by matching face portraits. The old **Skip servant face-card check** option (**More options → Battle**) has been removed; instead, FGA decides **automatically** at script start by inspecting the battle config. It turns on if any of the following apply, and is skipped if none apply:

- Any entry uses **Servant** style.
- Any Affinity-style entry has **Use Servant Priority** enabled.
- Any Affinity-style entry has **Brave Chains** set to `With NP` or `Avoid`. Brave Chains on Servant-style entries are ignored and do not enable it.
- **Shuffle cards** is set to **No cards matching NP**.
- The skill command contains a Hakuno Shuffle.
- **Command card count mode** is enabled. When a second start setting is enabled, its entries and command are checked as well.

Below Card Priority in the Battle Config editor, the result appears as `Servant face-card check: auto — on (brave chains W2)` or `Servant face-card check: auto — skipped`, updating immediately when settings change. Battle runs use the same decision and write a line (`face-card check: on (…)`) to the log at start. Legacy values of the removed option in previously saved configs are ignored.

### Distinguishing Support cards

Support Servant cards are identified in the following order. The old **Skip support icon check** option has been removed and this behavior now always applies:

1. If the `SUPPORT` badge (the trailing `ORT` portion) is visible on a card, it is treated as a Support card and excluded from face matching.
2. When a Support Servant appears on the frontline, FGA opens its status details screen and saves its face portrait. If this portrait is virtually identical to one of your own Servants (similarity 0.99 or higher, when two copies of the same Servant are in the party), the Support portrait is not used for face matching in that run and only the badge is checked.
3. Any card whose badge was missed is assigned to the deployed Servant with the closest face match (0.5 or higher). When usable, the Support face portrait is also included as a candidate, reducing misidentification of Support cards as your own Servants when buff icons cover the badge.

The result is recorded as a single line (`Support face of … `) in the log.

## Detect Aoko card change

The **Detect Aoko card change** switch sits in a box matching the per-entry settings card—below the Servant priority list if shown, or directly below the per-entry settings card otherwise.

- It defaults to **Off**; when off, card reading and priority behavior are identical to before.
- When on, it applies to **all waves and turns (across all profiles)** in that battle config.
- If a command card has the **swap icon** (red/blue circular arrows) at the bottom right of its card-type label, that card is treated as an **Arts** card. Stunned cards and cards whose type cannot be read are not checked.
- Converted cards are counted as Arts for Card Priority, Brave Chains, Direct cards, Shuffle cards, and Command card count start conditions.

## Things to note

- If a list becomes invalid, it resets to the default order (`1B 1A 1Q 2B 2A 2Q 3B 3A 3Q`).
- Cards whose Servant position could not be read are placed at the end, so if the hand fails to be recognized, the selection order may differ from what you intended.
