# Friend Manager

**Browse all support Servants and investment levels from your Regular, Event, and Grand formations in the captured friend list, and filter by friend name, Servant, CE, level, or inactivity period.** It is read-only and never touches the game or changes settings.

Open via **More options → Additional features → Friend manager** (directly below **Log collection**). Shown only on Android 10 or later when **Enable log collection** is turned on; turning the switch off hides it without deleting your saved friend list. Set up [Log Collection](Gains-Tracker-EN.md) first.

## Populating the List

1. Launch the game with log collection turned on.
2. Open **Menu › Friends & Followers** inside the game once. The full list is built from the friend list response received at that time.
3. If no list is stored yet, the same instructions are shown on screen.

A friend list is saved **only when the account can be identified and has stored state**. If you are capturing a second account for the first time, launch the game with log collection on so its login state is saved, **visit the main menu once**, and then open Friends & Followers. Responses from an unidentified account never overwrite another account's list. A list belonging to an already stored account saves to that owner even if a different account is currently displayed, without switching the active account.

Afterward, whenever you open a quest's Support selection screen or finish a battle, Servants belonging to people who are **friends** in the received support list update in place. When the account is identified in the response, new friends are added as well; when account metadata is absent, only friends already in the current account's list are updated. Non-friend supports are never stored. After removing or adding friends, reopen **Friends & Followers** to refresh the roster—removed friends and pending friend requests are excluded from the new list. Each account's list is stored separately.

## Screen Layout

Below the title, **Choose account** shows the account name, **Lv N · Friends N · Updated N min (h/d) ago**, and the server tag (**KR / JP**). If the current account has a roster, it opens first; otherwise, the most recently updated roster is shown. With multiple accounts, tap to switch; with one account, only the info is shown without a menu.

Below that appear the active filter chips. The updated time (in the account picker row) reflects when Friends & Followers was last opened and uses the same `min / h / d` units as the Login column. Each row is one support Servant slot, sorted by most recently active friend. The table header stays sticky at the top when scrolling.

| Name | Skills | Appends | CE | Login |
| --- | --- | --- | --- | --- |
| Line 1: Servant name; Line 2: `Lv120 NP5 · Friend name` | Three skills `a/b/c` | All five joined like `5/5/5/-/-` (locked is `-`; wraps automatically if narrow) | Right after the CE name, **Max** (at MLB) and `Lv100` on the same line; Grand formation shows CE1 and CE3 on separate lines with the bond effect below | Elapsed time since last login: `N min` / `N h` / `N d` |

Long Servant, friend, and CE names truncate with an ellipsis. Appends wrap to fit the column width, and Grand formation adjusts to the height of two CE lines plus the bond effect label. For CEs, the name truncates first while **Max** and Lv stay visible beside it. **Login** shows `–` when no login record is available, and missing CEs show **No CE**. In the Name column, `NPn` indicates Noble Phantasm level.

In Regular and Event formations, support slots marked as Grand Servants display a small **Grand** badge next to the Servant's name. Grand metadata populates when you reopen **Friends & Followers** in game with log collection enabled. Both official JP and KR display according to received data, and KR updates the same way once Grand Servants roll out there.

**Grand formation** is a separate formation fetched for Grand battles and special quests, selected independently from the Grand badge on Regular/Event decks. It displays CE1 and CE3 names, MLB tags, and levels on two lines, followed below by **Bond: NP charge / Bond: Base effect / Bond: None**: **NP charge** when a bond CE is equipped with the NP-charge effect enabled, **Base effect** when a bond CE is equipped without that setting, and **None** when no bond CE is equipped. Grand formation is also ordered by recent login, with one friend's Grand slots ordered by seat number. Stored Grand formations update only when a support list response includes Grand formation data.

