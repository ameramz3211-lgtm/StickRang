"""Generates the bundled starter sticker packs (512x512 WebP + 96x96 tray PNG)
into app/src/main/assets/<pack_id>/ and writes app/src/main/assets/contents.json.

Run:  python3 tools/make_packs.py   (needs Pillow built with raqm for Urdu shaping)
"""
import json, os, math, random
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
FONTS = os.path.join(ROOT, "tools", "fonts")
LATIN = os.path.join(FONTS, "LilitaOne-Regular.ttf")
URDU = os.path.join(FONTS, "NotoNastaliqUrdu.ttf")
EMOJI = "/usr/share/fonts/truetype/noto/NotoColorEmoji.ttf"

PUBLISHER = "StickRang"
PALETTE = ["#6C2BD9", "#FF3D7F", "#FF5A5F", "#FFB400", "#00A699", "#7B61FF", "#FF7A00", "#1FA2FF",
           "#E91E63", "#2ECC71", "#8E44AD", "#F39C12", "#16A085", "#D35400"]

def font(path, size):
    f = ImageFont.truetype(path, size, layout_engine=ImageFont.Layout.RAQM)
    if "Nastaliq" in path:
        try: f.set_variation_by_axes([700])
        except Exception: pass
    return f

def emoji_img(ch, size):
    f = ImageFont.truetype(EMOJI, 109)
    im = Image.new("RGBA", (160, 160), (0, 0, 0, 0))
    ImageDraw.Draw(im).text((80, 80), ch, font=f, embedded_color=True, anchor="mm")
    im = im.crop(im.getbbox())
    return im.resize((size, size), Image.LANCZOS)

def fit(draw, text, path, max_w, start, min_size=40, direction=None):
    size = start
    while size > min_size:
        f = font(path, size)
        b = draw.textbbox((0, 0), text, font=f, stroke_width=8, direction=direction)
        if b[2] - b[0] <= max_w: return f
        size -= 4
    return font(path, min_size)

def wrap(text, n=12):
    words, lines, cur = text.split(), [], ""
    for w in words:
        if cur and len(cur) + 1 + len(w) > n: lines.append(cur); cur = w
        else: cur = (cur + " " + w).strip()
    lines.append(cur)
    return lines

def sticker(main, color, emoji=None, urdu=None, shape="blob", seed=0):
    rnd = random.Random(seed)
    S = 512
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    # die-cut bubble: white outline + coloured fill + soft shadow
    base = Image.new("L", (S, S), 0); bd = ImageDraw.Draw(base)
    if shape == "circle":
        bd.ellipse((40, 40, S - 40, S - 40), fill=255)
    else:
        pts = []
        for i in range(24):
            a = 2 * math.pi * i / 24
            r = 200 + rnd.randint(-8, 8)
            pts.append((S / 2 + r * math.cos(a) * 1.08, S / 2 + r * math.sin(a) * 0.92))
        bd.polygon(pts, fill=255)
        base = base.filter(ImageFilter.GaussianBlur(10)).point(lambda v: 255 if v > 128 else 0)
    shadow = base.filter(ImageFilter.MaxFilter(25)).filter(ImageFilter.GaussianBlur(8))
    im.paste((0, 0, 0, 60), (0, 6), shadow)
    outline = base.filter(ImageFilter.MaxFilter(21))
    im.paste((255, 255, 255, 255), (0, 0), outline)
    im.paste(color, (0, 0), base)
    d = ImageDraw.Draw(im)

    y = 256
    blocks = []
    if urdu: blocks.append(("urdu", urdu))
    lines = wrap(main)
    total_h = (95 if urdu else 0) + len(lines) * 78 + (0 if not emoji else 20)
    y = 256 - total_h / 2 + (30 if emoji else 0)
    if urdu:
        f = fit(d, urdu, URDU, 390, 80, direction="rtl")
        d.text((256, y + 30), urdu, font=f, fill="white", anchor="mm", stroke_width=6,
               stroke_fill="#222222", direction="rtl", language="ur")
        y += 100
    for ln in lines:
        f = fit(d, ln, LATIN, 400, 84 if len(lines) > 1 else 96)
        d.text((256, y + 36), ln, font=f, fill="white", anchor="mm", stroke_width=8, stroke_fill="#222222")
        y += 80
    if emoji:
        e = emoji_img(emoji, 120)
        im.alpha_composite(e, (S - 150, 20))
    return im

