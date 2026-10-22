# Renders the Daylight theme's wallpaper: a light, cool gradient with large soft colour blobs.
# Deterministic (no randomness), so re-running it gives the same file.
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

W, H = 1672, 941
root = Path(__file__).resolve().parent.parent

def hex_rgb(h): return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))

top, bottom = hex_rgb('#f4f7fc'), hex_rgb('#e3eaf6')
img = Image.new('RGB', (W, H))
d = ImageDraw.Draw(img)
for y in range(H):
    t = y / (H - 1)
    d.line([(0, y), (W, y)], fill=tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))

# Colour, centre and radius of each blob; blurred hard so they read as light, not shapes.
blobs = [('#b9d4ff', (300, 180), 420), ('#d6cdfb', (1350, 140), 380), ('#c4efe4', (1180, 760), 400),
         ('#cfe6fb', (520, 820), 360), ('#e2d9fb', (860, 420), 300)]
for colour, (cx, cy), r in blobs:
    mask = Image.new('L', (W, H), 0)
    ImageDraw.Draw(mask).ellipse((cx - r, cy - r, cx + r, cy + r), fill=int(255 * 0.7))
    img.paste(Image.new('RGB', (W, H), hex_rgb(colour)), (0, 0), mask.filter(ImageFilter.GaussianBlur(160)))

img.save(root / 'app' / 'assets' / 'daylight-wallpaper.png', optimize=True)
print('wallpaper written')
