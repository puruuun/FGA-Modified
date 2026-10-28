"""Cut #44 templates from normalized owner game-area frames (720p, aspect kept)."""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
CUTS = {
    "Jp": {
        "weekly_gate": (2, (1045, 378, 290, 66)),
        "weekly_gate_title": (3, (1220, 4, 374, 79)),
        "weekly_daily": (3, (1020, 550, 455, 55)),
        "weekly_daily_title": (8, (1270, 4, 325, 79)),
        "weekly_daily_quest": (8, (1097, 350, 200, 12)),
    },
    "Kr": {
        "weekly_gate": (9, (714, 296, 280, 66)),
        "weekly_gate_title": (10, (938, 4, 340, 77)),
        "weekly_daily": (10, (712, 364, 414, 65)),
        "weekly_daily_title": (11, (996, 4, 282, 79)),
    },
}

if __name__ == "__main__":
    for server, cuts in CUTS.items():
        for name, (number, (x, y, w, h)) in cuts.items():
            frame = Image.open(ROOT / f"docs/reference-images/{server}/weekly-vault/{number:02}.webp").convert("RGB")
            target = "En" if name == "weekly_daily_quest" else server
            frame.crop((x, y, x + w, y + h)).save(ROOT / f"app/src/main/assets/{target}/{name}.png")
            verify = frame.copy()
            ImageDraw.Draw(verify).rectangle((x, y, x + w, y + h), outline="lime", width=2)
            out = ROOT / f"build/vault-cuts/{server}-{name}.png"
            out.parent.mkdir(parents=True, exist_ok=True)
            verify.save(out)
            print(server, name, number, (x, y, w, h))
