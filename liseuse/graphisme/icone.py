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


def fond(haut, bas, arrondi=True):
    f = Image.new('RGBA', (S, S))
    d = ImageDraw.Draw(f)
    for y in range(S):
        t = y / S
        d.line([(0, y), (S, y)], fill=tuple(int(haut[k] + (bas[k] - haut[k]) * t) for k in range(3)) + (255,))
    if not arrondi:
        return f
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


def motif(v):
    """Le dessin seul (livre ou lettre), sur fond transparent."""
    nom, haut, bas, pages, traits, signet, forme = v
    im = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    if forme == 'lettre':
        lettre(d, pages)
    else:
        livre(d, pages, traits, signet)
    return im


def icone(v):
    """Icône classique (Android 7 et moins) : carré arrondi."""
    im = fond(v[1], v[2])
    m = motif(v)
    im.alpha_composite(m)
    return im


def premier_plan(v):
    """Icône adaptative : le dessin réduit pour tenir dans la zone sûre (cercle de 66 dp sur 108 dp)."""
    m = motif(v)
    k = 0.70
    petit = m.resize((int(S * k), int(S * k)), Image.LANCZOS)
    im = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    o = (S - petit.width) // 2
    im.alpha_composite(petit, (o, o))
    return im


def monochrome(avant):
    """Silhouette pour les icônes à thème (Android 13+) : la forme du dessin, en une seule teinte."""
    a = avant.getchannel('A')
    im = Image.new('RGBA', avant.size, (255, 255, 255, 0))
    im.putalpha(a)
    return im


TAILLES = (('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192))
ADAPTATIVES = (('mdpi', 108), ('hdpi', 162), ('xhdpi', 216), ('xxhdpi', 324), ('xxxhdpi', 432))

ANYDPI = os.path.join(RES, 'mipmap-anydpi-v26')
os.makedirs(ANYDPI, exist_ok=True)


def xml_adaptatif(nom):
    return f"""<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/{nom}_fond" />
    <foreground android:drawable="@mipmap/{nom}_avant" />
    <monochrome android:drawable="@mipmap/{nom}_mono" />
</adaptive-icon>
"""


for i, v in enumerate(VARIANTES, 1):
    noms = ['ic_icone%d' % i] + (['ic_launcher'] if i == 1 else [])
    classique = icone(v)
    arriere = fond(v[1], v[2], arrondi=False)
    avant = premier_plan(v)
    mono = monochrome(avant)
    for dens, t in TAILLES:
        dos = os.path.join(RES, 'mipmap-' + dens)
        os.makedirs(dos, exist_ok=True)
        for nom in noms:
            classique.resize((t, t), Image.LANCZOS).save(os.path.join(dos, nom + '.png'))
    for dens, t in ADAPTATIVES:
        dos = os.path.join(RES, 'mipmap-' + dens)
        for nom in noms:
            arriere.resize((t, t), Image.LANCZOS).save(os.path.join(dos, nom + '_fond.png'))
            avant.resize((t, t), Image.LANCZOS).save(os.path.join(dos, nom + '_avant.png'))
            mono.resize((t, t), Image.LANCZOS).save(os.path.join(dos, nom + '_mono.png'))
    for nom in noms:
        open(os.path.join(ANYDPI, nom + '.xml'), 'w').write(xml_adaptatif(nom))
    if i == 1:
        classique.resize((512, 512), Image.LANCZOS).save(os.path.join(ICI, 'icone-512.png'))
        # aperçu : découpe ronde comme le ferait un lanceur
        apercu = arriere.copy()
        apercu.alpha_composite(avant)
        masque = Image.new('L', (S, S), 0)
        ImageDraw.Draw(masque).ellipse([0, 0, S - 1, S - 1], fill=255)
        rond = Image.new('RGBA', (S, S), (0, 0, 0, 0))
        rond.paste(apercu, (0, 0), masque)
        rond.resize((256, 256), Image.LANCZOS).save(os.path.join(ICI, 'apercu-rond.png'))
