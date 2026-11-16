# Support Append Skill Check

Selects supports by checking whether the 5 Append Skill slots of your Preferred Servant are **level 10**. Setting a slot to `10` requires level 10, while `x` skips checking that slot. This does not target specific levels from `1–9`.

## How to configure

1. Under **Support selection → Preferred Servants** in your battle config, choose your Servant and configure the 3 normal **Max Skills** slots.
2. Turn on **Check Append Skills** (off by default).
3. From left to right, set Append Skill slots 1–5 to `10` or `x`.

For example, `x / 10 / x / x / 10` passes if only slots 2 and 5 are level 10. Locked slots do not count as level 10. Turning the toggle off preserves your slot selections, and they are included in copy, export, and import. If all 5 slots are `x` or no Preferred Servant is selected, FGA does not switch to the Append Skill display.

## Selection order

1. In the normal skill display, FGA checks Servant, normal skills, score, NP level, CE, and friend conditions first. If any fails, the Append check is skipped.
2. When a candidate passes those conditions, FGA taps the top **Skill Switch** button to inspect the 5 Append Skill slots.
3. After checking, it taps the button twice to restore the normal skill display.
4. Passing candidates are selected; failing candidates are skipped and FGA looks for another Servant.

If FGA cannot confirm the display switch or restoration, it stops without selecting a support (`"Could not confirm the support skill display. Restore the normal skill display and retry."`). To ensure conditions are strictly enforced, do not set **Fallback** to **First**, as it can bypass preferred conditions.

## Notes

- Reads digits and display icons using templates from KR and JP screens; blurry or low-resolution screens may fail to read and skip candidates.
- Screen-switching speed on real devices may vary depending on your environment.
