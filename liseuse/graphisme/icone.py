# Génère les icônes de l'application (python3 graphisme/icone.py)
# ic_launcher = icône par défaut ; ic_icone1…8 = icônes au choix dans les réglages.
from PIL import Image, ImageDraw, ImageFont
import os

S = 1024
ICI = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(ICI, '..', 'res')

# (nom, haut du dégradé, bas du dégradé, pages, traits, signet, motif)
VARIANTES = [
    ('classique', (196, 98, 66), (146, 68, 46), (255, 250, 240), (181, 84, 58), (70, 120, 200), 'livre'),
    ('ocean', (52, 104, 168), (24, 58, 110), (245, 249, 255), (52, 104, 168), (240, 170, 60), 'livre'),
    ('foret', (66, 128, 88), (30, 78, 52), (247, 252, 244), (56, 110, 74), (220, 90, 70), 'livre'),
    ('lavande', (138, 110, 196), (92, 66, 150), (252, 249, 255), (120, 94, 180), (240, 190, 70), 'livre'),
    ('nuit', (44, 44, 52), (14, 14, 20), (236, 208, 140), (60, 52, 40), (190, 70, 60), 'livre'),
    ('papier', (250, 245, 232), (232, 222, 200), (181, 84, 58), (255, 250, 240), (60, 110, 190), 'livre-inverse'),
    ('monogramme', (196, 98, 66), (126, 52, 34), (255, 248, 236), None, None, 'lettre'),
    ('rose', (230, 120, 150), (180, 70, 110), (255, 250, 252), (200, 90, 128), (90, 160, 200), 'livre'),
]


def fond(haut, bas):
    f = Image.new('RGBA', (S, S))
    d = ImageDraw.Draw(f)
    for y in range(S):
        t = y / S
        d.line([(0, y), (S, y)], fill=tuple(int(haut[k] + (bas[k] - haut[k]) * t) for k in range(3)) + (255,))
    m = Image.new('L', (S, S), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, S - 1, S - 1], radius=230, fill=255)
    im = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    im.paste(f, (0, 0), m)
    return im


def livre(d, pages, traits, signet):
    cx, cy = S // 2, S // 2 + 40
    ombre = (0, 0, 0, 70)
    for dx, col in ((14, ombre), (0, pages + (255,))):
        d.polygon([(cx, cy - 210 + dx), (cx - 330, cy - 270 + dx), (cx - 330, cy + 190 + dx), (cx, cy + 250 + dx)], fill=col)
        d.polygon([(cx, cy - 210 + dx), (cx + 330, cy - 270 + dx), (cx + 330, cy + 190 + dx), (cx, cy + 250 + dx)], fill=col)
    d.line([(cx, cy - 210), (cx, cy + 250)], fill=traits + (255,), width=10)
    for i in range(5):
        y = cy - 170 + i * 75
        d.line([(cx - 280, y - 40), (cx - 50, y - 5)], fill=traits + (200,), width=18)
        d.line([(cx + 50, y - 5), (cx + 280, y - 40)], fill=traits + (200,), width=18)
    d.polygon([(cx + 170, cy - 262), (cx + 240, cy - 272), (cx + 240, cy - 120), (cx + 205, cy - 150), (cx + 170, cy - 112)], fill=signet + (255,))


def lettre(d, couleur):
    police = None
    for chemin in ('/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf', '/usr/share/fonts/truetype/liberation/LiberationSerif-Bold.ttf'):
        if os.path.exists(chemin):
            police = ImageFont.truetype(chemin, 640)
            break
    if police is None:
        police = ImageFont.load_default()
    d.text((S // 2, S // 2 + 20), 'L', font=police, fill=couleur + (255,), anchor='mm')
    d.line([(S // 2 - 200, S // 2 + 300), (S // 2 + 200, S // 2 + 300)], fill=couleur + (200,), width=14)


def icone(v):
    nom, haut, bas, pages, traits, signet, motif = v
    im = fond(haut, bas)
    d = ImageDraw.Draw(im)
    if motif == 'lettre':
        lettre(d, pages)
    else:
        livre(d, pages, traits, signet)
    return im


TAILLES = (('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192))

for i, v in enumerate(VARIANTES, 1):
    im = icone(v)
    for dens, t in TAILLES:
        dos = os.path.join(RES, 'mipmap-' + dens)
        os.makedirs(dos, exist_ok=True)
        petite = im.resize((t, t), Image.LANCZOS)
        petite.save(os.path.join(dos, 'ic_icone%d.png' % i))
        if i == 1:
            petite.save(os.path.join(dos, 'ic_launcher.png'))
    if i == 1:
        im.resize((512, 512), Image.LANCZOS).save(os.path.join(ICI, 'icone-512.png'))
