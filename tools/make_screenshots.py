"""Play Store screenshots (1080x1920) drawn from the app's real sticker art and layouts.

Run: python3 tools/make_screenshots.py [out_dir]   (Pillow with raqm)
Replace them with real phone screenshots once the app is built.
"""
import json, os, sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
A = os.path.join(ROOT, "app/src/main/assets")
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "store/screenshots")
os.makedirs(OUT, exist_ok=True)
LIL = os.path.join(ROOT, "tools/fonts/LilitaOne-Regular.ttf")
URDU = os.path.join(ROOT, "tools/fonts/NotoNastaliqUrdu.ttf")
EMOJI = "/usr/share/fonts/truetype/noto/NotoColorEmoji.ttf"
SANS = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
SANSB = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
BRAND, DARK, PINK, SURF, INK = (108, 43, 217), (75, 23, 168), (255, 61, 127), (247, 244, 252), (29, 27, 32)
CONT = (234, 221, 255)
W, H = 1080, 1920
BRAND_NAME = "StickRang"
TILE = 22
packs = {p["identifier"]: p for p in json.load(open(os.path.join(A, "contents.json")))["sticker_packs"]}
F = lambda p, s: ImageFont.truetype(p, s)


def gradient(w, h, a=BRAND, b=PINK):
    g = Image.new("L", (w, h))
    g.putdata([min(255, (x + y) * 255 // (w + h - 2)) for y in range(h) for x in range(w)])
    return Image.composite(Image.new("RGBA", (w, h), b + (255,)), Image.new("RGBA", (w, h), a + (255,)), g)


BG = gradient(W, H)


def sticker(pid, f, size):
    return Image.open(os.path.join(A, pid, f)).convert("RGBA").resize((size, size), Image.LANCZOS)


def emoji(ch, size):
    im = Image.new("RGBA", (160, 160), (0, 0, 0, 0))
    ImageDraw.Draw(im).text((80, 80), ch, font=ImageFont.truetype(EMOJI, 109), embedded_color=True, anchor="mm")
    return im.crop(im.getbbox()).resize((size, size), Image.LANCZOS)


def canvas(headline, sub):
    im = BG.copy()
    d = ImageDraw.Draw(im)
    d.text((W // 2, 120), headline, font=F(LIL, 84), fill="white", anchor="mm", stroke_width=5, stroke_fill=DARK)
    d.text((W // 2, 215), sub, font=F(LIL, 44), fill=(255, 230, 150), anchor="mm")
    px, py, pw, ph = 130, 290, 820, 1600
    shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle([px + 10, py + 20, px + pw + 10, py + ph + 20], 70, fill=(0, 0, 0, 100))
    im.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(18)))
    d.rounded_rectangle([px, py, px + pw, py + ph], 70, fill=(22, 20, 26))
    return im, Image.new("RGBA", (pw - 40, ph - 40), SURF + (255,)), (px + 20, py + 20)


def finish(im, screen, pos, name):
    m = Image.new("L", screen.size, 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, screen.size[0], screen.size[1]], 52, fill=255)
    im.paste(screen, pos, m)
    im.convert("RGB").save(os.path.join(OUT, name))


def toolbar(s, title, search=False, back=False):
    d = ImageDraw.Draw(s)
    d.rectangle([0, 0, s.size[0], 70], fill=DARK)
    d.text((40, 35), "9:41", font=F(SANSB, 28), fill="white", anchor="lm")
    d.rectangle([0, 70, s.size[0], 190], fill=BRAND)
    x = 40
    if back:
        d.text((40, 130), "←", font=F(SANSB, 48), fill="white", anchor="lm"); x = 110
    d.text((x, 130), title, font=F(SANSB, 44), fill="white", anchor="lm")
    if search:
        cx, cy = s.size[0] - 150, 125
        d.ellipse([cx - 18, cy - 18, cx + 14, cy + 14], outline="white", width=6)
        d.line([cx + 10, cy + 10, cx + 30, cy + 30], fill="white", width=7)
        for i in range(3):
            d.ellipse([s.size[0] - 58, 105 + i * 18, s.size[0] - 50, 113 + i * 18], fill="white")
    return d


def icon(d, kind, x, y, r, color):
    """Small versions of the app's vector icons."""
    if kind == "cut":
        d.ellipse([x - r, y + r * 0.2, x - r * 0.2, y + r], outline=color, width=6)
        d.ellipse([x - r, y - r, x - r * 0.2, y - r * 0.2], outline=color, width=6)
        d.line([x - r * 0.3, y - r * 0.3, x + r, y + r * 0.6], fill=color, width=7)
        d.line([x - r * 0.3, y + r * 0.3, x + r, y - r * 0.6], fill=color, width=7)
    elif kind == "text":
        d.text((x, y), "T", font=F(SANSB, int(r * 2.2)), fill=color, anchor="mm")
    elif kind == "play":
        d.polygon([(x - r * 0.6, y - r), (x - r * 0.6, y + r), (x + r, y)], fill=color)
    elif kind == "photo":
        d.rounded_rectangle([x - r, y - r, x + r, y + r], 8, outline=color, width=6)
        d.polygon([(x - r + 8, y + r - 8), (x - r * 0.2, y), (x + r * 0.2, y + r * 0.4), (x + r * 0.5, y + r * 0.1), (x + r - 8, y + r - 8)], fill=color)


def bottom_nav(s, sel):
    d = ImageDraw.Draw(s)
    y0 = s.size[1] - 150
    d.rectangle([0, y0, s.size[0], s.size[1]], fill=(243, 237, 250))
    w = s.size[0] // 3
    for i, l in enumerate(["Create", "Explore", "My Stickers"]):
        cx = w * i + w // 2
        if i == sel:
            d.rounded_rectangle([cx - 60, y0 + 18, cx + 60, y0 + 78], 30, fill=CONT)
        d.ellipse([cx - 18, y0 + 30, cx + 18, y0 + 66], outline=BRAND if i == sel else (90, 85, 100), width=5)
        d.text((cx, y0 + 108), l, font=F(SANSB if i == sel else SANS, 26), fill=INK, anchor="mm")


def pack_card(s, y, p, button="Add"):
    d = ImageDraw.Draw(s)
    x0, x1 = 30, s.size[0] - 30
    d.rounded_rectangle([x0, y, x1, y + 330], 36, fill="white", outline=(230, 224, 240), width=2)
    s.alpha_composite(Image.open(os.path.join(A, p["identifier"], "tray.png")).convert("RGBA").resize((80, 80)), (x0 + 30, y + 30))
    d.text((x0 + 130, y + 45), p["name"], font=F(SANSB, 32), fill=INK)
    d.text((x0 + 130, y + 95), f"{BRAND_NAME} · {len(p['stickers'])} stickers", font=F(SANS, 26), fill=(110, 105, 120))
    bw = 150 if button == "Add" else 190
    d.rounded_rectangle([x1 - 30 - bw, y + 40, x1 - 30, y + 110], 35, fill=CONT)
    d.text((x1 - 30 - bw // 2, y + 75), button, font=F(SANSB, 30), fill=DARK, anchor="mm")
    sz = (x1 - x0 - 60) // 5
    for i, st in enumerate(p["stickers"][:5]):
        s.alpha_composite(sticker(p["identifier"], st["image_file"], sz - 10), (x0 + 30 + i * sz, y + 150))


def chips(d, x, y, labels, sel, size=28):
    for i, l in enumerate(labels):
        f = F(URDU, size - 4) if l == "اردو" else F(SANSB, size)
        w = int(d.textlength(l, font=f)) + 44
        d.rounded_rectangle([x, y, x + w, y + 64], 16, fill=CONT if i == sel else "white", outline=(170, 160, 185), width=2)
        d.text((x + w // 2, y + 32), l, font=f, fill=DARK if i == sel else (60, 55, 70), anchor="mm")
        x += w + 10


def checker(d, x0, y0, x1, y1):
    for yy in range(y0, y1, TILE):
        for xx in range(x0, x1, TILE):
            if ((xx - x0) // TILE + (yy - y0) // TILE) % 2 == 0:
                d.rectangle([xx, yy, min(xx + TILE - 1, x1), min(yy + TILE - 1, y1)], fill=(236, 231, 245))


def colours(d, y, selected):
    for i, c in enumerate(["#FFFFFF", "#6C2BD9", "#FF5A5F", "#FFB400", "#7B61FF", "#1FA2FF", "#E91E63", "#FF7A00"]):
        x = 55 + i * 92
        d.ellipse([x, y, x + 66, y + 66], fill=c, outline=(180, 180, 180), width=2)
        if c == selected:
            d.ellipse([x - 8, y - 8, x + 74, y + 74], outline=DARK, width=5)


def save_button(d, s, label="Save sticker"):
    d.rounded_rectangle([40, s.size[1] - 150, s.size[0] - 40, s.size[1] - 50], 50, fill=BRAND)
    d.text((s.size[0] // 2, s.size[1] - 100), label, font=F(SANSB, 34), fill="white", anchor="mm")


# 1. Create tab
im, s, pos = canvas("Make Stickers Fast", "Photo, text, GIF ya video se")
toolbar(s, BRAND_NAME, search=True)
d = ImageDraw.Draw(s)
hero = gradient(s.size[0] - 60, 190)
m = Image.new("L", hero.size, 0); ImageDraw.Draw(m).rounded_rectangle([0, 0, hero.size[0], hero.size[1]], 40, fill=255)
s.paste(hero, (30, 220), m)
d.text((70, 262), "Make a sticker in seconds", font=F(SANSB, 38), fill="white")
d.text((70, 322), "No ads. No watermark. Works offline.", font=F(SANS, 28), fill=(255, 235, 245))
cards = [("Cut-out", "Remove a photo's background", (234, 221, 255), "cut"),
         ("Text", "Words with style", (255, 217, 226), "text"),
         ("GIF / Video", "Make it move", (255, 232, 184), "play"),
         ("Photo", "Round or square", (211, 242, 230), "photo")]
cw = (s.size[0] - 90) // 2
for i, (t, sub, col, ic) in enumerate(cards):
    x = 30 + (i % 2) * (cw + 30)
    y = 440 + (i // 2) * 330
    d.rounded_rectangle([x, y, x + cw, y + 300], 40, fill=col)
    icon(d, ic, x + 70, y + 75, 30, BRAND)
    d.text((x + 40, y + 190), t, font=F(SANSB, 38), fill=INK)
    d.text((x + 40, y + 245), sub if len(sub) < 22 else sub[:20] + "…", font=F(SANS, 25), fill=(73, 69, 79))
d.text((40, 1130), "Popular packs", font=F(SANSB, 32), fill=INK)
for i, (pid, f) in enumerate([("party_time", "01.webp"), ("funny_reactions", "01.webp"), ("love_hearts", "01.webp"), ("desi_replies", "01.webp")]):
    s.alpha_composite(sticker(pid, f, 175), (25 + i * 185, 1185))
bottom_nav(s, 0)
finish(im, s, pos, "1-create.png")

# 2. Background removal before/after (illustration drawn from open-licence emoji art)
im, s, pos = canvas("Remove Background", "Ek tap mein cut-out sticker")
toolbar(s, "Create sticker", back=True)
d = ImageDraw.Draw(s)
cx = s.size[0] // 2
photo = gradient(560, 560, (120, 190, 255), (160, 230, 170)).convert("RGBA")
pd = ImageDraw.Draw(photo)
pd.ellipse([380, 40, 500, 160], fill=(255, 230, 120))
pd.rectangle([0, 420, 560, 560], fill=(90, 170, 90))
photo.alpha_composite(emoji("🐱", 330), (115, 150))
s.alpha_composite(photo.resize((330, 330)), (40, 240))
d.text((205, 600), "Before", font=F(SANSB, 30), fill=INK, anchor="mm")
cut = Image.new("RGBA", (560, 560), (0, 0, 0, 0))
cat = emoji("🐱", 330)
ring = cat.split()[3].filter(ImageFilter.MaxFilter(25))
cut.paste((0, 0, 0, 70), (115, 165), ring.filter(ImageFilter.GaussianBlur(6)))
cut.paste(Image.new("RGBA", cat.size, (255, 255, 255, 255)), (115, 150), ring)
cut.alpha_composite(cat, (115, 150))
ImageDraw.Draw(cut).text((280, 505), "Meow!", font=F(LIL, 80), fill=PINK, anchor="mm", stroke_width=12, stroke_fill="white")
checker(d, 410, 240, 740, 570)
s.alpha_composite(cut.resize((330, 330)), (410, 240))
d.text((575, 600), "After", font=F(SANSB, 30), fill=INK, anchor="mm")
d.text((375, 400), "→", font=F(SANSB, 60), fill=BRAND, anchor="mm")
d.rounded_rectangle([40, 680, s.size[0] - 40, 770], 45, fill=BRAND)
d.text((cx, 725), "Remove background", font=F(SANSB, 32), fill="white", anchor="mm")
d.rounded_rectangle([40, 790, s.size[0] - 40, 880], 45, outline=BRAND, width=3)
d.text((cx, 835), "Edit: erase, restore, crop, rotate", font=F(SANSB, 28), fill=BRAND, anchor="mm")
d.text((40, 930), "Animation", font=F(SANSB, 28), fill=INK)
chips(d, 40, 975, ["None", "Pulse", "Bounce", "Shake", "Rainbow"], 2)
d.text((40, 1080), "Text colour", font=F(SANSB, 28), fill=INK)
colours(d, 1125, "#E91E63")
save_button(d, s)
finish(im, s, pos, "2-cutout.png")

# 3. Explore with categories
im, s, pos = canvas("100+ Stickers", "Funny, Love, Party, Desi, Islamic")
toolbar(s, BRAND_NAME, search=True)
d = ImageDraw.Draw(s)
chips(d, 20, 215, ["All", "Funny", "Love", "Greetings", "Party"], 0, 26)
for i, pid in enumerate(["funny_reactions", "love_hearts", "party_time"]):
    pack_card(s, 305 + i * 350, packs[pid], "Added ✓" if i == 0 else "Add")
bottom_nav(s, 1)
finish(im, s, pos, "3-explore.png")

# 4. Text sticker + animation
im, s, pos = canvas("Text Stickers", "Any language, animated too")
toolbar(s, "Create sticker", back=True)
d = ImageDraw.Draw(s)
cx, top, half = s.size[0] // 2, 225, 220
checker(d, cx - half, top, cx + half, top + 2 * half)
t = Image.new("RGBA", (2 * half, 2 * half), (0, 0, 0, 0))
fnt = F(LIL, 96)
ImageDraw.Draw(t).multiline_text((half + 3, half + 10), "Best day\never!", font=fnt, fill=(0, 0, 0, 90), anchor="mm", align="center", stroke_width=18, stroke_fill=(0, 0, 0, 90))
t = t.filter(ImageFilter.GaussianBlur(6))
ImageDraw.Draw(t).multiline_text((half, half), "Best day\never!", font=fnt, fill=BRAND, anchor="mm", align="center", stroke_width=18, stroke_fill="white")
s.alpha_composite(t, (cx - half, top))
d = ImageDraw.Draw(s)
d.rounded_rectangle([40, 690, s.size[0] - 40, 780], 16, outline=(150, 145, 160), width=3)
d.text((70, 735), "Best day ever!", font=F(SANS, 32), fill=INK, anchor="lm")
chips(d, 40, 805, ["ABC", "اردو"], 0)
chips(d, 40, 885, ["Round", "Square", "Text only"], 2)
d.text((40, 975), "Text colour", font=F(SANSB, 28), fill=INK)
colours(d, 1020, "#6C2BD9")
d.text((40, 1115), "Animation", font=F(SANSB, 28), fill=INK)
chips(d, 40, 1158, ["None", "Pulse", "Bounce", "Shake", "Rainbow"], 1)
save_button(d, s)
finish(im, s, pos, "4-text.png")


# 5-6. Pack grids
def grid(pid, headline, sub, name):
    im, s, pos = canvas(headline, sub)
    toolbar(s, packs[pid]["name"], back=True)
    d = ImageDraw.Draw(s)
    d.text((40, 235), f"{BRAND_NAME} · {len(packs[pid]['stickers'])} stickers", font=F(SANS, 28), fill=(110, 105, 120))
    sz = (s.size[0] - 40) // 3
    for i, st in enumerate(packs[pid]["stickers"][:12]):
        s.alpha_composite(sticker(pid, st["image_file"], sz - 20), (20 + (i % 3) * sz + 10, 300 + (i // 3) * sz))
    save_button(d, s, "Add to WhatsApp")
    finish(im, s, pos, name)


grid("funny_reactions", "Add Packs in One Tap", "Poora pack ek saath add", "5-funny.png")
grid("eid_ramadan", "Desi & Islamic Packs", "Eid, Ramadan, Salam, Dua", "6-eid.png")
print("ok", OUT)
