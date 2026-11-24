# Class Score +62 and Grand Score +16

## Settings and passing conditions

Turn on **Require Class score +62** and **Require Grand score +16** independently under **Battle Configs → Support → Preferred Servants**. When both toggles are enabled, both conditions must be met. Both toggles are shown only when at least one Preferred Servant is chosen, and the Grand toggle also needs **Grand Servant** turned on, because the game shows +16 only for Grand servants. A toggle that becomes hidden is also stored off; when it reappears it is off and the earlier value is not restored. Both toggles are included in copy, export, and import.

| Actual display | Class only | Grand only | Both enabled |
|---|---|---|---|
| +62/+16 | Pass | Pass | Pass |
| +0/+16 | Fail | Pass | Fail |
| +62/+0 | Pass | Fail | Fail |
| +16/+62 | Fail | Fail | Fail |
| Ordinary +62 | Pass | Fail | Fail |
| Ordinary +0, +23, +43 | Fail | Fail | Fail |
| Format or value undetermined | Must confirm Class `62` | Must confirm special display and Grand `16` | Must satisfy both conditions |

**When the Grand condition is on, `+16` in the compound Class/Grand display is strictly required.** When only Class is on, both ordinary `+62` and compound `+62/+16` are accepted. In a compound display, the value before `/` is checked as Class and the value after `/` as Grand.

Other conditions such as normal skills, NP, and CEs must also pass, and Append Skill checks always run last. This filter applies to **Preferred** selection. Because setting **Fallback** to **First** bypasses preferred conditions, set **Fallback** to **Manual** if you want the condition strictly enforced.

On the JP server score display, even if OCR reads the `+` sign as `4`, `+62/+16` is still recognized properly as long as another read or the `+16` template confirms Grand `16`, or even if some of the multiple OCR reads only capture the Grand score portion. Even when the screen is dim and digit OCR returns empty, it is still recognized properly if the `+16` template is confirmed through the last digit. If readings remain unclear or conflicting, the candidate is treated as undetermined and skipped as before. Score reading behavior on the KR server is unchanged.

To have the support list itself show only matching supports while log collection runs, see [Support list filter](Support-List-Filter-EN.md).
