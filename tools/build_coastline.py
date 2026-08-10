#!/usr/bin/env python3
"""Build app/src/main/assets/coastline.json — the radar scope's geographic underlay.

For every airport in assets/feeds.json that carries lat/lon, this clips the
Natural Earth 1:10m coastline to a box of ~90 NM around the field, thins the
resulting polylines, and writes them keyed by ICAO code.

Input data (public domain, ~1:10m physical / coastline):
    https://naciscdn.org/naturalearth/10m/physical/ne_10m_coastline.zip
Unzip it anywhere and point --shp at ne_10m_coastline.shp.

Usage:
    python3 tools/build_coastline.py --shp /path/to/ne_10m_coastline.shp
    python3 tools/build_coastline.py --shp ... --min-step-nm 0.5   # smaller asset

The .shp reader below is intentionally dependency-free: the polyline subset of
the format is a handful of structs, so the build needs nothing but stdlib.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import struct
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FEEDS = os.path.join(REPO, "app", "src", "main", "assets", "feeds.json")
OUT = os.path.join(REPO, "app", "src", "main", "assets", "coastline.json")

NM_PER_DEG_LAT = 60.0
SHAPE_NULL = 0
SHAPE_POLYLINE = 3


# --------------------------------------------------------------------------- #
# Shapefile reading
# --------------------------------------------------------------------------- #

def read_polylines(path: str) -> list[list[tuple[float, float]]]:
    """Return every part of every polyline record as a list of (lon, lat)."""
    with open(path, "rb") as fh:
        blob = fh.read()

    magic, = struct.unpack_from(">i", blob, 0)
    if magic != 9994:
        raise ValueError(f"{path}: not a shapefile (magic {magic})")
    file_len = struct.unpack_from(">i", blob, 24)[0] * 2  # header stores 16-bit words
    if file_len != len(blob):
        print(f"warning: header length {file_len} != file size {len(blob)}", file=sys.stderr)

    parts: list[list[tuple[float, float]]] = []
    pos = 100  # main header is a fixed 100 bytes
    while pos + 8 <= len(blob):
        _num, content_len = struct.unpack_from(">ii", blob, pos)
        pos += 8
        end = pos + content_len * 2
        shape_type, = struct.unpack_from("<i", blob, pos)
        if shape_type == SHAPE_POLYLINE:
            # <i type, 4d bbox, i numParts, i numPoints, numParts*i indices, numPoints*2d xy
            num_parts, num_points = struct.unpack_from("<ii", blob, pos + 36)
            idx_at = pos + 44
            indices = struct.unpack_from(f"<{num_parts}i", blob, idx_at)
            xy_at = idx_at + num_parts * 4
            coords = struct.unpack_from(f"<{2 * num_points}d", blob, xy_at)
            for p, start in enumerate(indices):
                stop = indices[p + 1] if p + 1 < num_parts else num_points
                if stop - start >= 2:
                    parts.append([(coords[2 * i], coords[2 * i + 1]) for i in range(start, stop)])
        elif shape_type != SHAPE_NULL:
            raise ValueError(f"{path}: unexpected shape type {shape_type}")
        pos = end
    return parts


# --------------------------------------------------------------------------- #
# Geometry
# --------------------------------------------------------------------------- #

def thin(points: list[tuple[float, float]], min_step_nm: float) -> list[tuple[float, float]]:
    """Drop points closer than [min_step_nm] to the previous kept point.

    First and last points always survive so clipped ends stay put.
    """
    if len(points) < 3:
        return points
    kept = [points[0]]
    for pt in points[1:-1]:
        if dist_nm(kept[-1], pt) >= min_step_nm:
            kept.append(pt)
    kept.append(points[-1])
    return kept


def dist_nm(a: tuple[float, float], b: tuple[float, float]) -> float:
    """Flat-earth distance in nautical miles — plenty at coastline scale."""
    mid_lat = math.radians((a[1] + b[1]) / 2.0)
    dx = (b[0] - a[0]) * math.cos(mid_lat) * NM_PER_DEG_LAT
    dy = (b[1] - a[1]) * NM_PER_DEG_LAT
    return math.hypot(dx, dy)


def clip_segment(
    a: tuple[float, float],
    b: tuple[float, float],
    box: tuple[float, float, float, float],
) -> tuple[tuple[float, float], tuple[float, float]] | None:
    """Liang-Barsky clip of segment a->b to (xmin, ymin, xmax, ymax)."""
    xmin, ymin, xmax, ymax = box
    x0, y0 = a
    x1, y1 = b
    dx = x1 - x0
    dy = y1 - y0
    t0, t1 = 0.0, 1.0
    for p, q in ((-dx, x0 - xmin), (dx, xmax - x0), (-dy, y0 - ymin), (dy, ymax - y0)):
        if p == 0.0:
            if q < 0.0:
                return None  # parallel to this edge and outside it
            continue
        t = q / p
        if p < 0.0:
            if t > t1:
                return None
            t0 = max(t0, t)
        else:
            if t < t0:
                return None
            t1 = min(t1, t)
    if t0 > t1:
        return None
    return ((x0 + t0 * dx, y0 + t0 * dy), (x0 + t1 * dx, y0 + t1 * dy))


def clip_polyline(
    points: list[tuple[float, float]],
    box: tuple[float, float, float, float],
) -> list[list[tuple[float, float]]]:
    """Clip a polyline to [box], splitting wherever it leaves and re-enters."""
    out: list[list[tuple[float, float]]] = []
    current: list[tuple[float, float]] = []
    for a, b in zip(points, points[1:]):
        piece = clip_segment(a, b, box)
        if piece is None:
            if len(current) >= 2:
                out.append(current)
            current = []
            continue
        pa, pb = piece
        if current and same(current[-1], pa):
            current.append(pb)
        else:
            if len(current) >= 2:
                out.append(current)
            current = [pa, pb]
    if len(current) >= 2:
        out.append(current)
    return out


def same(a: tuple[float, float], b: tuple[float, float]) -> bool:
    return abs(a[0] - b[0]) < 1e-12 and abs(a[1] - b[1]) < 1e-12


def bbox(points: list[tuple[float, float]]) -> tuple[float, float, float, float]:
    xs = [p[0] for p in points]
    ys = [p[1] for p in points]
    return (min(xs), min(ys), max(xs), max(ys))


def overlaps(a: tuple[float, float, float, float], b: tuple[float, float, float, float]) -> bool:
    return not (a[2] < b[0] or b[2] < a[0] or a[3] < b[1] or b[3] < a[1])


# --------------------------------------------------------------------------- #
# Airports
# --------------------------------------------------------------------------- #

def airport_code(feed_id: str) -> str:
    """Mirror Feed.displayCode: "liveatc:kbos_twr" -> "KBOS"."""
    return feed_id.split(":", 1)[-1].split("_", 1)[0][:4].upper()


def load_airports(path: str) -> dict[str, tuple[float, float]]:
    with open(path, encoding="utf-8") as fh:
        feeds = json.load(fh).get("feeds", [])
    airports: dict[str, tuple[float, float]] = {}
    for feed in feeds:
        lat, lon = feed.get("lat"), feed.get("lon")
        if lat is None or lon is None:
            continue
        code = feed.get("code")
        code = code.upper() if code else airport_code(feed["id"])
        airports.setdefault(code, (float(lat), float(lon)))  # first feed wins for shared codes
    return airports


def box_around(lat: float, lon: float, radius_nm: float) -> tuple[float, float, float, float]:
    """Lon/lat box of roughly [radius_nm] around a point."""
    dlat = radius_nm / NM_PER_DEG_LAT
    dlon = dlat / max(math.cos(math.radians(lat)), 0.01)
    return (lon - dlon, lat - dlat, lon + dlon, lat + dlat)


# --------------------------------------------------------------------------- #

def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--shp", required=True, help="path to ne_10m_coastline.shp")
    ap.add_argument("--feeds", default=FEEDS)
    ap.add_argument("--out", default=OUT)
    ap.add_argument("--radius-nm", type=float, default=90.0)
    ap.add_argument("--min-step-nm", type=float, default=0.3,
                    help="drop consecutive points closer together than this")
    args = ap.parse_args()

    airports = load_airports(args.feeds)
    print(f"{len(airports)} airports with coordinates")

    raw = read_polylines(args.shp)
    print(f"{len(raw)} coastline parts, {sum(len(p) for p in raw)} points")

    # Thin once globally, then reuse for every airport.
    shapes = []
    for part in raw:
        pts = thin(part, args.min_step_nm)
        if len(pts) >= 2:
            shapes.append((bbox(pts), pts))
    print(f"{sum(len(s[1]) for s in shapes)} points after thinning at {args.min_step_nm} NM")

    result: dict[str, list[list[list[float]]]] = {}
    for code, (lat, lon) in sorted(airports.items()):
        box = box_around(lat, lon, args.radius_nm)
        lines: list[list[list[float]]] = []
        for shape_box, pts in shapes:
            if not overlaps(shape_box, box):
                continue
            for line in clip_polyline(pts, box):
                # Emit as [lat, lon] pairs; re-thin so clip-introduced points
                # don't sneak in below the threshold.
                line = thin(line, args.min_step_nm)
                if len(line) >= 2:
                    lines.append([[round(y, 4), round(x, 4)] for x, y in line])
        if lines:
            result[code] = lines

    payload = {"version": 1, "airports": result}
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, separators=(",", ":"))
        fh.write("\n")

    size = os.path.getsize(args.out)
    points = sum(len(line) for lines in result.values() for line in lines)
    print(f"wrote {args.out}: {size} bytes ({size / 1024:.1f} KB)")
    print(f"{len(result)} airports with coastline, {points} points")
    for code in ("KBOS", "KSFO", "KMIA", "KSEA", "KSAN", "KDEN", "KDFW"):
        lines = result.get(code)
        if lines:
            print(f"  {code}: {len(lines)} polylines, "
                  f"{sum(len(l) for l in lines)} points, first={lines[0][0]}")
        else:
            print(f"  {code}: none (inland)")
    if size > 400 * 1024:
        print("WARNING: over the 400 KB budget — rerun with a larger --min-step-nm",
              file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
