# JP support score regression crops

These anonymous 55×30 crops come from the numeric score region of actual BlueStacks
JP support screens, normalized from 1920×1080 to FGA's 1280×720 matching space.
No account, friend name or servant portrait is included.

`manifest.json` labels each crop independently of OCR. `grand16` is true only for
a complete compound `Class/+16` field. `compactClass62` is true only for a complete
`+62/Grand` field. Ordinary `+62`, `+51`, `+9`, Grand `+0`, obscured/empty regions
and clipped digits are negative examples for these **compound template fallbacks**.
They are not a test of the unchanged ordinary-score OCR/legacy matcher.

The six Grand templates use crops 000, 037, 027 (two-digit Class font) and
014, 047, 080 (larger one-digit Class font). The three compact Class templates
use 000, 037, 027. Other crops are held-out examples; template source examples
are included so accidental asset corruption is also detected.

Each Grand template includes `/+16`, and its final `6` must match independently
inside the same candidate. This prevents the common `/+1` prefix from dominating
the score for a different final digit. Cropped or uncertain values stay unknown.

Run `python tools/validate_support_scores.py` with numpy and
`opencv-python-headless==4.11.0.86` (the app uses OpenCV 4.11.0).
The test evaluates 101 crops / 202 field decisions, including Grand `+0`, ordinary
displays and incomplete images. This is JP screenshot coverage, not an assertion
that every server, future UI font or device aspect ratio has been validated.

The first reproduced failure was `+56/+16`: the original whole-pair OCR returned
`56/416` (confidence 33), `56/+16` (60), `+6` (50), `56/+16` (55) with the bundled
English trained data and Tesseract 5.5.1. Every result was below the existing 75/85
acceptance thresholds. Those thresholds are unchanged by the template recovery.
