"""Clean minimalistic Bypass launcher icons.

Goals vs original:
 - thinner stroke (5.6 vs 8) for lighter feel
 - more negative space (FILL 1.05 vs 1.25)
 - smaller radius (23 vs 25) + less crowded
 - flat solid background instead of strong diagonal gradient
 - 22% rounded rectangle stays, but surface is flat
 - white mark stays for contrast but more breathable

Generates:
 - 3 preview variants (flat, soft-gradient, ink) in ./previews/
 - overwrites legacy mipmap PNGs and play-store asset with the
   recommended flat variant (Variant A)
 - you can switch by changing RECOMMENDED
"""

import os
from PIL import Image, ImageDraw

ROOT = r"E:\vpn\android\app\src\main\res"
PREVIEW_DIR = r"E:\vpn\previews"
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
SS = 4

# --- Brand ---
FLAT = (0xF0, 0x4E, 0x3E)       # clean flat coral - muted vs original #F0455F
SOFT_C1 = (0xF2, 0x51, 0x4A)
SOFT_C2 = (0xFA, 0x7A, 0x3A)
INK_BG = (0xFF, 0xFF, 0xFF)     # white bg
INK_FG = (0xF0, 0x4E, 0x3E)     # coral mark on white

STROKE_W = 6.2                 # vs 8 before -> lighter but still legible
RADIUS = 24                    # vs 25 before
FILL = 1.12                    # vs 1.25  -> more padding but not tiny
MARK_COLOR_WHITE = (255, 255, 255, 255)
MARK_COLOR_INK = INK_FG + (255,)

def rounded_bg(size_px, color, radius_ratio=0.22):
    px = size_px * SS
    img = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, px-1, px-1], radius=round(px * radius_ratio), fill=color + (255,))
    return img.resize((size_px, size_px), Image.LANCZOS)

def gradient_bg(size_px, c1, c2, radius_ratio=0.22):
    px = size_px * SS
    base = Image.new("RGB", (px, px))
    d = ImageDraw.Draw(base)
    for i in range(2 * px):
        t = i / (2 * px - 1)
        col = tuple(round(a + (b-a)*t) for a,b in zip(c1, c2))
        d.line([(i,0),(0,i)], fill=col, width=2)
    base = base.convert("RGBA")
    mask = Image.new("L", (px, px), 0)
    m = ImageDraw.Draw(mask)
    m.rounded_rectangle([0,0,px-1,px-1], radius=round(px*0.22), fill=255)
    base.putalpha(mask)
    return base.resize((size_px, size_px), Image.LANCZOS)

def mark_layer(size, stroke_color):
    """white or coral ring+arrow, 108-unit design space"""
    px = size * SS
    layer = Image.new("RGBA", (px, px), (0,0,0,0))
    d = ImageDraw.Draw(layer)
    k = px * FILL / 108.0

    def p(x,y):
        return ((x-54)*k + px/2.0, (y-54)*k + px/2.0)

    w = STROKE_W * k
    cx, cy = px/2.0, px/2.0
    box = [cx - RADIUS*k, cy - RADIUS*k, cx + RADIUS*k, cy + RADIUS*k]
    # arcs - flat cut for clean geometry (no bulbous caps)
    d.arc(box, 206, 334, fill=stroke_color, width=round(w))
    d.arc(box, 26, 154, fill=stroke_color, width=round(w))

    # horizontal shaft - add round caps via ellipse (subtle)
    d.line([p(26,54), p(79.5,54)], fill=stroke_color, width=round(w))
    for pt in [(26,54)]:
        x,y = p(*pt)
        r = w/2
        d.ellipse([x-r,y-r,x+r,y+r], fill=stroke_color)
    # tip end is covered by arrowhead, no cap needed there
    # arrowhead
    d.line([p(68.5,47), p(79.5,54), p(68.5,61)], fill=stroke_color, width=round(w), joint="curve")
    return layer.resize((size, size), Image.LANCZOS)

def compose_flat(size):
    bg = rounded_bg(size, FLAT)
    fg = mark_layer(size, MARK_COLOR_WHITE)
    bg.alpha_composite(fg)
    return bg

def compose_soft(size):
    bg = gradient_bg(size, SOFT_C1, SOFT_C2)
    fg = mark_layer(size, MARK_COLOR_WHITE)
    bg.alpha_composite(fg)
    return bg

def compose_ink(size):
    # white bg, coral mark - ultra minimal
    bg = rounded_bg(size, INK_BG)
    # add subtle border for white on white contexts
    fg = mark_layer(size, MARK_COLOR_INK)
    bg.alpha_composite(fg)
    # 1px hairline stroke around card for legibility on white wallpapers
    px = size
    border = Image.new("RGBA", (px,px), (0,0,0,0))
    bd = ImageDraw.Draw(border)
    # not needed for preview; keep clean
    return bg

