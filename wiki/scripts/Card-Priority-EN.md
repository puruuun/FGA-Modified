# Card Priority Extensions

The Card Priority screen lets you configure **up to 10 entries**, **Wave/Turn splitting**, **per-entry Style (Affinity/Servant)**, **Chain Priority**, and **Detect Aoko card change**. Existing configs continue to work as before.

## Screen layout

From top to bottom, the screen is arranged as **Split by** → Wave/Turn tabs (select or add) → the page for the selected entry. **Style** and **Use Servant Priority** are no longer a single global option at the top of the screen; instead, they appear **per entry** in the settings card below the priority list. The order inside that card is **Style**, **Use Servant Priority**, **Use Chain Priority**, **Brave Chains**, and **Rearrange Cards**.

## Up to 10 entries

You can add **up to 10 entries** per wave (or turn). If the battle progresses past the last entry, the **last entry repeats**. Each entry is a tab in the row at the top; tap a tab to switch to that entry. Tap `+` right after the last tab to add an entry (it moves right as entries are added; on a narrow screen, scroll the tab row sideways to reach it), and the `x` button at the right end of the tab row to remove the last entry. Each new entry copies **Style** and **Use Servant Priority** from the entry right before it, and copies the priority list from the first entry.

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

The Servant face-card check identifies which Servant owns each command card by matching face portraits. FGA decides **automatically** at script start by inspecting the battle config—turning it on if any of the following apply, and skipping it if none apply:

- Any entry uses **Servant** style.
- Any Affinity-style entry has **Use Servant Priority** enabled.
- Any Affinity-style entry has **Use Chain Priority** enabled.
- Any Affinity-style entry has **Brave Chains** set to `With NP` or `Avoid`. Brave Chains on Servant-style entries are ignored and do not enable it.
- **Shuffle cards** is set to **No cards matching NP**.
- The skill command contains a Hakuno Shuffle.
- **Command card count mode** is enabled. When a second or third start setting is enabled, its entries and command are checked as well.

Below Card Priority in the Battle Config editor, the result appears as `Servant face-card check: auto — on (brave chains W2)` or `Servant face-card check: auto — skipped`, updating immediately when settings change. Battle runs use the same decision and write a line (`face-card check: on (…)`) to the log at start.

### Distinguishing Support cards

Support Servant cards are identified in the following order:

1. If the `SUPPORT` badge (the trailing `ORT` portion) is visible on a card, it is treated as a Support card and excluded from face matching.
2. When a Support Servant appears on the frontline, FGA opens its status details screen and saves its face portrait. If this portrait is virtually identical to one of your own Servants (similarity 0.99 or higher, when two copies of the same Servant are in the party), the Support portrait is not used for face matching in that run and only the badge is checked.
3. Any card whose badge was missed is assigned to the deployed Servant with the closest face match (0.5 or higher). When usable, the Support face portrait is also included as a candidate, reducing misidentification of Support cards as your own Servants when buff icons cover the badge.

The result is recorded as a single line (`Support face of … `) in the log.

## Chain Priority

On an entry with Chain Priority on, the app first looks for **a chain this hand can make** before it picks cards. This is original FGA's Chain Priority (Mighty, color chains, Avoid), taken over as it is.

- It tries the chains in your order (default: Mighty → Buster → Arts → Quick) and picks the first one the hand can make.
- If none can be made, it picks with servant priority and card priority as before.
- With **Avoid** on, it avoids chains as far as it can. A hand of one color cannot avoid one.
- An NP counts as one card of the chain, so the NP card type of each Servant is read (see below).
- Inside a chain, the order of the cards follows card priority (affinity included). A weak Arts ranked above a normal Buster can give A-B-Q.

Example: card priority Arts > Buster > Quick, hand 1 Arts, 1 Buster, 2 Buster, 3 Buster, 3 Quick

- Off: 1 Arts → 1 Buster → 2 Buster
- On (default order): 1 Arts → 1 Buster → 3 Quick (Mighty Chain)
- On (Buster first): 1 Buster → 2 Buster → 3 Buster (Buster Chain)

### Where to turn it on