def save_webp(im, path):
    q = 85
    while True:
        im.save(path, "WEBP", quality=q, method=6)
        if os.path.getsize(path) < 95_000 or q <= 40: break
        q -= 10

def tray(emoji, color, path):
    im = Image.new("RGBA", (96, 96), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.ellipse((2, 2, 94, 94), fill="white"); d.ellipse((6, 6, 90, 90), fill=color)
    im.alpha_composite(emoji_img(emoji, 60), (18, 18))
    im.save(path, "PNG", optimize=True)

# (id, name, tray emoji, category, stickers)
PACKS = [
    ("funny_reactions", "Funny Reactions", "😂", "funny", [
        ("LOL", "😂"), ("OMG", "😱"), ("Bruh", "😑"), ("Same!", "🙋"), ("Nope", "🙅"),
        ("Really?", "🤨"), ("I'm dead", "💀"), ("Mood", "😌"), ("No way!", "😳"),
        ("Facepalm", "🤦"), ("Yasss!", "🙌"), ("Whatever", "🙄")]),
    ("love_hearts", "Love & Hearts", "💖", "love", [
        ("Love you", "❤️"), ("Miss you", "🥺"), ("You're mine", "💞"), ("Hugs", "🤗"),
        ("Kisses", "😘"), ("Thinking of you", "💭"), ("Cutie", "🥰"), ("Always", "💍"),
        ("Be mine", "💌"), ("XOXO", "💋"), ("Sweetheart", "🍬"), ("Forever us", "💑")]),
    ("hello_bye", "Hello & Bye", "👋", "greetings", [
        ("Hi!", "👋"), ("Hello", "😊"), ("How are you?", "🙂"), ("Thank you", "🙏"),
        ("Welcome", "🤗"), ("Take care", "💐"), ("See you", "👀"), ("Bye bye", "👋"),
        ("Get well soon", "🤒"), ("Sorry", "😔"), ("Good luck", "🍀"), ("Congrats!", "🎉")]),
    ("party_time", "Birthday & Party", "🎂", "celebrations", [
        ("Happy Birthday", "🎂"), ("Party time!", "🥳"), ("Cheers!", "🥂"), ("Best wishes", "🎁"),
        ("Happy Anniversary", "💐"), ("Let's celebrate", "🎊"), ("Cake time", "🍰"), ("Surprise!", "🎈"),
        ("You rock!", "🤘"), ("Well done", "🏆"), ("Happy New Year", "🎆"), ("Wedding vibes", "💒")]),
    ("desi_replies", "Desi Replies", "😎", "desi", [
        ("Kya scene hai?", "😎"), ("Chill kar yaar", "😌"), ("Bas kar pagle", "🤣"),
        ("Haye main mar jawan", "😍"), ("Theek hai boss", "👍"), ("Acha ji?", "🤨"),
        ("Chai pe chalo", "☕"), ("Uff tauba", "🙈"), ("Kaam ki baat karo", "🙄"),
        ("Hahaha", "😂"), ("Nahi yaar", "🙅"), ("Sab set hai", "👌")]),
    ("islamic_greetings", "Islamic Greetings", "🕌", "islamic", [
        ("Assalam o Alaikum", "👋", "السلام علیکم"), ("Walaikum Assalam", "🤝", "وعلیکم السلام"),
        ("JazakAllah Khair", "🤲", "جزاک اللہ خیر"), ("MashaAllah", "✨", "ماشاءاللہ"),
        ("InshaAllah", "🤲", "ان شاءاللہ"), ("SubhanAllah", "🌙", "سبحان اللہ"),
        ("Alhamdulillah", "💚", "الحمدللہ"), ("Ameen", "🤲", "آمین"),
        ("Jumma Mubarak", "🕌", "جمعہ مبارک"), ("Dua mein yaad rakhna", "📿", "دعا میں یاد رکھنا"),
        ("Astaghfirullah", "😔", "استغفراللہ"), ("Allah Hafiz", "👋", "اللہ حافظ")]),
    ("subah_shaam", "Good Morning & Night", "🌅", "greetings", [
        ("Subah Bakhair", "🌅", "صبح بخیر"), ("Good Morning", "☀️"), ("Utho bhai subah ho gayi", "⏰"),
        ("Chai ready hai?", "☕"), ("Have a nice day", "🌻"), ("Shab Bakhair", "🌙", "شب بخیر"),
        ("Good Night", "😴"), ("So jao ab", "🛌"), ("Sweet dreams", "💤"),
        ("Neend aa rahi hai", "🥱"), ("Jaldi so jana", "🌜"), ("Kal milte hain", "👋")]),
    ("pyar_family", "Pyar & Family", "❤️", "love", [
        ("Miss you yaar", "🥺"), ("Love you", "❤️"), ("Ammi ki dua", "🤲"),
        ("Abbu ki jaan", "👨"), ("Meri jaan", "😘"), ("Naraz ho?", "😢"),
        ("Sorry na", "🙏"), ("Tum best ho", "🌟"), ("Bhai jaan", "💪"),
        ("Behna", "🌸"), ("Family first", "🏡"), ("Khush raho", "😊")]),
    ("eid_ramadan", "Eid & Ramadan", "🌙", "islamic", [
        ("Eid Mubarak", "🌙", "عید مبارک"), ("Ramadan Kareem", "🏮", "رمضان کریم"),
        ("Chaand Raat Mubarak", "🌙", "چاند رات مبارک"), ("Eidi kahan hai?", "💸"),
        ("Iftar time!", "🍉"), ("Sehri ka waqt", "⏰"), ("Roza rakha hai", "🤲"),
        ("Pakoray ready", "🍤"), ("Eid ul Adha Mubarak", "🐐", "عید الاضحیٰ مبارک"),
        ("Shab e Barat Mubarak", "✨", "شب برات مبارک"), ("Taraweeh chalo", "🕌"),
        ("Mithai khao", "🍬")]),
]

def main():
    os.makedirs(ASSETS, exist_ok=True)
    packs_json = []
    for pi, (pid, name, tray_emoji, category, items) in enumerate(PACKS):
        folder = os.path.join(ASSETS, pid); os.makedirs(folder, exist_ok=True)
        tray(tray_emoji, PALETTE[pi * 2 % len(PALETTE)], os.path.join(folder, "tray.png"))
        stickers = []
        for i, it in enumerate(items):
            text, emo = it[0], it[1]
            urdu = it[2] if len(it) > 2 else None
            color = PALETTE[(i + pi * 3) % len(PALETTE)]
            im = sticker(text, color, emo, urdu, shape="circle" if i % 3 == 2 else "blob", seed=pi * 100 + i)
            fn = f"{i + 1:02d}.webp"
            save_webp(im, os.path.join(folder, fn))
            stickers.append({"image_file": fn, "emojis": [emo], "accessibility_text": text})
        packs_json.append({
            "identifier": pid, "name": name, "publisher": PUBLISHER, "category": category,
            "tray_image_file": "tray.png", "image_data_version": "1",
            "avoid_cache": False, "animated_sticker_pack": False,
            "publisher_email": "", "publisher_website": "", "privacy_policy_website": "",
            "license_agreement_website": "", "stickers": stickers})
    with open(os.path.join(ASSETS, "contents.json"), "w", encoding="utf-8") as f:
        json.dump({"android_play_store_link": "", "ios_app_store_link": "",
                   "sticker_packs": packs_json}, f, ensure_ascii=False, indent=2)
    print("ok", len(packs_json), "packs")

if __name__ == "__main__":
    main()
