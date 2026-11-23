# Support NP Level Check

## How to configure

1. Under **Battle Configs → Support**, set **Selection Mode** to **Preferred** and open the **Preferred Servants** section.
2. Turn on **Check support NP level**. It is shown only when at least one Preferred Servant is chosen; Preferred CEs alone do not show it. This setting appears above the Append Skill settings.
3. Set **Minimum NP level** from `2` to `5` (`Lv.2 or higher` through `Lv.5 or higher`; `Lv.1 or higher` is omitted because every support satisfies it). Choosing `Lv.3 or higher` accepts `Lv.3`, `Lv.4`, and `Lv.5`, and rejects `Lv.1` and `Lv.2`.

The toggle starts off by default, and the initial minimum level is `Lv.5 or higher`. Turning the toggle off preserves your chosen minimum level. When importing a legacy config with `1`, the toggle starts off and selects `Lv.5 or higher` by default when re-enabled. When importing from Chaldea, a friend's NP level of `1` turns off NP level checking while keeping your saved minimum, and `2–5` turns checking on with that level. It is included in config copy, export, and import.

You can combine this with Preferred Servants, Preferred CEs, and Friend names. If you clear all Preferred Servants, the option is hidden and the stored check is turned off; choosing a servant again shows it off, and the earlier value is not restored. Friend-only restriction follows the existing **Friends Only** setting.

This filter applies to **Preferred** selection mode. If **Fallback** is set to **First**, it can bypass your preferred conditions; set **Fallback** to **Manual** if you want the condition strictly enforced.

## Execution order

When a candidate row is found, FGA first checks ordinary conditions: Servant, normal skills, score, CE, friend name, and NP level. If any of these fail, it moves on to the next candidate or page without checking Append Skills on that candidate.

Only for candidates that pass all ordinary conditions does FGA check Append Skills last. When Append Skill checking is enabled, it switches the skill display, reads the Append Skills, restores normal skill display, and then selects the support. If Append Skills fail, it restores the display and moves to the ordinary conditions of the next candidate. If the screen starts in Append display mode, the existing behavior of restoring normal skill display first before checking normal skills is preserved.

Score +62 and NP level checks run before Append Skill checks.

## Recognition method

- Uses the existing support row finder to locate a candidate, then searches for `Lv.` inside the NP name region without fixing the horizontal coordinate of the number.
- Crops only the detected `Lv.` and digit, and reads it using the existing Tesseract engine and bundled English data. The NP-level OCR config is separate from the Gift Box OCR config.
- If the initial read succeeds, it is used immediately. Only on failure does FGA search for the reference `Lv.` template across multiple sizes and re-read using preprocessing with different scale factors and brightness thresholds.
- Retry recovery only passes if two or more high-confidence readings agree on the same level. If another level is also read with high confidence, the location is ambiguous, or the digit is clipped, the candidate is rejected.
- The confirmed `Lv.1`–`Lv.5` is compared against the configured minimum level. If it remains uncertain, FGA moves on to the next support.

Existing KR and English reference images are used, and the JP server uses the English defaults.
