# JP live score samples (2026-11-17)

- **Source:** 24 fully visible support-list rows from 12 JP captures that the owner took on MuMu at 2400×1080 (20:9) after #431.
- **Not committed:** the full captures, because they show other players' names.
  - Only the 60×30 score crops are here.
  - The planner keeps the full screens locally as device fixtures.

**How the crops were cut**, the same way as `support-score-holdout`:
1. The capture is resized with OpenCV `INTER_AREA` to height 720, keeping the aspect ratio.
2. The product score number region is cut out:
   - x comes from the wide support offset: 1199 for 2400×1080;
   - y is the row bound plus 155;
   - the size is 60×30.
3. The method was checked byte for byte against an existing holdout crop.

**QA cases:** `cases/support.json` has the `live-20261117-*` cases.
- The crop is placed at the standard score region (`region720` 1018,355, row 200), as for the holdout.
- Each case checks the selection `policy` from the ground truth in `labels.json`:
  - classOnly is Class 62;
  - grandOnly is Grand 16;
  - bothScores is both.
- The planner labelled each row by eye from the full captures.

**Use:** these images are regression samples only. They are never templates or calibration inputs.
