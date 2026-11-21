# Génère les icônes de l'application (python3 graphisme/icone.py)
from PIL import Image, ImageDraw
import os
S = 1024
def icone():
    im = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    # fond dégradé arrondi
    fond = Image.new('RGBA', (S, S))
    fd = ImageDraw.Draw(fond)
    for y in range(S):
        t = y / S
        c = (int(196 - 50 * t), int(98 - 30 * t), int(66 - 20 * t), 255)
        fd.line([(0, y), (S, y)], fill=c)
    masque = Image.new('L', (S, S), 0)
    ImageDraw.Draw(masque).rounded_rectangle([0, 0, S - 1, S - 1], radius=230, fill=255)
    im.paste(fond, (0, 0), masque)
    d = ImageDraw.Draw(im)
    # livre ouvert
    cx, cy = S // 2, S // 2 + 40
    blanc = (255, 250, 240, 255)
    ombre = (120, 50, 30, 120)
    for dx, col in ((14, ombre), (0, blanc)):
        d.polygon([(cx, cy - 210 + dx), (cx - 330, cy - 270 + dx), (cx - 330, cy + 190 + dx), (cx, cy + 250 + dx)], fill=col)
        d.polygon([(cx, cy - 210 + dx), (cx + 330, cy - 270 + dx), (cx + 330, cy + 190 + dx), (cx, cy + 250 + dx)], fill=col)
    d.line([(cx, cy - 210), (cx, cy + 250)], fill=(181, 84, 58, 255), width=10)
    trait = (181, 84, 58, 200)
    for i in range(5):
        y = cy - 170 + i * 75
        d.line([(cx - 280, y - 40), (cx - 50, y - 5)], fill=trait, width=18)
        d.line([(cx + 50, y - 5), (cx + 280, y - 40)], fill=trait, width=18)
    # « W » → page : petit signet
    d.polygon([(cx + 170, cy - 262), (cx + 240, cy - 272), (cx + 240, cy - 120), (cx + 205, cy - 150), (cx + 170, cy - 112)], fill=(70, 120, 200, 255))
    return im
im = icone()
racine = os.path.join(os.path.dirname(__file__), '..', 'res')
for nom, t in (('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)):
    dos = os.path.join(racine, 'mipmap-' + nom)
    os.makedirs(dos, exist_ok=True)
    im.resize((t, t), Image.LANCZOS).save(os.path.join(dos, 'ic_launcher.png'))
im.resize((192, 192), Image.LANCZOS).save(os.path.join(www, 'icone.png'))
im.resize((512, 512), Image.LANCZOS).save(os.path.join(os.path.dirname(__file__), 'icone-512.png'))
