"""Render the Bypass launcher icon to the legacy PNG mipmap folders.

Geometry mirrors res/drawable/ic_launcher_foreground.xml: a ring broken at
3 and 9 o'clock, with an arrow flying out through the gap.
"""
import os
from PIL import Image, ImageDraw

ROOT = r"E:\vpn\android\app\src\main\res"
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
SS = 4                      # supersample factor
C1 = (0xF0, 0x45, 0x5F)     # coral
C2 = (0xFF, 0x8A, 0x3C)     # orange
FILL = 1.25                 # how much larger the mark sits than the 108-unit design


def gradient(s):
    """Diagonal gradient, one flat colour per anti-diagonal."""
    img = Image.new("RGB", (s, s))
    d = ImageDraw.Draw(img)
    for i in range(2 * s):
        t = i / (2 * s - 1)
        col = tuple(round(a + (b - a) * t) for a, b in zip(C1, C2))
        d.line([(i, 0), (0, i)], fill=col, width=2)
    return img


def mark(size):
    """The white ring + arrow, drawn on transparent, in 108-unit design space."""
    px = size * SS
    layer = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    k = px * FILL / 108.0

    def p(x, y):
        return ((x - 54) * k + px / 2.0, (y - 54) * k + px / 2.0)

    w = 8 * k
    cx, cy = px / 2.0, px / 2.0
    box = [cx - 25 * k, cy - 25 * k, cx + 25 * k, cy + 25 * k]
    # PIL angles run clockwise from 3 o'clock, same as the SVG arc sweep used above.
    d.arc(box, 206, 334, fill=(255, 255, 255, 255), width=round(w))
    d.arc(box, 26, 154, fill=(255, 255, 255, 255), width=round(w))

    for a, b in ((25, 54), (82, 54)):
        x, y = p(a, b)
        d.ellipse([x - w / 2, y - w / 2, x + w / 2, y + w / 2], fill=(255, 255, 255, 255))
    d.line([p(25, 54), p(82, 54)], fill=(255, 255, 255, 255), width=round(w))

    d.line([p(70, 45), p(82, 54), p(70, 63)], fill=(255, 255, 255, 255),
           width=round(w), joint="curve")
    return layer.resize((size, size), Image.LANCZOS)


def compose(size, rounded):
    px = size * SS
    bg = gradient(px).convert("RGBA")
    mask = Image.new("L", (px, px), 0)
    m = ImageDraw.Draw(mask)
    if rounded:
        m.rounded_rectangle([0, 0, px - 1, px - 1], radius=round(px * 0.22), fill=255)
    else:
        m.ellipse([0, 0, px - 1, px - 1], fill=255)
    bg.putalpha(mask)
    bg.alpha_composite(mark(size).resize((px, px), Image.LANCZOS))
    return bg.resize((size, size), Image.LANCZOS)


def main():
    for name, size in SIZES.items():
        folder = os.path.join(ROOT, "mipmap-" + name)
        os.makedirs(folder, exist_ok=True)
        compose(size, rounded=True).save(os.path.join(folder, "ic_launcher.png"))
        compose(size, rounded=False).save(os.path.join(folder, "ic_launcher_round.png"))
        print(name, size, "ok")
    store = compose(512, True)
    store.convert("RGB").save(r"E:\vpn\assets\bypass-icon-512.png")
    print("store icon ok")


if __name__ == "__main__":
    main()
