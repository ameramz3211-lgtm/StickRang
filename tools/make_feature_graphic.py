"""Play Store feature graphic (1024x500) from the app's own sticker art."""
import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
A = os.path.join(ROOT, "app/src/main/assets/")
BRAND, PINK = (108, 43, 217), (255, 61, 127)
W, H = 1024, 500

g = Image.new("L", (W, H))
g.putdata([min(255, (x + y) * 255 // (W + H - 2)) for y in range(H) for x in range(W)])
im = Image.composite(Image.new("RGBA", (W, H), PINK + (255,)), Image.new("RGBA", (W, H), BRAND + (255,)), g)
d = ImageDraw.Draw(im)


def put(path, size, x, y, rot):
    s = Image.open(A + path).convert("RGBA").resize((size, size), Image.LANCZOS).rotate(rot, expand=True, resample=Image.BICUBIC)
    im.alpha_composite(s, (x, y))


put("funny_reactions/01.webp", 210, 620, 10, 6)
put("love_hearts/01.webp", 200, 810, 40, -8)
put("party_time/01.webp", 200, 600, 260, -5)
put("eid_ramadan/01.webp", 200, 800, 270, 7)
F = os.path.join(ROOT, "tools/fonts/LilitaOne-Regular.ttf")
d.text((44, 120), "StickRang", font=ImageFont.truetype(F, 112), fill="white", stroke_width=6, stroke_fill=(60, 15, 120))
d.text((50, 250), "Sticker Maker · Cut-out · GIF · Text", font=ImageFont.truetype(F, 32), fill=(255, 226, 120))
d.text((50, 300), "No watermark. Make it yours.", font=ImageFont.truetype(F, 30), fill="white")
im.convert("RGB").save(os.path.join(ROOT, "store/feature-graphic-1024x500.png"))
print("ok")