def save_previews():
    os.makedirs(PREVIEW_DIR, exist_ok=True)
    for fn, composer in [
        ("preview_flat_512.png", compose_flat),
        ("preview_soft_512.png", compose_soft),
        ("preview_ink_512.png", compose_ink),
    ]:
        im = composer(512)
        im.save(os.path.join(PREVIEW_DIR, fn))
        print(f"preview {fn} ok")
        # also 192 for launcher feel
        im2 = composer(192)
        im2.save(os.path.join(PREVIEW_DIR, fn.replace("512","192")))
    # side-by-side compare with original
    from PIL import Image as I
    orig = I.open(r"E:\vpn\assets\bypass-icon-512.png").convert("RGBA").resize((512,512), I.LANCZOS)
    flat = I.open(os.path.join(PREVIEW_DIR, "preview_flat_512.png"))
    soft = I.open(os.path.join(PREVIEW_DIR, "preview_soft_512.png"))
    ink  = I.open(os.path.join(PREVIEW_DIR, "preview_ink_512.png"))
    comp = I.new("RGBA", (1024, 1024), (16,24,39,255))
    comp.alpha_composite(orig.resize((480,480), I.LANCZOS), (20,20))
    comp.alpha_composite(flat.resize((480,480), I.LANCZOS), (524,20))
    comp.alpha_composite(soft.resize((480,480), I.LANCZOS), (20,524))
    comp.alpha_composite(ink.resize((480,480), I.LANCZOS), (524,524))
    # labels would need font - skip, just image grid
    comp.save(os.path.join(PREVIEW_DIR, "compare_2x2.png"))
    print("compare ok")

def install_recommended(variant="flat"):
    composers = {"flat": compose_flat, "soft": compose_soft, "ink": compose_ink}
    comp = composers[variant]
    for name,size in SIZES.items():
        folder = os.path.join(ROOT, "mipmap-" + name)
        os.makedirs(folder, exist_ok=True)
        # rounded (square)
        im = comp(size)
        # we need circle version for round icon: mask to circle
        # for flat/soft: regenerate circle bg
        if variant == "flat":
            # circle
            px = size*SS
            bg_circle = Image.new("RGBA", (px,px), (0,0,0,0))
            d = ImageDraw.Draw(bg_circle)
            d.ellipse([0,0,px-1,px-1], fill=FLAT+(255,))
            bg_circle = bg_circle.resize((size,size), Image.LANCZOS)
            fg = mark_layer(size, MARK_COLOR_WHITE)
            bg_circle.alpha_composite(fg)
            circ = bg_circle
        elif variant == "soft":
            px = size*SS
            base = Image.new("RGB", (px,px))
            d = ImageDraw.Draw(base)
            for i in range(2*px):
                t=i/(2*px-1)
                col=tuple(round(a+(b-a)*t) for a,b in zip(SOFT_C1, SOFT_C2))
                d.line([(i,0),(0,i)], fill=col, width=2)
            base=base.convert("RGBA")
            mask=Image.new("L",(px,px),0)
            ImageDraw.Draw(mask).ellipse([0,0,px-1,px-1], fill=255)
            base.putalpha(mask)
            base=base.resize((size,size), Image.LANCZOS)
            fg=mark_layer(size, MARK_COLOR_WHITE)
            base.alpha_composite(fg)
            circ=base
        else:
            px=size*SS
            bg_circle=Image.new("RGBA",(px,px),(0,0,0,0))
            ImageDraw.Draw(bg_circle).ellipse([0,0,px-1,px-1], fill=INK_BG+(255,))
            bg_circle=bg_circle.resize((size,size), Image.LANCZOS)
            fg=mark_layer(size, MARK_COLOR_INK)
            bg_circle.alpha_composite(fg)
            circ=bg_circle

        im.save(os.path.join(folder, "ic_launcher.png"))
        circ.save(os.path.join(folder, "ic_launcher_round.png"))
        print(name, size, "installed", variant)
    store = comp(512)
    # play store wants opaque RGB, rounded already; drop alpha for flat? keep RGB
    if variant == "ink":
        # ink is white bg, just save as RGB
        store.convert("RGB").save(r"E:\vpn\assets\bypass-icon-512.png")
    else:
        # flat/soft already have rounded alpha; for store we want rounded but RGB with white? keep RGBA flattened on white? original converted to RGB (loses transparency, keeps rounded baked on white). Do same: flatten on white then save RGB? original did convert RGB directly (black bg for corners). Better to composite on white.
        bg_white = Image.new("RGB", (512,512), (255,255,255))
        bg_white.paste(store, mask=store.split()[3])
        bg_white.save(r"E:\vpn\assets\bypass-icon-512.png")
        # also keep RGBA version for preview
        store.save(os.path.join(PREVIEW_DIR, f"store_{variant}_512.png"))
    print("store icon ok")

if __name__ == "__main__":
    save_previews()
    install_recommended("flat")
