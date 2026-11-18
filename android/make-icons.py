#!/usr/bin/env python3
"""make-icons.py - generate the Android launcher icons from the A-DOS logo.

Takes the single logo image in resources/ (A-DOS-Logo.*, any of jpeg/png/webp)
and produces every launcher asset the app needs:

  mipmap-*/ic_launcher.png            legacy square icon  (API 21-25)
  mipmap-*/ic_launcher_round.png      legacy round icon   (API 21-25)
  mipmap-*/ic_launcher_foreground.png adaptive foreground (API 26+), the logo
                                      centred inside the 72/108 safe zone
  values/colors.xml                   ic_launcher_background, sampled from the
                                      logo's border so it blends with it

The logo is fitted (contain, aspect preserved) and centred. The background
colour is taken from the logo's border, so the adaptive background and the
logo's own background are the same colour and the seam disappears - the icon
reads as that colour with the A-DOS mark in the middle.

Usage:
    ./android/make-icons.py [--source FILE] [--check]

    --source FILE   logo to use (default: the largest *logo* image in resources/)
    --check         verify the committed assets match a fresh render; write
                    nothing, exit 1 on a mismatch.

Requires Python 3 with Pillow:
    python3 -m pip install --user pillow
"""

import argparse
import glob
import io
import os
import sys
from collections import Counter

try:
    from PIL import Image
except ImportError:
    sys.exit("Pillow is required: python3 -m pip install --user pillow")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
RES = os.path.join(ROOT, "resources")
RES_OUT = os.path.join(HERE, "app", "src", "main", "res")

# density -> (legacy icon size px, adaptive canvas size px)
DENSITIES = {
    "mdpi":    (48, 108),
    "hdpi":    (72, 162),
    "xhdpi":   (96, 216),
    "xxhdpi":  (144, 324),
    "xxxhdpi": (192, 432),
}

# the adaptive icon is 108x108dp with a 72x72dp safe zone in the middle
SAFE_ZONE = 72.0 / 108.0
# how much of the square / safe zone the logo fills (a little margin looks best)
LEGACY_FILL = 0.92
FOREGROUND_FILL = 0.94

# Background for a logo that carries its own transparency (the A-DOS mark): a
# dark navy that the metallic mark, the blue glow and the green prompt all read
# well on. An opaque logo uses its own sampled border colour instead.
FIXED_BG = (0x0D, 0x0D, 0x1F)


def find_source():
    cands = []
    for ext in ("jpeg", "jpg", "png", "webp", "JPEG", "JPG", "PNG", "WEBP"):
        cands += glob.glob(os.path.join(RES, "*." + ext))
    if not cands:
        sys.exit("no logo image found in resources/ (pass --source)")

    def base(c):
        return os.path.basename(c).lower()

    # Prefer, in order: the cleaned A-DOS *mark* (transparent, what the launcher
    # shows), then any A-DOS artwork, then anything with "logo".
    ados = [c for c in cands if "ados" in base(c).replace("-", "")]
    mark = [c for c in ados if "mark" in base(c)]
    if mark:
        return max(mark, key=os.path.getsize)
    if ados:
        return max(ados, key=os.path.getsize)
    named = [c for c in cands if "logo" in base(c)]
    pool = named or cands
    return max(pool, key=os.path.getsize)


def border_color(img):
    """Most common colour along the image border - the logo's background."""
    rgb = img.convert("RGB")
    w, h = rgb.size
    px = rgb.load()
    counter = Counter()
    sx = max(1, w // 64)
    sy = max(1, h // 64)
    for x in range(0, w, sx):
        counter[px[x, 0]] += 1
        counter[px[x, h - 1]] += 1
    for y in range(0, h, sy):
        counter[px[0, y]] += 1
        counter[px[w - 1, y]] += 1
    return counter.most_common(1)[0][0]


def contain(logo, box):
    """Logo scaled (aspect preserved) to fit within box x box, as RGBA."""
    out = logo.copy()
    out.thumbnail((box, box), Image.LANCZOS)
    return out


def render():
    """Returns {relative path -> bytes} of every generated asset."""
    src = ARGS.source or find_source()
    print("logo:", os.path.relpath(src, ROOT), file=sys.stderr)
    logo = Image.open(src).convert("RGBA")
    # A logo with real transparency (the A-DOS mark) goes on a fixed dark
    # background; a flat/opaque logo gets its background sampled from its border.
    transparent = logo.getchannel("A").getextrema()[0] < 16
    bg = FIXED_BG if transparent else border_color(logo)
    hexbg = "#%02X%02X%02X" % bg
    print("background:", hexbg, "(transparent logo)" if transparent else "(sampled)",
          file=sys.stderr)

    out = {}
    for dens, (lsize, csize) in DENSITIES.items():
        # legacy square + round: the logo centred on the background
        legacy = Image.new("RGBA", (lsize, lsize), bg + (255,))
        fit = contain(logo, int(lsize * LEGACY_FILL))
        legacy.alpha_composite(fit, ((lsize - fit.width) // 2, (lsize - fit.height) // 2))
        legacy_rgb = legacy.convert("RGB")
        for name in ("ic_launcher.png", "ic_launcher_round.png"):
            buf = io.BytesIO()
            legacy_rgb.save(buf, "PNG")
            out[f"mipmap-{dens}/{name}"] = buf.getvalue()

        # adaptive foreground: the logo in the safe zone on transparency (the
        # adaptive background colour fills the rest, matching the logo's own)
        fg = Image.new("RGBA", (csize, csize), (0, 0, 0, 0))
        fitf = contain(logo, int(csize * SAFE_ZONE * FOREGROUND_FILL))
        fg.alpha_composite(fitf, ((csize - fitf.width) // 2, (csize - fitf.height) // 2))
        buf = io.BytesIO()
        fg.save(buf, "PNG")
        out[f"mipmap-{dens}/ic_launcher_foreground.png"] = buf.getvalue()

    colors = (
        '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
        '    <!-- sampled from the A-DOS logo by android/make-icons.py -->\n'
        f'    <color name="ic_launcher_background">{hexbg}</color>\n'
        '</resources>\n'
    )
    out["values/colors.xml"] = colors.encode("utf-8")
    return out


def main():
    assets = render()
    if ARGS.check:
        stale = []
        for rel, data in assets.items():
            path = os.path.join(RES_OUT, rel)
            if not os.path.exists(path) or open(path, "rb").read() != data:
                stale.append(rel)
        if stale:
            print("out of date:\n  " + "\n  ".join(stale), file=sys.stderr)
            sys.exit(1)
        print("icons up to date", file=sys.stderr)
        return
    for rel, data in assets.items():
        path = os.path.join(RES_OUT, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as f:
            f.write(data)
    print("icons written to", os.path.relpath(RES_OUT, ROOT), file=sys.stderr)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--source")
    ap.add_argument("--check", action="store_true")
    ARGS = ap.parse_args()
    main()
