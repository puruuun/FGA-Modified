# Added Features Fine Tune

Under **More options → Advanced → Added features fine tune**, you can make small adjustments to fixed wait times and template similarity thresholds for each added feature. At the default values, behavior is completely unchanged. Changed values take effect **from the next script run** and do not affect any currently running script.

## Sections

| Section | Targets |
|---|---|
| Weekly mission automation | Route navigation, banner/button matching, list scrollbar |
| FP auto-sell and banner return | Sell screen waits, scrollbar/lock matching, summon waits |
| Skill / append level | Selection, enhance, cancel, and skip waits; label, dialog, and panel outline matching |
| CE auto-enhance | Enhancement screen waits, recommended-selection matching |
| Storm pod / teapot and quest launch | Post-tap settle time, re-check interval, item state/icon matching |
| Bond tracking and stop | Bond screen waits, bond image matching |
| Added support reading | NP name/level, score, and skill display matching |

Hakuno Shuffle and Card Priority are not included.

## Items

| Item | Range | Default | Description |
|---|---|---|---|
| Delay multiplier | 50–300% (10% steps) | 100% | Multiplies this section's fixed waits. Waits where the global Wait Multiplier already applied use both multipliers together. |
| Similarity offset | -0.05–+0.05 | 0.00 | Added to template matching thresholds, clamped to 0.40–0.99. Negative values allow weaker matches. |
| Settle after item-control tap | 50–1000ms | 100ms | Wait after tapping a Storm Pod or Teapot control. Only the section multiplier applies. |
| Item-control poll interval | 100–2000ms | 300ms | Interval between re-reading item state. Retry count remains 10. |

## What Stays Unchanged

Safety check intervals and time limits, OCR brightness thresholds, color rules, ink ratios, score margins, and coordinates are not changed.

## Resetting

**Reset section** in each section restores that section only; **Reset all sections** restores every section to defaults.

## Tips

- If recognition fails often, try lowering **Similarity offset** in steps of -0.01 to -0.02. Raise it back if false matches occur.
- On devices with slower screen transitions, try raising **Delay multiplier** first.
