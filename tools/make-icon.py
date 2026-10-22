# Renders Luma's square launcher icon into mipmap density folders.
# Fire OS draws sideloaded apps' square icon centred on a grey tile (banner ignored),
# so the icon is full-bleed and opaque with the crescent kept inside the centre.
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageChops

S = 1024
root = Path(__file__).resolve().parent.parent
res = root / 'app' / 'res'

def radial(size, inner, outer):
    g = Image.new('RGB', (size, size))
    px = g.load()
    c = size / 2
    for y in range(size):
        for x in range(size):
            t = min(1.0, ((x - c) ** 2 + (y - c) ** 2) ** 0.5 / (c * 1.25))
            px[x, y] = tuple(int(inner[i] + (outer[i] - inner[i]) * t) for i in range(3))
    return g

bg = radial(S, (24, 34, 82), (3, 9, 32))

# Crescent = disc minus offset disc.
mask = Image.new('L', (S, S), 0)
d = ImageDraw.Draw(mask)
# Sized so the visible crescent spans ~70% of the icon and its bounding box is centred.
cx, cy, r = 575, 508, 369
d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=255)
cut = Image.new('L', (S, S), 0)
ImageDraw.Draw(cut).ellipse((cx - r + 168, cy - r - 78, cx + r + 134, cy + r - 78), fill=255)
mask = ImageChops.subtract(mask, cut)

glow = Image.new('RGB', (S, S), (174, 148, 245))
bg.paste(glow, (0, 0), mask.filter(ImageFilter.GaussianBlur(60)).point(lambda v: int(v * 0.8)))
bg.paste(glow, (0, 0), mask.filter(ImageFilter.GaussianBlur(18)))

# Lit face: white at the outer edge fading to lavender.
face = Image.new('RGB', (S, S))
fp = face.load()
for y in range(S):
    for x in range(S):
        t = min(1.0, max(0.0, (x - 140) / 520))
        fp[x, y] = (int(255 - 60 * t), int(255 - 85 * t), 255)
bg.paste(face, (0, 0), mask.filter(ImageFilter.GaussianBlur(2)))

for folder, px in {'mipmap-mdpi': 48, 'mipmap-hdpi': 72, 'mipmap-xhdpi': 96,
                   'mipmap-xxhdpi': 144, 'mipmap-xxxhdpi': 192}.items():
    out = res / folder
    out.mkdir(exist_ok=True)
    bg.resize((px, px), Image.LANCZOS).save(out / 'luma_launcher.png')
(root / 'build').mkdir(exist_ok=True)
bg.resize((512, 512), Image.LANCZOS).save(root / 'build' / 'luma_launcher_512.png')
print('icons written')
