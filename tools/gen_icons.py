import os
from PIL import Image, ImageDraw, ImageFilter

SRC = r"E:/andriod/AndroidStudioProjects/DavSrv/icon_src.png"
RES = r"E:/andriod/AndroidStudioProjects/DavSrv/app/src/main/res"

src = Image.open(SRC).convert("RGB")

# ---- 1. extract white glyph -> transparent foreground ----
px = src.load()
w, h = src.size
glyph = Image.new("RGBA", (w, h), (0, 0, 0, 0))
gp = glyph.load()
for y in range(h):
    for x in range(w):
        r, g, b = px[x, y]
        # background is teal (R low); glyph is near-white
        a = int(max(0, min(255, (r - 70) * 255 / 110)))
        if a:
            gp[x, y] = (255, 255, 255, a)

bbox = glyph.getbbox()
glyph = glyph.crop(bbox)
gw, gh = glyph.size
side = max(gw, gh)

# ---- 2. adaptive foreground: glyph inside 66% safe zone of 108dp canvas ----
def adaptive_fg(size):
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    target = int(size * 0.60)
    scale = target / side
    g = glyph.resize((max(1, int(gw * scale)), max(1, int(gh * scale))), Image.LANCZOS)
    canvas.paste(g, ((size - g.width) // 2, (size - g.height) // 2), g)
    return canvas

# ---- 3. teal gradient background for adaptive layer ----
def make_bg(size):
    # sample icon corners for a matching diagonal gradient
    c1 = src.getpixel((int(w * 0.08), int(h * 0.05)))
    c2 = src.getpixel((int(w * 0.92), int(h * 0.95)))
    img = Image.new("RGB", (size, size))
    d = ImageDraw.Draw(img)
    for i in range(size * 2):
        t = i / (size * 2 - 1)
        col = tuple(int(c1[k] + (c2[k] - c1[k]) * t) for k in range(3))
        d.line([(0, i), (i, 0)], fill=col)
    return img

# ---- 4. legacy icons: rounded square + circle, full artwork ----
def rounded(img, radius_ratio, circle=False):
    size = img.size[0]
    mask = Image.new("L", (size * 4, size * 4), 0)
    d = ImageDraw.Draw(mask)
    if circle:
        d.ellipse([0, 0, size * 4 - 1, size * 4 - 1], fill=255)
    else:
        r = int(size * 4 * radius_ratio)
        d.rounded_rectangle([0, 0, size * 4 - 1, size * 4 - 1], radius=r, fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    out = img.convert("RGBA")
    out.putalpha(mask)
    return out

densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
# adaptive layers are 108dp
for name, dp in densities.items():
    folder = os.path.join(RES, "mipmap-" + name)
    os.makedirs(folder, exist_ok=True)

    base = src.resize((dp, dp), Image.LANCZOS)
    rounded(base, 0.22).save(os.path.join(folder, "ic_launcher.png"))
    rounded(base, 0.5, circle=True).save(os.path.join(folder, "ic_launcher_round.png"))

    fg_size = int(dp * 108 / 48)
    adaptive_fg(fg_size).save(os.path.join(folder, "ic_launcher_foreground.png"))
    make_bg(fg_size).save(os.path.join(folder, "ic_launcher_background.png"))

# ---- 5. notification small icon (white glyph, 24dp) ----
for name, dp in {"mdpi": 24, "hdpi": 36, "xhdpi": 48, "xxhdpi": 72, "xxxhdpi": 96}.items():
    folder = os.path.join(RES, "drawable-" + name)
    os.makedirs(folder, exist_ok=True)
    g = glyph.resize((dp, dp), Image.LANCZOS)
    white = Image.new("RGBA", (dp, dp), (0, 0, 0, 0))
    white.paste((255, 255, 255, 255), (0, 0), g)
    white.save(os.path.join(folder, "ic_stat_davsrv.png"))

# ---- 6. in-app logo (round, high-res) ----
os.makedirs(os.path.join(RES, "drawable-nodpi"), exist_ok=True)
rounded(src.resize((512, 512), Image.LANCZOS), 0.24).save(
    os.path.join(RES, "drawable-nodpi", "app_logo.png"))

print("icons generated", glyph.size)