Turn on **Use Chain Priority** in the entry settings card of the card priority screen. It is set per wave or turn entry and saved per profile (first, second and third start). When it is on, a **Chain Priority** panel appears below.

- Tap a tile to turn that chain on or off. A grey tile is never tried.
- Hold and drag to reorder.
- A new entry copies the switch and the order of the previous entry.
- The default is off. Existing settings are read as off.

The card priority summary on the auto skill config screen marks an entry with it on as `Chain Mighty>Buster>Arts>Quick`.

### What wins over what

1. A turn with **direct cards** (`&…`) plays the direct cards only. Chain Priority is not applied, and the fallback places of `?` are filled by card and servant priority only.
2. On a **Servant** style entry the Chain Priority switch is greyed out and ignored, like Brave Chains and Rearrange Cards.
3. On an **Affinity** entry with Chain Priority on: Brave Chains → chain order → servant priority → card priority → Rearrange Cards.
4. An entry with Chain Priority off behaves exactly as before.

### Brave Chains "Always"

On an entry with Chain Priority on, Brave Chains gets an **Always** option: also on a turn without an NP, a Brave Chain of one Servant's three cards is picked when the hand has one. With `With NP` or `Always`, a Brave Chain the hand can make on its own wins over a color chain. With Chain Priority off, a saved `Always` acts as `With NP` and is shown as such.

### Reading the NP card type

When any entry has Chain Priority on, the servant face-card check turns on by itself (`Servant face-card check: auto — on (chain priority W1)`), and the NP card type is read in the same Servant details dialog that is opened to save the face. No tap is added. When no entry has it on, the NP type is not read and taps, screen reads and waits stay as before.

- After a **skill with a choice** such as Emiya's or BB Dubai's, the details dialog is opened again to read the new type, and checked again next turn.
- While the type is changed, a banner covers the NP card art and **the new type cannot be read**. The remaining-turns number on the banner is read instead, and **the last known type** is used until the banner is gone. If the number cannot be read either, the dialog is opened again every turn.
- A **transforming** Servant such as Mélusine or Ptolemaios gets the NP type of the other form read separately; the current form is told from the portrait.
- A spam NP joins the chain plan only when its portrait is seen on the NP card of the card screen.
- An NP whose type was never read, a card of unknown type and a stunned card are not used to build a chain.

Not done: NP gauge and seals are not read (an NP in the command is assumed ready). Space Ishtar's color choice is not recognized. Only the current turn is planned; the next hand, damage, stars and enemy HP are not.

### Fine-tune

Settings → Fine-tune has the same four settings as original FGA.

| Setting | Default | Used for |
|---|---|---|
| NP Card Type Similarity | 50% | The NP type in the details dialog (only with Chain Priority) |
| NP card detection similarity | 80% | The portrait on a spam NP card (only with Chain Priority) |
| Wait after Order Change | 1000 ms | The wait after Order Change, always |
| Servant details wait | 250 ms | The wait after tapping the INFO tab of the details dialog, whenever face cards are checked |

The defaults of the two waits are the fixed waits used so far.

### Importing from original FGA

Importing original FGA configs converts the per-wave Chain Priority switch (`use_chain_priority_per_wave`, else `use_chain_priority`), the chain order (`chain_priority`) and the per-wave servant priority switch (`use_servant_priority_per_wave`) into this app's per-entry settings.

## Detect Aoko card change

The **Detect Aoko card change** switch sits in a box matching the per-entry settings card—below the Servant priority list if shown, or directly below the per-entry settings card otherwise.

- It defaults to **Off**; when off, card reading and priority behavior are identical to before.
- When on, it applies to **all waves and turns (across all profiles)** in that battle config.
- If a command card has the **swap icon** (red/blue circular arrows) at the bottom right of its card-type label, that card is treated as an **Arts** card. Stunned cards and cards whose type cannot be read are not checked.
- Converted cards are counted as Arts for Card Priority, Brave Chains, Direct cards, Shuffle cards, and Command card count start conditions.

## Things to note

- If a list becomes invalid, it resets to the default order (`1B 1A 1Q 2B 2A 2Q 3B 3A 3Q`).
- Cards whose Servant position could not be read are placed at the end, so if the hand fails to be recognized, the selection order may differ from what you intended.
