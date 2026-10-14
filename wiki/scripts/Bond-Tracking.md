# Bond gain tracking and MAX stop — 0.8.0

In the Play launcher, use **Bond points**. Choices are saved separately per server, support KR/JP, and default to off.

- **Track bond point gains** adds the displayed base and bonus per original party position for this script session. `+4,748(+2,660)` contributes `7,408`.
- **Stop when a party member reaches MAX** stops before the next quest when a watched position is capped. This works independently of gain tracking.
- **MAX stop exceptions** excludes checked positions from stopping. Numbers refer to the original six party positions; exceptions do not disable gain tracking.

The support banner is read before Quest Start. With support in position 3, the five result columns map to party positions 1, 2, 4, 5 and 6. Death, backline entry and Order Change do not alter this mapping. Totals belong to positions, so restart the script to keep separate character totals when changing the party.

Normal results recognize MAX separately from gains. Complete base/bonus pairs need two strong agreeing OCR readings, no conflicting strong value, and agreement across fresh frames. Revisited results do not count twice.

On a level-up popup, hidden background gains and MAX are ignored. Only the reached level to the right of the arrow is read. Reaching 10 or above applies the MAX stop condition if a unique LEVEL UP marker confirms the result column; 9 or below does not. If level/position is uncertain, the popup is skipped and normal MAX is checked on the next quest, as requested. This may allow one additional quest.

The exit screen shows each position's **total read**, read quest count and missing quest count. Unreadable or obscured gains are missing, not invented zero. With missing results the total may be lower than the actual full total. Gem/current-level tracking and NEXT reading are excluded.

Start from the quest list or support selection so the initial party can be checked. An unknown party stops with an explanation rather than assigning results to guessed positions. Fast repeats reuse the captured party mapping. Enabled bond controls advance results one screen at a time; both off retain the existing behavior.

`tools/verify_bond_images.py` uses shipped templates, source coordinates and bundled OCR data with the supplied full-frame references: 3 normal results (6 gains, 9 MAX), 5 level-up popups (7/10/11/12/16), 3 party screens and 2 blank controls. It requires OpenCV, Pillow and tesserocr. Desktop and unit tests do not establish Android/FGO gesture behavior. Small parties and simultaneous level-ups need additional device verification.

Teapot/Storm Pod behavior remains based on the visible item icon. [한국어 안내](Bond-Tracking-KR.md).
