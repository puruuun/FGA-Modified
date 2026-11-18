# Bond Point Tracking & MAX Stop

**More options → Battle → Enable bond tracking** defaults to ON. Turning it off hides the **Bond points** block in the battle launcher and skips tracking and MAX stops. Saved per-config values are preserved and restored when turned back on.

Configure it under **Play button → Battle launcher → Bond points** (below **Item auto-use**). Select a battle config on the left, choose tracking, MAX stop, and any character slots to ignore, then tap **OK** to save to that battle config. Tapping **Cancel** discards changes to bond settings. Supports KR and JP servers, preserving existing storage keys, values, copying, exporting, and importing. Tracking and MAX stop default to OFF.

- **Track bond point gains**: Sums base and bonus gains from left to right for **Character 1, 2, 3, 4, 5** on the post-quest bond result screen. `+4,748(+2,660)` adds `7,408`.
- **Stop when bond MAX is confirmed**: When MAX is confirmed for an unchecked character slot, stops on that quest's bond result screen without starting the next quest.
- **Ignore MAX for checked characters**: Check boxes below the horizontal row of character slots (**Character 1–5**). **Checking Character 2 ignores Character 2's MAX and keeps farming.** All slots default to unchecked for new configs. This option does not turn off point tracking.

**It does not read the pre-battle party screen or link slots to support positions.** Numbers refer to the five result-screen slots, not original party slots 1–6. Even if started from a quest start screen, mid-battle, or results screen, no prior party check is required. You are responsible for keeping party/result-screen order consistent. If you swap Servants mid-run, gains continue summing into the same result slot; stop and start a new run to track separately.

## Bond Stop During KR Log Collection

When starting a battle script on **the KR server while log collection is running**, that run gets bond **numbers** (points gained) from the log-collection responses. If log collection is auto-starting at script start, it waits up to 3 seconds and decides once for the run; toggling collection mid-run does not change this choice.

**Stop when bond MAX is confirmed** and level 10+ bond level-up stops still use **screen reading (OCR)** even during log collection, exactly the same as when log collection is off. Per-character MAX labels are confirmed on two consecutive frames, and the 1.2-second wait and result-advancing speed are unchanged. Slots checked in **Ignore MAX for checked characters** are excluded. Log-collection responses are not used for stop decisions. In this mode, bond point numbers are not read from the screen, and per-character bond totals are not shown on the exit screen; check per-Servant bond gains under **Log → Gains (log collection)**. The Screenshot Bond setting still applies as usual.

If only **Track bond point gains** is enabled (without MAX stop), the script skips screen reading and advances through result screens the same number of times as when bond tracking is off.

## Without Log Collection: Normal Results and Level-Up Popups

When a normal bond result screen is detected, it waits **1.2 seconds and then reads MAX icons and gain numbers on a fresh frame**. Because this waits for the game's number count-up animation, it is not shortened by the app's Wait Multiplier. Gains are recorded only when at least two high-confidence reads and consecutive frames agree. Re-seeing the same quest's screen does not double-count, and an unreadable slot never shifts the numbering of other slots.

On a bond level-up popup, obscured background gains and MAX markers are not read. Confirming a **reached level 10–16** to the right of the arrow stops the script even if the result slot cannot be identified. OCR runs first, and if it fails, bright digit width distinguishes single-digit vs. two-digit numbers. Level 10 or higher must still be confirmed 350ms later to stop; levels 9 and below continue.

If both the reached level and digit width remain unconfirmed, the popup is dismissed once. It does not skip the next popup, and normal result MAX checks and Screenshot Bond settings remain active. A stop where the slot could not be identified displays **Stopped after confirming a bond level-up to 10 or higher.** Check and adjust your party afterward. Obscured gains are recorded as missing; the script does not track current bond levels via gems or read NEXT numbers.

## Without Log Collection: Exit Screen Totals

Displays per-character totals for Character 1–5 along with read and missing counts for the current run. Starting a new run resets totals. Normal results already at MAX are recorded as 0 gain, whereas unreadable gains count as missing. If there are missing reads, the displayed total may be lower than the actual total earned.

Quests whose gains were obscured by a bond level-up popup are excluded from each character's missing count and shown on a separate line, `"Missing due to bond level-up popups: n quests"`. This line appears only when such quests occurred, and obscured gains are not recovered. Values already read before the popup appeared remain included in the read count.

Enabling either option advances result screens one at a time to ensure reading time. Disabling both restores the legacy result-tapping behavior. Enabling only MAX stop skips gain OCR and the exit totals display.
