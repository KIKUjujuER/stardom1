#!/usr/bin/env python3
"""Turn the box-drawing diagrams in the docs into SVG.

The diagrams in this repository are drawn with Unicode box characters inside
fenced code blocks. Hand-drawn box art drifts: a border ends up one column
left on one row and one column right on the next, and the diagram no longer
looks like a box. A monospace font does not help, because the drift is in the
source, not in the renderer.

So the art is not rendered as text. It is *parsed*:

  1. the corners (┌ ┐ └ ┘) are found and matched into rectangles;
  2. every rectangle is re-snapped onto the column its own side rows agree
     on, which repairs the drift;
  3. a rectangle that is too narrow for the text inside it is widened, and
     its parent follows, so no border ever cuts through a word;
  4. the free-standing strokes (the connectors between boxes, the tree
     branches, the rules under a table header) are kept as lines;
  5. the rectangles and the lines are drawn as vector paths and every
     remaining character is placed in the middle of its own cell.

The result is an SVG whose borders are straight, because they are geometry
rather than glyphs, and whose text still sits exactly where the ASCII put it.

Usage:
    python3 boxart.py <file.md> [...]        rewrite the markdown, write the SVGs
    python3 boxart.py --refresh <file.md>    re-render the SVGs from the text copies

The markdown file is rewritten in place: every diagram block is replaced by
the SVG image plus a collapsed copy of the ASCII art, so the text version
stays searchable and copyable. The text copy is the source of truth: edit it
and run the tool with --refresh, and the drawing follows.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from collections import Counter

# --------------------------------------------------------------------------
# the box alphabet
# --------------------------------------------------------------------------

# Which strokes each box character draws: L(eft), R(ight), U(p), D(own).
STROKES = {
    "─": "LR",
    "│": "UD",
    "┌": "RD",
    "┐": "LD",
    "└": "RU",
    "┘": "LU",
    "├": "RUD",
    "┤": "LUD",
    "┬": "LRD",
    "┴": "LRU",
    "┼": "LRUD",
    "═": "LR",
    "║": "UD",
    "╔": "RD",
    "╗": "LD",
    "╚": "RU",
    "╝": "LU",
    "╠": "RUD",
    "╣": "LUD",
    "╦": "LRD",
    "╩": "LRU",
    "╬": "LRUD",
}

BOX_CHARS = set(STROKES)

TOP_LEFT = "┌╔"
TOP_RIGHT = "┐╗"
BOTTOM_LEFT = "└╚"
BOTTOM_RIGHT = "┘╝"

# How far a border may wander from the column it is supposed to be on.
DRIFT = 2


def strokes(ch: str) -> str:
    return STROKES.get(ch, "")


def is_box(ch: str) -> bool:
    return ch in BOX_CHARS


def has_lr(ch: str) -> bool:
    s = strokes(ch)
    return "L" in s or "R" in s


def has_ud(ch: str) -> bool:
    s = strokes(ch)
    return "U" in s or "D" in s


# --------------------------------------------------------------------------
# geometry helpers
# --------------------------------------------------------------------------


class Rect:
    """A box of a frame."""

    def __init__(self, top: int, bottom: int, left: int, right: int):
        self.top = top
        self.bottom = bottom
        self.left = left
        self.right = right
        # The columns the border was actually drawn on, in the source. A
        # hand-drawn border wanders, so the box is drawn straight but the
        # stray copies of the border have to be recognised as belonging to
        # it and not drawn a second time.
        self.left_span = (left, left)
        self.right_span = (right, right)

    def __repr__(self) -> str:  # pragma: no cover - debugging aid
        return f"<Rect {self.top}..{self.bottom} {self.left}..{self.right}>"


def cell_at(lines: list[str], row: int, col: int) -> str:
    if 0 <= row < len(lines) and 0 <= col < len(lines[row]):
        return lines[row][col]
    return " "


def bars_of(line: str) -> list[int]:
    """The columns of a row that carry a vertical stroke."""
    return [i for i, ch in enumerate(line) if has_ud(ch)]


def h_runs(line: str) -> list[tuple[int, int]]:
    """Maximal runs of characters that draw a horizontal stroke."""
    runs = []
    c = 0
    n = len(line)
    while c < n:
        if has_lr(line[c]):
            j = c
            while j + 1 < n and has_lr(line[j + 1]):
                j += 1
            runs.append((c, j))
            c = j + 1
        else:
            c += 1
    return runs


def v_runs(lines: list[str]) -> list[tuple[int, int, int]]:
    """Maximal runs of characters that draw a vertical stroke.

    Returns (col, first row, last row).
    """
    runs = []
    height = len(lines)
    width = max((len(l) for l in lines), default=0)
    for c in range(width):
        start = None
        for r in range(height + 1):
            ch = cell_at(lines, r, c) if r < height else " "
            if has_ud(ch):
                if start is None:
                    start = r
            else:
                if start is not None:
                    runs.append((c, start, r - 1))
                    start = None
    return runs


def span_covers(runs: list[tuple[int, int]], lo: int, hi: int, slack: int = DRIFT) -> bool:
    """Is there a run that covers the column range [lo, hi]?"""
    for a, b in runs:
        if a <= lo + slack and b >= hi - slack:
            return True
    return False


# --------------------------------------------------------------------------
# the diagram model
# --------------------------------------------------------------------------


class Diagram:
    """A parsed diagram: the boxes, the free lines, and the text."""

    def __init__(self, lines: list[str]):
        self.lines = [l.rstrip() for l in lines]
        self.width = max((len(l) for l in self.lines), default=0)
        self.rects = find_rects(self.lines)
        self.snap_columns()
        self.fit_content()
        self.align_neighbours()
        self.compute_lines()

    # -- the boxes ---------------------------------------------------------

    def snap_columns(self) -> None:
        """Put every side of a box on the column the side rows agree on.

        The corner characters are the best evidence of where a box starts and
        ends, but the rows in between drift: a hand-drawn border follows the
        text, so it ends up one or two columns further right on the long
        lines. The side rows are the majority, so they win.
        """
        for r in self.rects:
            for side in ("left", "right"):
                base = getattr(r, side)
                counts: Counter = Counter()
                seen = []
                for row in range(r.top + 1, r.bottom):
                    for c in bars_of(self.lines[row]):
                        if abs(c - base) <= DRIFT:
                            counts[c] += 1
                            seen.append(c)
                counts[base] += 1
                seen.append(base)
                best = max(counts, key=lambda c: (counts[c], c == base))
                setattr(r, side, best)
                setattr(r, side + "_span", (min(seen), max(seen)))

    def fit_content(self) -> None:
        """Make the boxes fit the text that was drawn inside them.

        A line of text can be longer than the box it sits in, so the border
        runs through the text. A run of text that *starts* inside a box and
        *ends* outside it has spilled, and the box is widened to hold it.
        Text that starts outside the box is an annotation next to the box and
        is left where the author put it.
        """
        for _ in range(4):
            changed = False
            for r in self.rects:
                for row in range(r.top + 1, r.bottom):
                    line = self.lines[row]
                    for a, b in text_runs(line):
                        if r.left < a <= r.right and b >= r.right:
                            if b + 1 > r.right:
                                r.right = b + 1
                                changed = True
                        elif r.left <= b < r.right and a <= r.left:
                            if a - 1 < r.left:
                                r.left = a - 1
                                changed = True
            if self.propagate():
                changed = True
            if not changed:
                break

    def propagate(self) -> bool:
        """A parent box has to be able to hold its children."""
        changed = False
        for inner in self.rects:
            for outer in self.rects:
                if inner is outer:
                    continue
                if not (outer.top < inner.top and inner.bottom < outer.bottom):
                    continue
                if not (outer.left <= inner.left and inner.right <= outer.right):
                    continue
                if inner.right + 1 > outer.right:
                    outer.right = inner.right + 1
                    changed = True
                if inner.left - 1 < outer.left:
                    outer.left = inner.left - 1
                    changed = True
        return changed

    def align_neighbours(self) -> bool:
        """Line up boxes that are stacked on top of each other.

        Two boxes one above the other are one column apart after the drift is
        repaired; they should share the same left and right border, or the
        picture looks broken. Boxes that are meant to be a different width
        are far enough apart to be left alone.
        """
        changed = False
        for a in self.rects:
            for b in self.rects:
                if a is b:
                    continue
                if not (a.bottom < b.top or b.bottom < a.top):
                    continue  # they are on top of each other, not stacked
                if a.right < b.left or b.right < a.left:
                    continue  # they do not overlap horizontally
                if abs(a.right - b.right) <= DRIFT:
                    new = max(a.right, b.right)
                    if a.right != new or b.right != new:
                        a.right = b.right = new
                        changed = True
                if abs(a.left - b.left) <= DRIFT:
                    new = min(a.left, b.left)
                    if a.left != new or b.left != new:
                        a.left = b.left = new
                        changed = True
        return changed

    # -- the free-standing strokes ----------------------------------------

    def compute_lines(self) -> None:
        """Work out which strokes are drawn and which are text.

        A stroke that a box already draws is dropped, so the border is not
        drawn twice. What is left is the connector between two boxes, the
        branch of a tree, the rule under a table header: those are drawn as
        lines.
        """
        self.h_lines: list[tuple[int, int, int]] = []  # row, first col, last col
        self.v_lines: list[tuple[int, int, int]] = []  # col, first row, last row
        self.consumed: set[tuple[int, int]] = set()

        for row, line in enumerate(self.lines):
            for a, b in h_runs(line):
                owner = self.owner_of_h(row, a, b)
                if owner is not None:
                    for c in range(a, b + 1):
                        self.consumed.add((row, c))
                    continue
                if b > a:
                    self.h_lines.append((row, a, b))
                    for c in range(a, b + 1):
                        self.consumed.add((row, c))

        for col, r0, r1 in v_runs(self.lines):
            owner = self.owner_of_v(col, r0, r1)
            if owner is not None:
                for r in range(r0, r1 + 1):
                    self.consumed.add((r, col))
                continue
            if r1 > r0:
                self.v_lines.append((col, r0, r1))
                for r in range(r0, r1 + 1):
                    self.consumed.add((r, col))

    def owner_of_h(self, row: int, a: int, b: int):
        """The box whose edge this horizontal stroke is, if any."""
        for r in self.rects:
            if row not in (r.top, r.bottom):
                continue
            if a >= r.left - DRIFT and b <= r.right + DRIFT:
                return r
        return None

    def owner_of_v(self, col: int, r0: int, r1: int):
        """The box whose side this vertical stroke is, if any."""
        for r in self.rects:
            if not (r.top - DRIFT <= r0 and r1 <= r.bottom + DRIFT):
                continue
            lo, hi = r.left_span
            if lo - DRIFT <= col <= hi + DRIFT:
                return r
            lo, hi = r.right_span
            if lo - DRIFT <= col <= hi + DRIFT:
                return r
        return None


def text_runs(line: str) -> list[tuple[int, int]]:
    """The runs of real text (not spaces, not box characters) in a row."""
    runs = []
    start = None
    for i, ch in enumerate(line):
        if ch != " " and not is_box(ch):
            if start is None:
                start = i
        else:
            if start is not None:
                runs.append((start, i - 1))
                start = None
    if start is not None:
        runs.append((start, len(line) - 1))
    return runs


def find_rects(lines: list[str]) -> list[Rect]:
    """Match the corner characters into rectangles."""
    rects = []
    seen = set()
    for r, line in enumerate(lines):
        for c, ch in enumerate(line):
            if ch not in TOP_LEFT:
                continue
            tr = None
            for c2 in range(c + 1, len(line)):
                if line[c2] in TOP_RIGHT:
                    tr = c2
                    break
            if tr is None:
                continue
            for r2 in range(r + 1, len(lines)):
                row = lines[r2]
                bl = _near(row, BOTTOM_LEFT, c)
                br = _near(row, BOTTOM_RIGHT, tr)
                if bl is None or br is None:
                    continue
                if not span_covers(h_runs(row), min(c, bl), max(tr, br)):
                    continue
                key = (r, r2, min(c, bl), max(tr, br))
                if key in seen:
                    break
                seen.add(key)
                rects.append(Rect(*key))
                break
    return rects


def _near(line: str, chars: str, col: int, slack: int = DRIFT) -> int | None:
    """The column of a character of `chars` close to `col`."""
    best = None
    for i, ch in enumerate(line):
        if ch in chars and abs(i - col) <= slack:
            if best is None or abs(i - col) < abs(best - col):
                best = i
    return best


# --------------------------------------------------------------------------
# SVG output
# --------------------------------------------------------------------------

FONT_STACK = (
    "'DejaVu Sans Mono', 'Liberation Mono', Menlo, Consolas, "
    "'Courier New', ui-monospace, monospace"
)


def render_svg(diag: Diagram, title: str, cell: float = 8.4, line_h: float = 19.0) -> str:
    """Render the parsed diagram as an SVG."""
    lines = diag.lines
    width_cells = max(diag.width, 1)
    height_cells = max(len(lines), 1)

    pad = 12.0
    stroke = 1.1
    # A monospace font is 0.6 em wide in almost every implementation, so this
    # size makes the natural advance equal the cell width. The glyphs are
    # centred in their cell anyway, so the grid stays exact even when the
    # reader's font is a little wider or narrower.
    text_size = cell / 0.6

    w = width_cells * cell + 2 * pad
    h = height_cells * line_h + 2 * pad

    def x(col: float) -> float:
        """The centre of a cell: both the text and the lines use it."""
        return pad + (col + 0.5) * cell

    def y(row: float) -> float:
        return pad + (row + 0.5) * line_h

    out = []
    out.append(
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{w:.0f}" height="{h:.0f}" '
        f'viewBox="0 0 {w:.0f} {h:.0f}" role="img" aria-label="{escape(title)}">'
    )
    out.append(
        "<style>"
        ".b{fill:#ffffff}"
        ".t{fill:#1f2328;font-family:%s;font-size:%gpx;text-anchor:middle;white-space:pre}"
        ".l{stroke:#59636e;stroke-width:%g;fill:none;stroke-linecap:butt}"
        "@media (prefers-color-scheme: dark){"
        ".b{fill:#0d1117}"
        ".t{fill:#d8dee9}"
        ".l{stroke:#8b949e}"
        "}"
        "</style>" % (FONT_STACK, text_size, stroke)
    )

    # the background, so the diagram stays readable on a dark page
    out.append(f'<rect class="b" x="0" y="0" width="{w:.0f}" height="{h:.0f}"/>')

    # the boxes
    for r in diag.rects:
        out.append(
            f'<path class="l" d="M{x(r.left):.2f} {y(r.top):.2f} '
            f"H{x(r.right):.2f} V{y(r.bottom):.2f} H{x(r.left):.2f} Z\"/>"
        )

    # the free strokes
    for row, a, b in diag.h_lines:
        out.append(
            f'<path class="l" d="M{x(a):.2f} {y(row):.2f} H{x(b):.2f}"/>'
        )
    for col, r0, r1 in diag.v_lines:
        out.append(
            f'<path class="l" d="M{x(col):.2f} {y(r0):.2f} V{y(r1):.2f}"/>'
        )

    # the text: one glyph per cell, centred in it, so the grid is exact in
    # any font and any renderer. textLength makes the renderer fit the glyph
    # into its cell as well, so even a font that is not quite monospaced
    # cannot make two letters overlap.
    baseline = 0.35 * text_size
    for r, line in enumerate(lines):
        for i, ch in enumerate(line):
            if ch == " " or (r, i) in diag.consumed:
                continue
            out.append(
                f'<text class="t" x="{x(i):.2f}" y="{y(r) + baseline:.2f}" '
                f'textLength="{cell:.2f}" lengthAdjust="spacingAndGlyphs">{escape(ch)}</text>'
            )

    out.append("</svg>")
    return "\n".join(out) + "\n"


def escape(text: str) -> str:
    return (
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")
    )


# --------------------------------------------------------------------------
# markdown surgery
# --------------------------------------------------------------------------

FENCE = re.compile(r"^```")


def split_blocks(text: str) -> list[tuple[str, list[str]]]:
    """Split a markdown file into (before, block) pairs; block is [] for prose.

    A fenced block that sits inside a <details> block is left alone: that is
    the text copy of a diagram that has already been converted, and running
    the tool twice must not convert it again.
    """
    parts = []
    lines = text.split("\n")
    i = 0
    prose: list[str] = []
    details = 0
    while i < len(lines):
        line = lines[i]
        if line.lstrip().startswith("<details"):
            details += 1
        elif line.lstrip().startswith("</details"):
            details = max(0, details - 1)
        if FENCE.match(line) and details == 0:
            parts.append(("\n".join(prose), []))
            prose = []
            i += 1
            block = []
            while i < len(lines) and not FENCE.match(lines[i]):
                block.append(lines[i])
                i += 1
            parts[-1] = (parts[-1][0], block)
            i += 1  # skip the closing fence
            continue
        prose.append(line)
        i += 1
    parts.append(("\n".join(prose), []))
    return parts


def has_box_art(block: list[str]) -> bool:
    return any(any(is_box(ch) for ch in line) for line in block)


def slug(text: str) -> str:
    s = re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")
    return s or "diagram"


PREFIX = {
    "TECHNICAL-REFERENCE.md": "tr",
    "INTERRUPTS.md": "int",
    "USER-GUIDE.md": "ug",
}


def push(out: list[str], line: str) -> None:
    """Append a line, unless it would double up a blank one."""
    if line == "" and out and out[-1] == "":
        return
    out.append(line)


def refresh(path: str) -> None:
    """Re-render the SVGs from the text copies that are already in the file.

    The text copy of a diagram is the source of truth: edit it, run the tool
    with --refresh, and the drawing follows. The markdown is not touched.
    """
    text = open(path, encoding="utf-8").read()
    lines = text.split("\n")
    base = os.path.dirname(path)
    count = 0
    i = 0
    while i < len(lines):
        m = re.match(r"^!\[([^\n]*)\]\(([^)]+\.svg)\)$", lines[i].strip())
        if not m:
            i += 1
            continue
        alt, rel = m.group(1), m.group(2)
        j = i
        while j < len(lines) and lines[j].strip() != "```":
            j += 1
        j += 1
        block = []
        while j < len(lines) and lines[j].strip() != "```":
            block.append(lines[j])
            j += 1
        if block and has_box_art(block):
            out = os.path.join(base, rel)
            os.makedirs(os.path.dirname(out), exist_ok=True)
            with open(out, "w", encoding="utf-8") as fh:
                fh.write(render_svg(Diagram(block), alt))
            count += 1
        i = j
    print(f"{path}: {count} diagram(s) refreshed")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("markdown", nargs="+")
    ap.add_argument("--outdir", default=None, help="where the SVGs go (default: <md dir>/diagrams)")
    ap.add_argument("--dry-run", action="store_true", help="list the diagrams, change nothing")
    ap.add_argument("--refresh", action="store_true", help="re-render the SVGs from the text copies")
    args = ap.parse_args()

    for path in args.markdown:
        if args.refresh:
            refresh(path)
        else:
            convert(path, args.outdir, args.dry_run)
    return 0


def convert(path: str, outdir: str | None, dry_run: bool) -> None:
    text = open(path, encoding="utf-8").read()
    outdir = outdir or os.path.join(os.path.dirname(path), "diagrams")
    prefix = PREFIX.get(os.path.basename(path), "dg")
    os.makedirs(outdir, exist_ok=True)

    rel = os.path.relpath(outdir, os.path.dirname(path))
    lines = text.split("\n")
    out: list[str] = []
    index = 0
    heading = "diagram"
    details = 0
    i = 0
    while i < len(lines):
        line = lines[i]
        stripped = line.lstrip()
        if stripped.startswith("<details"):
            details += 1
            out.append(line)
            i += 1
            continue
        if stripped.startswith("</details"):
            details = max(0, details - 1)
            out.append(line)
            i += 1
            continue
        if stripped.startswith("#"):
            heading = stripped.lstrip("#").strip()
        if not (FENCE.match(line) and details == 0):
            out.append(line)
            i += 1
            continue

        # a fenced block
        i += 1
        block = []
        while i < len(lines) and not FENCE.match(lines[i]):
            block.append(lines[i])
            i += 1
        i += 1  # skip the closing fence

        if not has_box_art(block):
            out.append(line)
            out.extend(block)
            out.append("```")
            continue

        index += 1
        name = f"{prefix}{index:02d}-{slug(heading)}.svg"
        svg = render_svg(Diagram(block), heading)
        if not dry_run:
            with open(os.path.join(outdir, name), "w", encoding="utf-8") as fh:
                fh.write(svg)
        push(out, "")
        out.append(f"![{heading}]({rel}/{name})")
        push(out, "")
        out.append("<details>")
        out.append("<summary>The same diagram as text (the SVG above is the aligned version)</summary>")
        push(out, "")
        out.append("```")
        out.extend(block)
        out.append("```")
        push(out, "")
        out.append("</details>")
        push(out, "")

    if not dry_run:
        open(path, "w", encoding="utf-8").write("\n".join(out))
    print(f"{path}: {index} diagram(s)")


if __name__ == "__main__":
    sys.exit(main())
