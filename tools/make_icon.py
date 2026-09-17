"""Render the Bypass launcher icon to the legacy PNG mipmap folders plus the store icon.

Geometry mirrors res/drawable/ic_launcher_foreground.xml: a lavender shield with
the way through it cut out, on a violet gradient.
"""
import os

from PIL import Image, ImageDraw

ROOT = r"E:\vpn\android\app\src\main\res"
STORE = r"E:\vpn\assets\bypass-icon-512.png"
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

SS = 4  # supersample factor for the shapes that need hard edges
MARK = (0xED, 0xE8, 0xFF)  # lavender
STOPS = ((0.0, (0x7C, 0x5C, 0xFF)), (0.5, (0x5B, 0x3A, 0xE8)), (1.0, (0x43, 0x26, 0xC4)))
GRAD_A = (14.0, 6.0)
GRAD_B = (98.0, 102.0)

# Drawn on a 108-unit canvas and scaled up about the centre, so the mark sits
# closer to the edge of the artwork than it does inside the adaptive-icon safe zone.
FILL = 1.12
SHIELD = ((28, 24), (80, 24), (80, 50), (80, 72), (54, 86), (28, 72), (28, 50))
SHIELD_CURVES = ((2, 3, 4), (4, 5, 6))
CHEVRON = ((45, 40), (64, 54), (45, 68), (45, 59.2), (52, 54), (45, 48.8))


def _lerp(a, b, t):
    return tuple(round(x + (y - x) * t) for x, y in zip(a, b))


def _stop_color(t):
    for (t0, c0), (t1, c1) in zip(STOPS, STOPS[1:]):
        if t <= t1:
            return _lerp(c0, c1, (t - t0) / (t1 - t0))
    return STOPS[-1][1]


def _shield_outline(steps=24):
    """The shield as a polygon: straight top, then two quadratics into the point."""
    pts = [SHIELD[0], SHIELD[1]]
    for start, ctrl, end in SHIELD_CURVES:
        x0, y0 = SHIELD[start]
        cx, cy = SHIELD[ctrl]
        x1, y1 = SHIELD[end]
        pts.append((x0, y0))
        for i in range(1, steps + 1):
            t = i / steps
            u = 1 - t
            pts.append((u * u * x0 + 2 * u * t * cx + t * t * x1,
                        u * u * y0 + 2 * u * t * cy + t * t * y1))
    return pts


def gradient(size):
    """The background gradient, evaluated directly at `size` (it is smooth, no AA needed)."""
    img = Image.new("RGB", (size, size))
    load = img.load()
    ax, ay = GRAD_A
    dx, dy = GRAD_B[0] - ax, GRAD_B[1] - ay
    span = dx * dx + dy * dy
    scale = 108.0 / size
    for y in range(size):
        yy = (y + 0.5) * scale
        for x in range(size):
            t = min(1.0, max(0.0, (((x + 0.5) * scale - ax) * dx + (yy - ay) * dy) / span))
            load[x, y] = _stop_color(t)
    return img


def shape_mask(size, rounded):
    px = size * SS
    mask = Image.new("L", (px, px), 0)
    d = ImageDraw.Draw(mask)
    box = [0, 0, px - 1, px - 1]
    if rounded:
        d.rounded_rectangle(box, radius=round(px * 0.22), fill=255)
    else:
        d.ellipse(box, fill=255)
    return mask.resize((size, size), Image.LANCZOS)


def mark(size):
    """Shield minus chevron, drawn supersampled and scaled back down."""
    px = size * SS
    k = px * FILL / 108.0
    mask = Image.new("L", (px, px), 0)
    d = ImageDraw.Draw(mask)

    def p(pt):
        return ((pt[0] - 54) * k + px / 2.0, (pt[1] - 54) * k + px / 2.0)

    d.polygon([p(q) for q in _shield_outline()], fill=255)
    d.polygon([p(q) for q in CHEVRON], fill=0)

    layer = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    layer.paste(MARK + (255,), (0, 0), mask)
    return layer.resize((size, size), Image.LANCZOS)


def compose(size, rounded):
    art = gradient(size).convert("RGBA")
    art.putalpha(shape_mask(size, rounded))
    art.alpha_composite(mark(size))
    return art


def main():
    for name, size in SIZES.items():
        folder = os.path.join(ROOT, "mipmap-" + name)
        os.makedirs(folder, exist_ok=True)
        compose(size, True).save(os.path.join(folder, "ic_launcher.png"))
        compose(size, False).save(os.path.join(folder, "ic_launcher_round.png"))
        print(name, size, "ok")
    os.makedirs(os.path.dirname(STORE), exist_ok=True)
    compose(512, True).save(STORE)
    print("store icon ok")


if __name__ == "__main__":
    main()
