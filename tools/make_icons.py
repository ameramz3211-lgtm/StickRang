"""Launcher PNGs (mdpi..xxxhdpi) and the 512 Play Store icon, same design as the adaptive icon XML."""
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app/src/main/res")
P, K, Y = (108, 43, 217), (255, 61, 127), (255, 201, 77)

def icon(size, rounded=True):
    s = size / 108 * 4  # draw at 4x, then shrink
    W = int(108 * s)
    # diagonal gradient, purple top-left -> pink bottom-right
    grad = Image.new("L", (W, W))
    grad.putdata([min(255, (x + y) * 255 // (2 * W - 2)) for y in range(W) for x in range(W)])
    bg = Image.composite(Image.new("RGB", (W, W), K), Image.new("RGB", (W, W), P), grad)
    im = bg.convert("RGBA")
    d = ImageDraw.Draw(im)
    f = lambda v: v * s
    # sticker body (rounded square with the bottom-right corner cut)
    body = Image.new("L", (W, W), 0)
    bd = ImageDraw.Draw(body)
    bd.rounded_rectangle([f(28), f(30), f(80), f(80)], radius=f(12), fill=255)
    bd.polygon([(f(80), f(62)), (f(80), f(81)), (f(61), f(81))], fill=0)
    bd.rectangle([f(62), f(62), f(81), f(81)], fill=0)
    bd.polygon([(f(80), f(62)), (f(62), f(80)), (f(62), f(62))], fill=255)
    im.paste((255, 255, 255, 255), (0, 0), body)
    d.polygon([(f(80), f(62)), (f(62), f(80)), (f(62), f(70)), (f(70), f(62))], fill=Y)
    d.ellipse([f(42), f(45.5), f(51), f(54.5)], fill=P)
    d.line([f(58), f(50), f(67), f(50)], fill=P, width=int(f(4)))
    d.arc([f(44), f(52), f(64), f(66)], 20, 160, fill=K, width=int(f(4.5)))
    out = im.resize((size, size), Image.LANCZOS)
    if rounded:
        m = Image.new("L", (size * 4, size * 4), 0)
        ImageDraw.Draw(m).rounded_rectangle([0, 0, size * 4, size * 4], radius=size * 4 // 5, fill=255)
        out.putalpha(m.resize((size, size), Image.LANCZOS))
    return out

for name, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
    icon(px).save(os.path.join(RES, f"mipmap-{name}", "ic_launcher.png"))
icon(512, rounded=False).convert("RGB").save(os.path.join(ROOT, "store/icon-512.png"))
print("ok")