Tapping a row expands **Friend code** with a copy button and **Friend Lv N · Class slot · Deck N · M/d HH:mm** (last login in the device's local time) directly underneath. Class slot shows the support slot (`All`, `Saber` … `Berserker`, `Extra`), and Deck indicates which Regular or Event deck of that friend holds it. The copy button copies digits only. If the friend code is not known yet, it is hidden. Tap the row again to collapse.

Servant names and classes come from Atlas public lists; JP entries absent from KR are supplemented from the JP list, and unknown IDs appear as `#ID`. Servant portraits are not displayed.

## Filters

Tap the first **Filters** chip (sliders icon) in the chip bar above the list to open the filter panel. The formation chip and active filter chips follow; tapping an active condition chip clears just that filter, while the formation chip is always shown.

- **Wide screens** (width 600 dp or wider: tablets, landscape phones): opens as a full-height ~360 dp panel on the right while the friend list stays visible and scrollable on the left. Changes update the list immediately without an apply button (friend name applies ~0.25 s after typing stops). The bottom bar shows **Reset**, **N slots**, and **Close**; dismiss with `✕`, Back, or **Close**. The panel stays open across screen rotation.
- **Narrow screens** (under 600 dp wide): opens as a single-column panel sliding down from the top. Changes apply to the list only when you tap **Show N slots**; closing without applying leaves the previous filters intact.

Filter sections are arranged vertically in one column, scroll if long, and are separated by thin dividers.

- **Deck**: Pick **Regular deck / Event deck / Grand formation** from the segmented buttons at the top. Switching clears Servant and CE selections.
- **Friend name**: Matches any substring of the friend's name, case-insensitively.
- **Servant**: Lists Servants present in the selected formation along with slot counts, prefixed with class like **(Saber) Name**, searchable by both name and class. Multiple Servants can be selected; tap **Done** to close the picker. Rows matching any selected Servant are shown. **All servants** clears the selection. Selected Servants appear as removable chips below the button and in the chip bar above the list; tapping a chip removes just that Servant. A single Servant saved from an earlier screen version restores automatically. Uses localized class names, falling back to Atlas's raw string for unrecognized classes.
- **Servant level**: One block with a small **At least / At most** toggle on the right of the header and **All / 80 / 90 / 100 / 120** chips below. For example, **100 or higher** matches level ≥ 100 and **100 or lower** matches level ≤ 100 (inclusive). Filter chips display like **100 or lower**, and **All** shows every level regardless of toggle direction.
- **Skills & Appends**: Pick per slot across two rows: **Skill 1 / 2 / 3** and **Append 1 / 2 / 3 / 4 / 5**. Each numbered button displays its active condition and opens a small menu with **All / 10 / Not 10**; locked Appends (`-`) count under **Not 10**. All configured slot conditions must be met simultaneously (for example, **Append2 10** shows only slots where Append 2 is 10; combining **Skill1 10** and **Append2 Not 10** requires both). Active conditions appear as removable chips in the panel and above the list; tapping a chip resets that slot to **All**. Applies across Regular, Event, and Grand formations and persists when switching formations. Wide screens apply immediately; narrow screens apply via **Show N slots**.
- **CE**: Multi-select picker closed via **Done**. Selected CEs appear as removable chips below the button; tapping a chip removes just that CE. Slots equipping any selected CE are shown. Grand formation checks CE1 and CE3, excluding bond CEs from CE selection and limit-break filtering. Under **Bond bonus**, Chaldea Teatime appears first, followed alphabetically by CEs granting +20% bond to borrowed supports, then all others alphabetically.
- **CE limit break**: **All / Max limit break only / Not max limit break**. When CEs are selected, at least one selected CE must match that limit-break state; when no CE is selected, any CE in that slot matching the state qualifies. Slots without a CE appear under **All**.
- **Last login**: **Any login / Inactive**. Choosing **Inactive** reveals a `[−] [N] [+] [unit ▾] or more` row. **N** accepts direct numeric input (digits only, clamped to **0–99**) or **− / +** adjustments, and the unit dropdown offers **min / h / d**. Displays slots inactive for at least `N × unit` (default 3 days). Out-of-range values restored from older screen state clamp to 0–99. Friends without login history appear under **Any login**.

The earlier **Grand Servants only** switch has been removed: choose Grand formation in the Deck selector instead, and **Max limit break only** moved into the three-way **CE limit break** filter.

**Reset** reverts to Regular deck, empty friend name, All servants, All CEs, limit break All, level All, Skills 1–3 All, Appends 1–5 All, and Any login (keeping the Inactive default at 3 days). On narrow screens, **Show N slots** displays the matching count, applies the filters, and closes the panel.

## Privacy

- Friend names, friend codes, and support metadata are stored **solely on this device** (in app-private storage) and are never sent anywhere. Profile messages are neither read nor saved.
- None of this data is included in Logs or Chaldea file exports.
- Running **Delete log collection data** or deleting a specific account deletes its friend list too.
- Tapping the **Friends icon** at the top of the Account dashboard (shown only for accounts that have received a friend list) opens Friend manager with that account selected.
- Once a friend list has been captured, the Account dashboard's Profile card shows **Friend limit: current/limit** (e.g., `139/145`); before capture, only the limit is shown.
