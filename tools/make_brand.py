#!/usr/bin/env python3
"""Draws the StreamX brand assets (launcher icons, TV banner, in-app mark) from code.

The mark: a screen frame whose picture is a play sign built from scan lines, with the red
"on air" tally light of a studio camera on its corner. Run from the repository root:
    python3 tools/make_brand.py
"""
from PIL import Image, ImageDraw, ImageFilter, ImageFont

RES = "app/src/main/res"
INK = (11, 12, 14)
GRAPHITE = (24, 27, 31)
TEXT = (241, 242, 243)
DIM = (155, 161, 168)
TALLY = (255, 77, 46)
EMBER = (255, 150, 60)
SS = 4  # supersampling


def lerp(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def draw_mark(d: ImageDraw.ImageDraw, x: float, y: float, s: float, frame=TEXT, bg=INK):
    """Mark in the square (x, y, s). Screen frame, scan-line play sign, tally light."""
    stroke = s * 0.075
    fx0, fy0, fx1, fy1 = x + s * 0.08, y + s * 0.16, x + s * 0.86, y + s * 0.84
    d.rounded_rectangle((fx0, fy0, fx1, fy1), radius=s * 0.13, outline=frame, width=round(stroke))
    # Play sign, solid, crossed by thin scan-line gaps (the picture of a broadcast monitor).
    tx0, ty0, ty1 = x + s * 0.35, y + s * 0.32, y + s * 0.68
    tip = x + s * 0.65
    mid = (ty0 + ty1) / 2
    gap = s * 0.022
    gaps = [ty0 + (ty1 - ty0) * i / 4 for i in range(1, 4)]
    for row in range(int(ty0), int(ty1) + 1):
        k = 1 - abs(row - mid) / ((ty1 - ty0) / 2)
        if k <= 0 or any(abs(row - g) < gap / 2 for g in gaps):
            continue
        t = (row - ty0) / (ty1 - ty0)
        d.line((tx0, row, tx0 + (tip - tx0) * k, row), fill=lerp(TALLY, EMBER, t), width=1)
    # Tally light on the top-right corner, cut out of the frame by an ink ring.
    cx, cy, r = fx1 - s * 0.02, fy0 + s * 0.02, s * 0.105
    d.ellipse((cx - r * 1.45, cy - r * 1.45, cx + r * 1.45, cy + r * 1.45), fill=bg)
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=TALLY)


def glow(img: Image.Image, cx, cy, radius, color, strength):
    layer = Image.new("RGB", img.size, (0, 0, 0))
    ImageDraw.Draw(layer).ellipse((cx - radius, cy - radius, cx + radius, cy + radius), fill=color)
    layer = layer.filter(ImageFilter.GaussianBlur(radius * 0.6))
    return Image.blend(img, Image.composite(layer, img, Image.new("L", img.size, round(255 * strength))), 1.0)


def icon(size: int, round_mask: bool = False) -> Image.Image:
    S = size * SS
    img = Image.new("RGB", (S, S), INK)
    img = glow(img, S * 0.82, S * 0.18, S * 0.42, (90, 28, 18), 0.55)
    d = ImageDraw.Draw(img)
    draw_mark(d, S * 0.12, S * 0.12, S * 0.76)
    img = img.resize((size, size), Image.LANCZOS)
    mask = Image.new("L", (S, S), 0)
    md = ImageDraw.Draw(mask)
    if round_mask:
        md.ellipse((0, 0, S - 1, S - 1), fill=255)
    else:
        md.rounded_rectangle((0, 0, S - 1, S - 1), radius=S * 0.18, fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask.resize((size, size), Image.LANCZOS))
    return out


def banner(w=640, h=360) -> Image.Image:
    W, H = w * SS, h * SS
    img = Image.new("RGB", (W, H), INK)
    img = glow(img, W * 0.18, H * 0.5, H * 0.55, (80, 24, 16), 0.6)
    d = ImageDraw.Draw(img)
    m = H * 0.56
    draw_mark(d, W * 0.06, (H - m) / 2, m)
    big = ImageFont.truetype(f"{RES}/font/barlowcondensed_bold.ttf", round(H * 0.30))
    small = ImageFont.truetype(f"{RES}/font/ibmplexmono_medium.ttf", round(H * 0.075))
    tx = W * 0.06 + m + W * 0.035
    d.text((tx, H * 0.24), "StreamX", font=big, fill=TEXT)
    d.text((tx + W * 0.004, H * 0.60), "Live · Films · Séries", font=small, fill=DIM)
    return img.resize((w, h), Image.LANCZOS)


def main():
    sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for dpi, px in sizes.items():
        icon(px).save(f"{RES}/mipmap-{dpi}/ic_launcher.png")
        icon(px, round_mask=True).save(f"{RES}/mipmap-{dpi}/ic_launcher_round.png")
    banner().save(f"{RES}/drawable-xhdpi/banner.png")
    # In-app mark (transparent background), used in headers.
    S = 256 * SS
    mark = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    draw_mark(ImageDraw.Draw(mark), 0, 0, S, bg=(0, 0, 0, 0))
    mark.resize((256, 256), Image.LANCZOS).save(f"{RES}/drawable-xhdpi/logo_mark.png")


if __name__ == "__main__":
    main()
