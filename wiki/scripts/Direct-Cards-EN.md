# Direct Attack Order for a Turn

Since **stable version 0.4.0**, you can fix the selection order of three cards—including face cards and Noble Phantasms—for a turn. [FGA(mod) APK](https://github.com/puruuun/FGA-Modified/releases/tag/v0.4.0) installs as an update using the same package ID and signature as existing modified builds. The app name is FGA(mod) and its icon is a blue Arts card.

## How to configure

1. Open **Command → Attack** in the battle config you want to edit.
2. Tap the **Direct cards** button next to **Cards before NP**.
3. Turn on **Fix the attack order for this turn**.
4. For **Attack 1**, **Attack 2**, and **Attack 3**, choose from **Buster**, **Arts**, **Quick**, **Servant 1–3 NP**, or **Unselected**.
5. To fill missing colors with other cards, check **Fill missing and unselected cards by priority**. It is off by default and is disabled when **Fix the attack order for this turn** is turned off. Whenever any slot is set to **Unselected**, this box is checked automatically and cannot be unchecked until all **Unselected** slots are changed back (the prior checkbox state is preserved).
6. Tap **Save**, review the displayed order, and add it to the command using **Next Turn in same wave** or **Next Wave**.

While Direct cards is active, the selected three cards are used instead of the normal NP buttons and **Cards before NP**. Reopening the button and turning off the toggle returns to the normal settings on the same screen. Tapping **Dismiss** discards any changes. Each time you open a new Attack dialog, Direct cards starts off, so turn it on only on the turns where you need it.

## String syntax

Specify exactly three characters after `&`. On turns with priority fallback enabled, add a single `?` character right after `&` (`&?b4a`). `?` is not an attack slot; all other rules stay the same. Plain `&b4a` runs as before without fallback. `b/a/q` stand for **Buster/Arts/Quick**, `4/5/6` stand for the **NP of current frontline Servant 1/2/3**, and `p` stands for **Unselected** (a slot to fill by priority). Any command containing `p` must include `?` (`&?bp4`); `&bp4` is rejected as invalid. Because face-card owners are not restricted separately, place any Servant-card-creating skill before the attack if needed.

```text
&ba4,&bba,&b5a,#,
```

| Command | Selection order |
|---|---|
| `&ba4` | Buster → Arts → Frontline 1 NP |
| `&bba` | Buster → another Buster → Arts |
| `&b5a` | Buster → Frontline 2 NP → Arts |
| `&a4b` | Arts → Frontline 1 NP → Buster |
| `&4ba` | Frontline 1 NP → Buster → Arts |
| `&?bp4` | Buster → Unselected (priority) → Frontline 1 NP |
| `&?pp4` | Unselected → Unselected → Frontline 1 NP |
| `&?ppp` | All three slots by priority |

The standard `,` separates turns and `,#,` separates waves. Existing skills go before `&`—for example, `ab&b5a` uses skills `a` and `b` and then executes the fixed attack. Uppercase `B/A/Q` inputs are normalized to lowercase. Affinity prefixes such as `WB` or `RB` are not supported in this syntax.

A fixed attack must be the last command of that turn. Combining it with standard NP commands or `n1/n2` in the same turn stops with a parse error. Specifying the same NP twice, or commands shorter or longer than three slots, are also rejected. A leading no-op `0` is ignored as usual.

## Selection rules and limits

- FGA inspects the current card screen, decides all three required cards first, and then taps them. Within the same card color, it prefers **Weak → Normal → Resist**, breaking ties from left to right. Already-selected face cards are excluded.
- Unusable face cards (such as stunned cards) are excluded. If fallback is off and a specified color is missing, if usable face cards are insufficient even with fallback on, or if a normal card's type cannot be read, **FGA stops without tapping even the first card**. It also stops if the frontline slot of a specified NP is empty.
- Plain direct attacks do not apply card or Servant priority. When fallback is on, only missing face-card slots follow the current wave and the active setting 1/2 card and Servant priorities. Neither mode applies Brave Chain selection, card rearrangement, or extra automatic NPs. Subsequent turns behave normally.
- Existing automatic skill use and shared card-shuffle settings remain active. The specified card types are searched in the actual hand after skills and shuffles; if you want to keep a skill-created hand, check your shared shuffle settings too.
- **This feature does not add new checks for NP gauge, NP seal, or NP availability.** Just like standard NP commands, make sure skills and NP charge are configured so the target NP is ready.
- Fixed attacks work in both first and second start-condition commands, and `.fga` copy, export, and import preserve the command string. Older `0.3.0` APKs cannot parse `&` commands.

## Fill missing and unselected cards by priority — 1.6.7-beta.1, Unselected 1.7.1-beta.6

When checked, FGA first reserves every specified card color that exists in the hand across all three slots, picking **Weak → Normal → Resist** and left to right on ties. If multiple slots ask for the same color, earlier slots reserve the best matching cards first. Next, all remaining empty face-card slots (**Unselected** slots and slots missing their specified color) are filled in slot order at once using the standard card and Servant priority on the remaining hand. An **Unselected** slot will never steal a card needed by a later color-specific slot. NP slots and reserved cards do not swap positions.

For example, with `&?b4a` (Buster → Frontline 1 NP → Arts), 1 Arts and 4 Quick cards in hand, and priority Buster > Arts > Quick, the result is **Quick → Frontline 1 NP → Arts**. Because the trailing Arts is reserved first, the earlier fallback slot does not take it. On the same hand, `&b4a` stops without tapping.

With `&?pa4` and only 1 Arts in hand, that Arts is reserved in the second slot and the first slot takes the highest-priority card from the rest. If there are not enough usable cards, FGA stops without tapping.

This checkbox is saved only for that turn. It is preserved across editing, copying, `.fga` export/import, and both setting 1 and setting 2. Unchecking it and tapping **Save** removes `?`, and a newly opened Attack dialog starts with it unchecked. **Dismiss** discards changes. Earlier app versions cannot parse `&?` or `p` syntax, so update the app where these commands will run.

Unreadable normal cards, incomplete or duplicate hands, insufficient usable cards, and empty frontline slots for a specified NP are not replaced by fallback. If `"Could not prepare the fixed attack … No cards were selected. Check the hand and deployed Servants."` appears, inspect the hand and frontline Servants and adjust the specified colors or fallback setting. Invalid command syntax must be fixed in the command itself. NP availability is still not newly read.

Whenever at least one slot is filled by priority (either **Unselected** or missing color), `FGA.DirectCards` logs the requested command and final three-card order once in the diagnostic log.
