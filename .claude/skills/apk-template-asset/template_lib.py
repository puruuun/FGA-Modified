"""Shared helpers of the apk-template-asset scripts: font rendering, sprite compositing, scoring.

render() and shift_scores() follow upstream FGA v3073's render_template.py, so a score here means
what it means in that script and in the app (grayscale TM_CCOEFF_NORMED at 720p).
"""

import os
import sys

try:
    import cv2
    import numpy as np
    from PIL import Image, ImageDraw, ImageFont
except ImportError:
    sys.exit("Pillow, numpy and opencv-python-headless are required")

RENDER_SCALE = 4
SHIFTS = [(0, 0), (0.5, 0), (0, 0.5), (0.5, 0.5)]
# NGUI UI to 720p, measured on KR 8.1.0: the best match of every atlas sprite was at this scale
DEFAULT_SPRITE_SCALE = 1.25
# Measured on KR 8.1.0 against 270 screenshots: drawing via a 1080p screen scores 0.01-0.03 above a single INTER_AREA step
DEFAULT_DEVICE_HEIGHT = 1080

# Keeps a __pycache__ out of the other skill's folder when crop_template is imported from here
sys.dont_write_bytecode = True


def parse_color(value: str) -> tuple[int, int, int]:
    value = value.lstrip("#")
    return int(value[0:2], 16), int(value[2:4], 16), int(value[4:6], 16)


def load_rgb(path: str) -> np.ndarray:
    return np.array(Image.open(path).convert("RGB"))


def median_color(rgb: np.ndarray) -> tuple[int, int, int]:
    return tuple(int(v) for v in np.median(rgb.reshape(-1, 3), axis=0))


