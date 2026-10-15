# Bond gain tracking and MAX stop — 0.9.2

Open a script’s editor and find **Bond points between Command start conditions and Card shuffle**. Tracking and MAX stop are independent, default off, support KR/JP, and are saved per script with copy/export/import. Re-enable them in each script after upgrading from 0.8.0; old launcher exceptions are not migrated into the new numbering.

- **Track bond point gains** sums base plus bonus for **result characters 1–5, left to right**. `+4,748(+2,660)` contributes `7,408`.
- **Stop when bond MAX is confirmed** stops for characters whose ignore checkbox is unchecked.
- **Ignore MAX for checked characters** is a horizontal row with labels above the checkboxes. Checking character 2 ignores its MAX and continues farming. New settings default to all unchecked; gain tracking is independent. Existing 0.9.0 scripts keep their stop behavior, so old watched characters now appear unchecked. The server notice and long description below these settings have been removed.
- A watched MAX stops at the cleared quest’s bond result screen, before another quest starts. A new script session resets the totals.

There is no pre-battle party read, support detection or mapping to original party positions 1–6. Starting at party confirmation, in battle or on the result screen does not require a captured party. The user maintains consistent result-column membership. If characters change during a session, their gains remain combined by column.

## Reading and missing data

After detecting the bond result, wait **1.2 seconds before capturing the first reading** so the gain animation can finish. Faster wait settings do not shorten this delay. Strong OCR readings must agree, as must successive fresh frames. Each quest/column contributes at most once; unreadable columns never shift the others. MAX on a normal result is a measured zero. Hidden or unreadable gains are missing, so a total with missing runs may understate the actual amount.

Level-up popups skip obscured background gains/MAX. Only the reached level to the right of the arrow is read. A confirmed result-column LEVEL UP marker and reached level of at least 10 apply the watched MAX rule. Levels below 10 continue. Unknown level/column defers to normal MAX next quest, which can allow one extra quest. Gems and NEXT are not read.

At exit, tracking displays five character totals with read/missing counts. Enabled controls advance results one screen at a time. MAX-only mode skips gain OCR and the tracking summary; both off retain the legacy result flow.

## Validation

`tools/verify_bond_images.py` uses shipped templates, source coordinates and bundled OCR data with supplied full-frame references: 3 normal results (6 gains, 9 MAX), 5 level-up popups (7/10/11/12/16), 3 party screens that must be ignored, and 2 blank controls. It requires OpenCV, Pillow and tesserocr. Unit tests cover result-only startup, independent column totals, duplicate prevention, five watched MAX targets and per-script persistence/copy/export. Desktop tests do not establish live Android/FGO gesture behavior. Small parties and simultaneous level-ups need device verification.

Teapot/Storm Pod recognition remains based on the visible item icon. Control defaults off. Opt in under **More options → Additional features → Enable Teapot/Storm Pod control**. [한국어 안내](Bond-Tracking-KR.md).
