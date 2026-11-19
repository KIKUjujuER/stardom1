#!/usr/bin/env python3
"""Derive the web-sized A-DOS brand assets from the 4K masters.

The 4K masters in resources/ are the source of truth for the brand, but they
are far too big to render in a README. This writes a transparent lockup and a
transparent mark at a sensible screen size, keeping the original files intact.

Usage: python3 tools/make-brand-assets.py
"""
import os

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "resources")

# source -> (output, width in px)
JOBS = [
    ("a-dos-logo-transparent-4k.png", "a-dos-logo.png", 1000),
    ("a-dos-icon-transparent-4k.png", "a-dos-icon.png", 1024),
]


def main():
    for src_name, out_name, width in JOBS:
        src = os.path.join(RES, src_name)
        out = os.path.join(RES, out_name)
        im = Image.open(src).convert("RGBA")
        ratio = width / im.width
        size = (width, max(1, round(im.height * ratio)))
        im = im.resize(size, Image.LANCZOS)
        im.save(out, "PNG", optimize=True)
        print(f"{out_name}: {size[0]}x{size[1]} "
              f"({os.path.getsize(out) / 1024:.0f} KB)")


if __name__ == "__main__":
    main()
