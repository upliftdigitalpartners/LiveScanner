#!/usr/bin/env python3
"""Turn the generated silhouette JPEGs in AiImages/ into tintable PNGs.

The image model was asked for a transparent background and drew a checkerboard
instead — the picture of transparency rather than the thing itself. JPEG cannot
carry alpha at all, so every one of them arrived as an opaque image of a white
aeroplane on a grey grid.

That is recoverable, because the two are far apart in luminance: threshold the
image and the aircraft is what remains. This does that, then fixes the things
that matter for a radar target:

  * crops to the aircraft and re-centres it, since the generator framed each one
    differently and the scope rotates these about their own centre
  * mirrors one half onto the other, because a generated aircraft is never quite
    symmetrical and a lopsided target looks like bad data rather than bad art
  * writes white RGB with the shape in the alpha channel, so android:tint can
    colour it from the altitude ramp

--preview rasterises the result to the terminal, which is the only way to check
a silhouette in a pipeline that cannot open an image.

Usage:
    python3 tools/import_ai_silhouettes.py --preview AiImages/Some_file.jpg
    python3 tools/import_ai_silhouettes.py --out app/src/main/res/drawable-nodpi/ic_ac_x.png AiImages/Some_file.jpg
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from PIL import Image

# The aircraft is near-white and the checkerboard is dark grey on black, so the
# gap is wide. Mid-grey splits them with room on either side for JPEG ringing.
THRESHOLD = 140

# Fraction of the output taken up by the aircraft, leaving room for the longest
# dimension to sweep through when the icon is rotated by the aircraft's track.
FILL = 0.88

OUTPUT_PX = 512


def mask_of(path: Path) -> tuple[Image.Image, tuple[int, int, int, int]]:
    """Binary alpha mask of the aircraft, plus its bounding box."""
    grey = Image.open(path).convert("L")
    mask = grey.point(lambda v: 255 if v >= THRESHOLD else 0, mode="L")
    box = mask.getbbox()
    if box is None:
        raise SystemExit(f"{path}: nothing above the threshold — is it a silhouette?")
    return mask, box


def symmetrise(mask: Image.Image) -> Image.Image:
    """Mirror the half with more ink onto the other, about the shape's own centre."""
    width, _ = mask.size
    mid = width // 2
    left, right = mask.crop((0, 0, mid, mask.height)), mask.crop((width - mid, 0, width, mask.height))
    # Whichever side the generator drew more completely is the one to keep.
    def ink(img: Image.Image) -> int:
        return sum(level * count for level, count in enumerate(img.histogram()))

    keep_right = ink(right) >= ink(left)
    half = right if keep_right else left
    out = Image.new("L", mask.size, 0)
    if keep_right:
        out.paste(half.transpose(Image.FLIP_LEFT_RIGHT), (0, 0))
        out.paste(half, (width - mid, 0))
    else:
        out.paste(half, (0, 0))
        out.paste(half.transpose(Image.FLIP_LEFT_RIGHT), (width - mid, 0))
    return out


def build(path: Path, size: int = OUTPUT_PX) -> Image.Image:
    mask, box = mask_of(path)
    cropped = mask.crop(box)

    # Square the crop around the aircraft before scaling, so proportions survive.
    side = max(cropped.size)
    squared = Image.new("L", (side, side), 0)
    squared.paste(cropped, ((side - cropped.width) // 2, (side - cropped.height) // 2))

    inner = int(size * FILL)
    scaled = symmetrise(squared.resize((inner, inner), Image.LANCZOS))

    alpha = Image.new("L", (size, size), 0)
    alpha.paste(scaled, ((size - inner) // 2, (size - inner) // 2))

    # White everywhere, with the shape only in alpha, so the tint has clean colour
    # to work with and no dark fringe survives at the edges.
    white = Image.new("L", (size, size), 255)
    return Image.merge("RGBA", (white, white, white, alpha))


def preview(image: Image.Image, cols: int = 54, rows: int = 27) -> str:
    alpha = image.getchannel("A").resize((cols, rows), Image.LANCZOS)
    ramp = " .:-=+*#%@"
    data = list(alpha.tobytes())
    lines = []
    for r in range(rows):
        row = data[r * cols:(r + 1) * cols]
        lines.append("".join(ramp[min(v * len(ramp) // 256, len(ramp) - 1)] for v in row))
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("sources", nargs="+", type=Path)
    parser.add_argument("--out", type=Path, help="write a PNG here (one source only)")
    parser.add_argument("--preview", action="store_true")
    parser.add_argument("--size", type=int, default=OUTPUT_PX)
    args = parser.parse_args()

    if args.out and len(args.sources) != 1:
        raise SystemExit("--out takes exactly one source")

    for src in args.sources:
        image = build(src, args.size)
        if args.preview:
            print(f"\n=== {src.name}")
            print(preview(image))
        if args.out:
            args.out.parent.mkdir(parents=True, exist_ok=True)
            image.save(args.out, "PNG", optimize=True)
            print(f"wrote {args.out} ({args.out.stat().st_size // 1024} KB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
