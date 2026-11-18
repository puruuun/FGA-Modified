# Hakuno Shuffle

**The in-game skill use confirmation must be OFF.** The Servant face-card check turns on automatically whenever Hakuno Shuffle is present in your command, so you do not need to enable it separately. This repeats the NP-costing first skill that shuffles command cards until the configured condition is met.

In the Skill Maker, tap **first skill → Hakuno Shuffle** on frontline Servant 1–3. Set **Min shuffle presses** (default `0`), **Max shuffle presses** (`1–30`, default `10`), the card color per frontline slot (`All / B / A / Q`) and required card count (`1–5` cards), and **Stop the battle if the condition is not met**. Each condition row displays `Slot [1] · [All] · [3] or more cards` with **or more cards** after the count. You can add and remove condition rows. **Counts non-stunned command cards. Stops when any condition is met.** **All** counts every color as before, while selecting a specific color counts only cards of that color. Cards whose color cannot be read are excluded from color-specific conditions.

The raw command syntax is `<skill>H{[min-]max:slot[color]count[|slot[color]count…][!]}`. Only the first skill of each Servant (`a`, `d`, or `g`) is used, followed immediately by an uppercase `H` and `{`. Lowercase `h` is frontline Servant 3's second skill, so `ah` means two skills, and `ah{…}` or `a{…}` is rejected as a syntax error. Colors are `b` (Buster), `a` (Arts), and `q` (Quick); input is case-insensitive and saved in lowercase. **No color = All colors.** Existing `13` and `22|31!` conditions continue to work unchanged.

- `aH{10-30:1b2|1q2}`: Minimum 10, maximum 30 presses. Stops shuffling when frontline Servant 1 has 2+ Buster cards **or** 2+ Quick cards.
- `aH{8:13|2a2}`: Maximum 8 presses; stops when frontline Servant 1 has 3+ cards of any color **or** Servant 2 has 2+ Arts cards.
- `aH{8:13|22}`: Maximum 8 presses; ends when frontline Servant 1 has 3+ cards **or** Servant 2 has 2+ cards. Uses 0 presses if the initial hand already qualifies.
- `aH{2-8:13|22!}`: Presses at least twice before checking, and stops the battle if the condition is still unmet at 8 presses, if NP runs out, or if card reading fails.
- `dH{30:23}`: Uses frontline Servant 2's first skill up to 30 times until frontline Servant 2 has 3+ command cards.

If an NP shortage dialog appears, FGA closes it and returns to the battle screen. For card reading, FGA retries the same hand twice more on failure before stopping. Without `!`, the remaining command continues even if the condition is unmet. Failing to return to the battle screen always stops the battle.

Right after pressing the first skill, FGA checks for the NP shortage dialog. It waits 0.5 seconds before closing only when that dialog is found; otherwise it immediately sends the usual skill-animation skip tap. It also waits 0.5 seconds after closing the NP shortage dialog and confirming the battle screen, and another 0.5 seconds after returning from the hand screen to the battle screen.

On the turn this token is used, FGA will not use a Mystic Code shuffle afterward. Subsequent face-card attacks follow your existing Card Priority, Brave Chains, and NP settings. Check the `HakunoShuffle` log for results, press counts, and per-condition card counts (`counts=1b:2,1q:1`).