def render(font_path: str, text: str, color, size: float, stroke: float, background) -> np.ndarray:
    """The text in RGB at 720p, rendered large and shrunk like a screenshot of a high-res device.

    The result is cropped to the glyphs, minus their anti-aliased edge, so it holds no pixels
    from beside the label.
    """
    font = ImageFont.truetype(font_path, round(size * RENDER_SCALE))
    stroke_width = round(stroke * RENDER_SCALE)
    pad = 8 * RENDER_SCALE
    left, top, right, bottom = map(int, font.getbbox(text, stroke_width=stroke_width))
    dimensions = (right - left + 2 * pad, bottom - top + 2 * pad)
    origin = (pad - left, pad - top)

    image = Image.new("RGB", dimensions, background)
    ImageDraw.Draw(image).text(origin, text, font=font, fill=color,
                               stroke_width=stroke_width, stroke_fill=(30, 30, 30))
    mask = Image.new("L", dimensions, 0)
    ImageDraw.Draw(mask).text(origin, text, font=font, fill=255,
                              stroke_width=stroke_width, stroke_fill=255)

    small = (dimensions[0] // RENDER_SCALE, dimensions[1] // RENDER_SCALE)
    rgb = cv2.resize(np.array(image), small, interpolation=cv2.INTER_AREA)
    ink = cv2.resize(np.array(mask), small, interpolation=cv2.INTER_AREA)
    ys, xs = np.where(ink > 64)
    return rgb[ys.min() + 2:ys.max() - 1, xs.min() + 1:xs.max()]


def sprite_on_background(rgba: np.ndarray, background) -> np.ndarray:
    """Composite a sprite's alpha over a flat color, as RGB, so it can be compared with a screenshot."""
    alpha = rgba[:, :, 3:4].astype(np.float32) / 255
    rgb = rgba[:, :, :3].astype(np.float32) * alpha + np.array(background, np.float32) * (1 - alpha)
    return np.clip(rgb + 0.5, 0, 255).astype(np.uint8)


def scale_sprite(rgba: np.ndarray, scale: float, device_height: int = DEFAULT_DEVICE_HEIGHT) -> np.ndarray:
    """A sprite at 720p scale `scale`, the way a screenshot of a device with a `device_height` screen shows it.

    The game draws the sprite with bilinear filtering on a device-sized screen and FGA then shrinks
    the screenshot with INTER_AREA. A single INTER_AREA enlargement acts like nearest neighbour and
    makes a blockier draft than any screenshot (measured: 0.01-0.03 lower scores on KR).
    device_height 720 keeps that single step.
    """
    h, w = rgba.shape[:2]
    size = (max(1, round(w * scale)), max(1, round(h * scale)))
    if device_height == 720:
        return cv2.resize(rgba, size, interpolation=cv2.INTER_AREA)
    device = scale * device_height / 720
    big = cv2.resize(rgba, (max(1, round(w * device)), max(1, round(h * device))), interpolation=cv2.INTER_LINEAR)
    return cv2.resize(big, size, interpolation=cv2.INTER_AREA)


def composite_layers(layers: list[np.ndarray], offsets: list[tuple[int, int]]) -> np.ndarray:
    """RGBA layers alpha-composited bottom to top on the union of their boxes.

    offsets[i] is where layer i's top-left sits relative to layer 0's, in pixels.
    """
    x0 = min(dx for dx, _ in offsets)
    y0 = min(dy for _, dy in offsets)
    x1 = max(dx + layer.shape[1] for layer, (dx, _) in zip(layers, offsets))
    y1 = max(dy + layer.shape[0] for layer, (_, dy) in zip(layers, offsets))
    canvas = np.zeros((y1 - y0, x1 - x0, 4), np.float32)
    for layer, (dx, dy) in zip(layers, offsets):
        top = layer.astype(np.float32)
        x, y = dx - x0, dy - y0
        below = canvas[y:y + top.shape[0], x:x + top.shape[1]]
        top_a = top[:, :, 3:4] / 255
        below_a = below[:, :, 3:4] / 255
        out_a = top_a + below_a * (1 - top_a)
        safe = np.where(out_a > 0, out_a, 1)
        rgb = (top[:, :, :3] * top_a + below[:, :, :3] * below_a * (1 - top_a)) / safe
        below[:, :, :3] = rgb
        below[:, :, 3:4] = out_a * 255
    return np.clip(canvas + 0.5, 0, 255).astype(np.uint8)


def locate(container_gray: np.ndarray, needle_gray: np.ndarray) -> tuple[float, int, int] | None:
    """(score, x, y) of the needle's top-left relative to the container's; x and y are negative
    when the needle is the larger image and the container sits inside it. None for a flat image."""
    ch, cw = container_gray.shape
    nh, nw = needle_gray.shape
    if nw <= cw and nh <= ch:
        outer, inner, sign = container_gray, needle_gray, 1
    elif cw <= nw and ch <= nh:
        outer, inner, sign = needle_gray, container_gray, -1
    else:
        return None
    if outer.std() < 3 or inner.std() < 3:
        return None
    _, score, _, (x, y) = cv2.minMaxLoc(cv2.matchTemplate(outer, inner, cv2.TM_CCOEFF_NORMED))
    return float(score), sign * x, sign * y


def arrange(image_a: np.ndarray, image_b: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """(container, needle): the smaller grayscale image is searched inside the larger.

    When one is wider and the other taller, neither contains the other; the container then grows
    with its own edge pixels so the whole needle can still be placed.
    """
    (ah, aw), (bh, bw) = image_a.shape[:2], image_b.shape[:2]
    if bw <= aw and bh <= ah:
        return image_a, image_b
    if aw <= bw and ah <= bh:
        return image_b, image_a
    pad_w, pad_h = max(0, bw - aw), max(0, bh - ah)
    grown = cv2.copyMakeBorder(image_a, pad_h // 2, pad_h - pad_h // 2, pad_w // 2, pad_w - pad_w // 2,
                               cv2.BORDER_REPLICATE)
    return grown, image_b


def to_gray(rgb: np.ndarray) -> np.ndarray:
    return cv2.cvtColor(rgb, cv2.COLOR_RGB2GRAY)


def best_score(image_a_gray: np.ndarray, image_b_gray: np.ndarray) -> float | None:
    """Best TM_CCOEFF_NORMED of the smaller image inside the larger; None for a flat image."""
    container, needle = arrange(image_a_gray, image_b_gray)
    if needle.std() < 3 or container.std() < 3:
        return None
    return float(cv2.minMaxLoc(cv2.matchTemplate(container, needle, cv2.TM_CCOEFF_NORMED))[1])


def shift_scores(image_a: np.ndarray, image_b: np.ndarray) -> list[float] | None:
    """Best score at each of SHIFTS, the way a screenshot at another resolution would land.

    The container gets a 1px edge margin first so it can move by half a pixel without losing
    its border. Needs RGB inputs. None when either image is flat.
    """
    container, needle = arrange(to_gray(image_a), to_gray(image_b))
    if needle.std() < 3 or container.std() < 3:
        return None
    container = cv2.copyMakeBorder(container, 1, 1, 1, 1, cv2.BORDER_REPLICATE).astype(np.float32)
    height, width = container.shape
    result = []
    for dx, dy in SHIFTS:
        shifted = cv2.warpAffine(container, np.float32([[1, 0, dx], [0, 1, dy]]), (width, height))
        inner = shifted[1:-1, 1:-1].astype(np.uint8)
        match = cv2.matchTemplate(inner, needle, cv2.TM_CCOEFF_NORMED)
        result.append(round(float(cv2.minMaxLoc(match)[1]), 3))
    return result


def crop_template_module():
    """crop_template.py of the extract-template-asset skill, so both skills resolve the same pixels."""
    here = os.path.dirname(os.path.abspath(__file__))
    sys.path.insert(0, os.path.join(here, "..", "extract-template-asset"))
    import crop_template
    return crop_template


def load_search_region(path: str, region_args) -> np.ndarray:
    """The search region of a full screenshot at 720p in RGB, with a 1px margin."""
    ct = crop_template_module()
    image = cv2.cvtColor(cv2.imread(path), cv2.COLOR_BGR2RGB)
    ax, ay, aw, ah = ct.game_area(image.shape[1], image.shape[0])
    area = image[ay:ay + ah, ax:ax + aw]
    if ah != ct.IMAGE_H:
        area = cv2.resize(area, (round(aw * ct.IMAGE_H / ah), ct.IMAGE_H), interpolation=cv2.INTER_AREA)
    rx, ry, rw, rh = ct.resolve(region_args, aw, ah)
    return area[ry - 1:ry + rh + 1, rx - 1:rx + rw + 1]
