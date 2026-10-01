"""Regenerate the launcher, legacy and store icons from blade-g-render.png.

Needs Python 3 with pillow, numpy and opencv-python-headless. Run from the repo root:

    python docs/brand/make_launcher_icons.py

Writes the adaptive foreground (drawable-*dpi), the legacy mipmaps and the fastlane store icon.
The background layer (ic_launcher_gradation_background.xml) and the themed-icon vector
(ic_launcher_gradation_mono.xml) are XML; if GLYPH_DP changes, rescale the mono group to match.
"""
import os

import cv2
import numpy as np
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RES = os.path.join(ROOT, "app/src/main/res")
SRC = os.path.join(ROOT, "docs/brand/blade-g-render.png")
STORE = os.path.join(ROOT, "fastlane/metadata/android/en-US/images/icon.png")

# Letter height on the 108dp adaptive canvas. 58 keeps the blade tip inside the 66dp safe circle.
GLYPH_DP = 58
# Tone of the letter: out = LIFT + render * GAIN. 0 and 1 keep the render's black obsidian body.
LIFT, GAIN = 0, 1.0
# Radial falloff, same as ic_launcher_gradation_background.xml. Light enough that the black
# letter separates from the tile in app drawers, dark enough to stay on the dark base.
BG_CENTER, BG_EDGE = 0x33, 0x1C


def glyph():
    src = cv2.imread(SRC)
    gray = cv2.cvtColor(src, cv2.COLOR_BGR2GRAY)
    # The body is almost the background's black, so find the shape from its silver outline:
    # anything above black, closed, with every outer contour filled.
    m = cv2.morphologyEx(((gray > 4) * 255).astype(np.uint8), cv2.MORPH_CLOSE,
                         cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (7, 7)))
    cs, _ = cv2.findContours(m, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    m = np.zeros_like(m)
    cv2.drawContours(m, [c for c in cs if cv2.contourArea(c) > 2000], -1, 255, -1)
    m = cv2.morphologyEx(m, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5)))
    alpha = cv2.GaussianBlur(cv2.dilate(m, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5))), (0, 0), 1.2)
    tone = np.clip(LIFT + gray.astype(np.float32) * GAIN, 0, 255).astype(np.uint8)
    x, y, w, h = cv2.boundingRect((m > 0).astype(np.uint8))
    img = Image.fromarray(np.dstack([tone, tone, tone, alpha]), "RGBA")
    return img, (x + w / 2, y + h / 2), h


def background(size):
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32) / size * 108
    d = np.clip(np.sqrt((xx - 54) ** 2 + (yy - 40) ** 2) / 76, 0, 1)
    v = (BG_CENTER + (BG_EDGE - BG_CENTER) * d).astype(np.uint8)
    return Image.fromarray(np.dstack([v, v, v, np.full_like(v, 255)]), "RGBA")


def place(g, canvas_px, glyph_frac, base=None):
    img, (cx, cy), h = g
    s = canvas_px * glyph_frac / h
    big = img.resize((round(img.width * s), round(img.height * s)), Image.LANCZOS)
    out = base.copy() if base is not None else Image.new("RGBA", (canvas_px, canvas_px), (0, 0, 0, 0))
    out.paste(big, (round(canvas_px / 2 - cx * s), round(canvas_px / 2 - cy * s)), big)
    return out


def masked(img, round_):
    n = img.size[0]
    mk = Image.new("L", (n * 4, n * 4), 0)
    dr = ImageDraw.Draw(mk)
    if round_:
        dr.ellipse((0, 0, n * 4 - 1, n * 4 - 1), fill=255)
    else:
        dr.rounded_rectangle((0, 0, n * 4 - 1, n * 4 - 1), radius=n * 4 * 0.22, fill=255)
    out = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    out.paste(img, (0, 0), mk.resize((n, n), Image.LANCZOS))
    return out


def main():
    g = glyph()
    frac = GLYPH_DP / 108
    for dpi, px in dict(mdpi=108, hdpi=162, xhdpi=216, xxhdpi=324, xxxhdpi=432).items():
        d = os.path.join(RES, f"drawable-{dpi}")
        os.makedirs(d, exist_ok=True)
        place(g, px, frac).save(os.path.join(d, "ic_launcher_gradation_foreground.png"), optimize=True)
    # Legacy bitmaps: the visible 72dp of the 108dp adaptive canvas.
    for dpi, px in dict(mdpi=48, hdpi=72, xhdpi=96, xxhdpi=144, xxxhdpi=192).items():
        full = round(px * 108 / 72)
        off = (full - px) // 2
        vis = place(g, full, frac, background(full)).crop((off, off, off + px, off + px))
        d = os.path.join(RES, f"mipmap-{dpi}")
        masked(vis, False).save(os.path.join(d, "ic_launcherrobot.png"), optimize=True)
    # Store icon: full-bleed square; the stores apply their own mask.
    place(g, 512, 0.70, background(512)).convert("RGB").save(STORE, optimize=True)


if __name__ == "__main__":
    main()
