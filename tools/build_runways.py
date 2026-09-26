#!/usr/bin/env python3
"""Build app/src/main/assets/runways.json — runway ends for every airport in the catalog.

Knowing which runway an aircraft used means knowing where the runways are. This
pulls that from OurAirports (public domain), keeps only the fields the app needs
and only the airports the feed catalog actually covers, and writes one entry per
runway *end* — because a landing or a departure uses an end, not a strip.

    ident        "18C", "36C" — what a controller says
    lat/lon      the threshold, which is what an aircraft is near at touchdown
    headingDeg   true, matching the track ADS-B reports (not magnetic)
    lengthFt     used to prefer a real runway over a short GA strip

Closed runways are dropped: KCLT's 05/23 is still in the data and would otherwise
be offered as a landing runway years after it stopped being one.

Source (public domain):
    https://raw.githubusercontent.com/davidmegginson/ourairports-data/main/runways.csv

Usage:
    python3 tools/build_runways.py                      # downloads the CSV
    python3 tools/build_runways.py --csv runways.csv    # use a local copy
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FEEDS = ROOT / "app/src/main/assets/feeds.json"
OUT = ROOT / "app/src/main/assets/runways.json"
SOURCE = "https://raw.githubusercontent.com/davidmegginson/ourairports-data/main/runways.csv"


def catalog_icaos() -> set[str]:
    feeds = json.loads(FEEDS.read_text())["feeds"]
    codes = set()
    for f in feeds:
        code = f.get("code")
        if not code:
            # Same derivation the app uses: "liveatc:kclt7_twr_118100" -> KCLT.
            code = f.get("id", "").split(":", 1)[-1].split("_", 1)[0][:4]
        if len(code) == 4:
            codes.add(code.upper())
    return codes


def ends(row: dict) -> list[dict]:
    """Both ends of one runway, skipping any without a usable threshold."""
    out = []
    for side in ("le", "he"):
        ident = (row.get(f"{side}_ident") or "").strip()
        lat = row.get(f"{side}_latitude_deg") or ""
        lon = row.get(f"{side}_longitude_deg") or ""
        heading = row.get(f"{side}_heading_degT") or ""
        if not (ident and lat and lon and heading):
            continue
        try:
            out.append({
                "ident": ident.upper(),
                "lat": round(float(lat), 6),
                "lon": round(float(lon), 6),
                "headingDeg": round(float(heading), 1),
                "lengthFt": int(float(row.get("length_ft") or 0)),
            })
        except ValueError:
            continue
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--csv", type=Path, help="local runways.csv instead of downloading")
    parser.add_argument("--out", type=Path, default=OUT)
    args = parser.parse_args()

    if args.csv:
        text = args.csv.read_text(encoding="utf-8", errors="replace")
    else:
        print(f"Downloading {SOURCE}…", file=sys.stderr)
        with urllib.request.urlopen(SOURCE, timeout=120) as r:
            text = r.read().decode("utf-8", "replace")

    wanted = catalog_icaos()
    airports: dict[str, list[dict]] = {}
    closed = 0
    for row in csv.DictReader(text.splitlines()):
        icao = (row.get("airport_ident") or "").upper()
        if icao not in wanted:
            continue
        if (row.get("closed") or "0").strip() == "1":
            closed += 1
            continue
        airports.setdefault(icao, []).extend(ends(row))

    # Longest first: when two ends match an aircraft's track equally well, the
    # main runway is the better guess than a crossing strip.
    for icao in airports:
        airports[icao].sort(key=lambda e: (-e["lengthFt"], e["ident"]))

    args.out.write_text(json.dumps({"airports": dict(sorted(airports.items()))}, indent=1) + "\n")

    missing = sorted(wanted - airports.keys())
    total = sum(len(v) for v in airports.values())
    print(f"{len(airports)} airports, {total} runway ends, {closed} closed rows skipped")
    print(f"wrote {args.out} ({args.out.stat().st_size // 1024} KB)")
    if missing:
        print(f"no runway data for: {', '.join(missing)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
