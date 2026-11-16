# Support NP Level Check

## How to configure

1. Install `FGA-mod-0.6.0-arm.apk` from [Stable release 0.6.0](https://github.com/puruuun/FGA-Modified/releases/tag/v0.6.0). It uses the same package ID and signature as existing modified builds.
2. In your battle config, set support **Selection Mode** to **Preferred** and open the **Preferred Servants** section.
3. Turn on **Check support NP level**. This setting appears above the Append Skill settings.
4. Set **Minimum NP level** from `2` to `5` (`Lv.2 or higher` through `Lv.5 or higher`). Choosing `Lv.3 or higher` accepts `Lv.3`, `Lv.4`, and `Lv.5`, and rejects `Lv.1` and `Lv.2`.

The toggle starts off by default, and the initial minimum level is `Lv.5 or higher`. Turning the toggle off preserves your chosen minimum level. When importing existing configs, the toggle starts off. It is included in config copy, export, and import.

You can combine this with Preferred Servants, Preferred CEs, and Friend names, or use the NP level condition on its own. Friend-only restriction follows the existing **Friends Only** setting.

This filter applies to **Preferred** selection mode. If **Fallback** is set to **First**, it can bypass your preferred conditions; set **Fallback** to **Manual** if you want the condition strictly enforced.

## Execution order

When a candidate row is found, FGA first checks ordinary conditions: Servant, normal skills, score, CE, friend name, and NP level. If any of these fail, it moves on to the next candidate or page without checking Append Skills on that candidate.

Only for candidates that pass all ordinary conditions does FGA check Append Skills last. When Append Skill checking is enabled, it switches the skill display, reads the Append Skills, restores normal skill display, and then selects the support. If Append Skills fail, it restores the display and moves to the ordinary conditions of the next candidate. If the screen starts in Append display mode, the existing behavior of restoring normal skill display first before checking normal skills is preserved.

Score +62 and NP level checks run before Append Skill checks. `0.5.1` preserves this order and improves retry recognition when NP level reading fails.

## Recognition method

- Uses the existing support row finder to locate a candidate, then searches for `Lv.` inside the NP name region without fixing the horizontal coordinate of the number.
- Crops only the detected `Lv.` and digit, and reads it using the existing Tesseract engine and bundled English data. The NP-level OCR config is separate from the Gift Box OCR config.
- If the initial read succeeds, it is used immediately. Only on failure does FGA search for the reference `Lv.` template across multiple sizes and re-read using preprocessing with different scale factors and brightness thresholds.
- Retry recovery only passes if two or more high-confidence readings agree on the same level. If another level is also read with high confidence, the location is ambiguous, or the digit is clipped, the candidate is rejected.
- The confirmed `Lv.1`–`Lv.5` is compared against the configured minimum level. If it remains uncertain, FGA moves on to the next support.

No new OCR engine, server, model, or Servant-specific exceptions were added. Existing KR and English reference images are kept, and the JP server uses the existing English defaults. No changes are needed to Append Skill, score, or config file formats.

## 1.6.2 — Removal of Lv.1 or higher

Because every support satisfies `Lv.1 or higher`, it was removed from the list. If a previously saved config has `1`, **Check support NP level** is treated as off. Turning it back on selects `Lv.5 or higher` by default and lets you choose `Lv.2–5`. When importing from Chaldea, if a friend's NP level is `1`, FGA turns off NP level checking and keeps your saved minimum; if it is `2–5`, it turns checking on and sets that level as the minimum.

Only the title is shown on the **Check support NP level** setting. Refer to this guide for detailed reading behavior.
