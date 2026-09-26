#!/usr/bin/env python3
"""Build app/src/main/assets/gates.json — terminal gate positions, from OpenStreetMap.

Nothing in ADS-B carries a gate, so the app reads one off the radio ("taxi to gate
charlie one five"). That works, and it is usually the earliest anyone knows — but
only if you were listening when it was said. This asset covers the other case: an
aircraft that has already parked can be matched to the stand it is sitting on.

OSM tags gates as aeroway=gate, with the gate designation in `ref` (sometimes
`local_ref`, occasionally only `name`). Data is ODbL — see
https://www.openstreetmap.org/copyright — which the app should credit if this
ships.

Overpass is a free, volunteer-run service. This asks for one airport at a time
with a pause between, retries politely on the codes that mean "slow down", and
will take a few minutes for the full catalog. Please don't remove the delays.

Usage:
    python3 tools/build_gates.py                      # every airport in the catalog
    python3 tools/build_gates.py --icao KCLT KRDU     # just these
    python3 tools/build_gates.py --radius 6000        # sprawling fields (DFW, ATL)

The app runs fine without this file: with no gates.json the gate is simply
radio-only, which is how it behaves today.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FEEDS = ROOT / "app/src/main/assets/feeds.json"
OUT = ROOT / "app/src/main/assets/gates.json"

ENDPOINTS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://lz4.overpass-api.de/api/interpreter",
]

# Overpass asks for a real User-Agent so its operators can tell traffic apart.
UA = "LiveScanner-gate-builder/1.0 (+https://github.com/upliftdigitalpartners/LiveScanner)"

QUERY = """[out:json][timeout:90];
(
  node(around:{radius},{lat},{lon})["aeroway"="gate"];
  way(around:{radius},{lat},{lon})["aeroway"="gate"];
);
out center;
"""

PAUSE_SEC = 4.0
RETRY_CODES = {429, 504, 502, 503}


def airports() -> dict[str, tuple[float, float]]:
    """ICAO -> (lat, lon) for every catalog feed that carries coordinates."""
    feeds = json.loads(FEEDS.read_text())["feeds"]
    out: dict[str, tuple[float, float]] = {}
    for f in feeds:
        lat, lon = f.get("lat"), f.get("lon")
        if lat is None or lon is None:
            continue
        code = f.get("code") or f.get("id", "").split(":", 1)[-1].split("_", 1)[0][:4]
        if len(code) == 4:
            out.setdefault(code.upper(), (lat, lon))
    return out


def overpass(query: str) -> dict | None:
    """Run a query, trying each mirror and backing off when asked to."""
    for endpoint in ENDPOINTS:
        for attempt in range(3):
            request = urllib.request.Request(
                endpoint,
                data=query.encode("utf-8"),
                headers={"User-Agent": UA, "Content-Type": "text/plain; charset=utf-8"},
            )
            try:
                with urllib.request.urlopen(request, timeout=120) as response:
                    return json.loads(response.read().decode("utf-8", "replace"))
            except urllib.error.HTTPError as e:
                if e.code not in RETRY_CODES:
                    print(f"    HTTP {e.code} from {endpoint}", file=sys.stderr)
                    break
                time.sleep((attempt + 1) * 15)
            except Exception as e:  # noqa: BLE001 — any transport failure moves to the next mirror
                print(f"    {type(e).__name__} from {endpoint}", file=sys.stderr)
                break
    return None


def gates_from(payload: dict) -> list[dict]:
    """Named gates out of an Overpass response, deduplicated by designation."""
    seen: dict[str, dict] = {}
    for element in payload.get("elements", []):
        tags = element.get("tags", {})
        # `ref` is the gate number proper; the other two are what mappers reach for
        # when they don't know that. An unnamed stand is no use to anyone.
        ref = (tags.get("ref") or tags.get("local_ref") or tags.get("name") or "").strip()
        if not ref:
            continue
        # Ways carry their position under `center` because of `out center`.
        lat = element.get("lat") or (element.get("center") or {}).get("lat")
        lon = element.get("lon") or (element.get("center") or {}).get("lon")
        if lat is None or lon is None:
            continue
        key = ref.upper().replace(" ", "")
        seen.setdefault(key, {
            "ref": key,
            "lat": round(float(lat), 6),
            "lon": round(float(lon), 6),
            "terminal": (tags.get("terminal") or "").strip() or None,
        })
    return [dict(filter(lambda kv: kv[1] is not None, g.items())) for g in
            sorted(seen.values(), key=lambda g: g["ref"])]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--icao", nargs="*", help="only these airports")
    parser.add_argument("--radius", type=int, default=5000, help="metres around the field (default 5000)")
    parser.add_argument("--out", type=Path, default=OUT)
    args = parser.parse_args()

    fields = airports()
    if args.icao:
        wanted = {i.upper() for i in args.icao}
        fields = {k: v for k, v in fields.items() if k in wanted}
    if not fields:
        print("No matching airports in the catalog.", file=sys.stderr)
        return 2

    # Keep anything already built so a partial or single-airport run adds rather
    # than replaces — a failed mirror shouldn't wipe the gates you already have.
    existing: dict[str, list] = {}
    if args.out.exists():
        try:
            existing = json.loads(args.out.read_text()).get("airports", {})
        except json.JSONDecodeError:
            pass

    print(f"Querying OpenStreetMap for {len(fields)} airports…", file=sys.stderr)
    found, empty, failed = 0, [], []
    for i, (icao, (lat, lon)) in enumerate(sorted(fields.items())):
        payload = overpass(QUERY.format(radius=args.radius, lat=lat, lon=lon))
        if payload is None:
            failed.append(icao)
            print(f"  {icao}: unreachable", file=sys.stderr)
        else:
            gates = gates_from(payload)
            if gates:
                existing[icao] = gates
                found += len(gates)
                print(f"  {icao}: {len(gates)} gates", file=sys.stderr)
            else:
                empty.append(icao)
                print(f"  {icao}: none mapped", file=sys.stderr)
        if i < len(fields) - 1:
            time.sleep(PAUSE_SEC)

    print(f"\n{len(existing)} airports, {found} gates this run")
    if not existing:
        # Writing an empty asset would look like "this airport has no gates" rather than
        # "the run failed", and the app treats a missing file correctly already.
        print("nothing to write — leaving the asset alone", file=sys.stderr)
    else:
        args.out.write_text(json.dumps({"airports": dict(sorted(existing.items()))}, indent=1) + "\n")
        print(f"wrote {args.out} ({args.out.stat().st_size // 1024} KB)")
    if empty:
        print(f"no gates mapped in OSM: {', '.join(empty)}")
    if failed:
        print(f"could not reach Overpass for: {', '.join(failed)} — rerun with --icao for just these")
    return 0


if __name__ == "__main__":
    sys.exit(main())
