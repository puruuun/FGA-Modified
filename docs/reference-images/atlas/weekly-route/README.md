# Weekly route navigation fixtures

The map and banner images are derived directly from official Atlas Academy image URLs. `sources.json` records
the source URL, source SHA-256, display dimensions, prepared image path and SHA-256. They are QA inputs only;
the APK downloads chapter art at runtime. No Chaldea code or processed data is used.

Reproduce/verify with `tools/device-qa/prepare_weekly_navigation_assets.py` (OpenCV + numpy). Banner bodies are
the opaque rectangle (14,36,420,63) of the 450×125 original, resized by 1.25 with INTER_AREA. The former crop
included transparent margins that became black after decoding and caused false matches.

The `weeklyRoute` localization cases compare projected Atlas spot coordinates with independently observed
icon centres/bases on the owner frames, rounded to approximately 5 pixels. Tolerance is 12 pixels at 720p.
Expected coordinates are in cases/weeklyRoute.json, rather than generated from the estimated transform.
The masks for feature extraction avoid fixed UI. Wrong-map and chapter-list negative cases must be rejected.

The owner frame normalization and navigation-template crops are reproducible using normalize_weekly_route.py
and cut_weekly_templates.py. The previously published 8 quest-panel cases and their expectations remain unchanged.
