# Korean NP labels captured on 2026-10-17

38 anonymous 448x52 grayscale name-panel crops from 20 normal/append screenshots. The game viewport was 1920x1080, normalized to the production 1280x720 matching viewport. Only servant/NP names and the Lv. label are included; player names stay outside these crops. Normal/append pairs intentionally preserve the same NP pixels.

Manual labels cover NP levels 1 through 5 across 19 support candidates collected by scrolling and refreshing. Merlin's white Lv.1 marker matched the existing template at .732, below the previous .75 recovery proposal threshold. KR recovery now proposes candidates at .72 and still requires at least two exact OCR reads with confidence >=85, with conflicting levels rejected. JP proposal thresholds are unchanged.

Run `tools/validate_support_np.py` with the pinned vision dependencies. It checks exact labels and rejects cropped labels without the Lv. marker or without the digit. Full source frames are also replayed locally through the Android production readers and selectors.
