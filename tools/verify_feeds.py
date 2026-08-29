#!/usr/bin/env python3
"""Check every LiveATC mount in the bundled catalog against LiveATC itself.

The catalog used to be filled in by guessing mount names from a pattern
(tools/probe_liveatc.sh tries `{icao}_twr`, `{icao}_app`, `{icao}`, …). That
works for the airports whose mounts happen to fit the pattern and silently
produces dead entries for the ones that don't — Charlotte shipped for months
pointing at kclt_twr, kclt1 and kclt2, none of which LiveATC has ever served,
while its real mounts were kclt7_twr_118100, kclt7_dep_119000, kclt7_dep_120500
and kclt4_arr. Nothing in the app could tell the difference between "mount does
not exist" and "feed is off the air", so the whole airport just failed.

This asks LiveATC directly, per mount:

  1. GET /play/<mount>.pls — does the mount exist at all, and which edge server
     is currently carrying it?
  2. Open that stream and wait for audio — is anyone actually broadcasting?

Statuses:
    LIVE    mount exists and audio is flowing
    SILENT  mount exists but no audio arrived — feed is off the air
    DEAD    no such mount; the catalog entry is wrong and can never work
    ERROR   could not be checked (network, timeout, unexpected response)

A mount whose playlist points somewhere other than the URL in the catalog is
flagged MOVED. That is not a bug the app suffers from any more — LiveAtcDataSource
resolves the playlist at play time — but it is worth knowing when reading the
catalog, because the URL on disk is no longer where the audio comes from.

Usage:
    python3 tools/verify_feeds.py                  # check every LiveATC feed
    python3 tools/verify_feeds.py --icao kclt krdu # just these airports
    python3 tools/verify_feeds.py --json out.json  # machine-readable too
    python3 tools/verify_feeds.py --self-test      # no network; check the report logic

Exits non-zero if any mount is DEAD, so this can gate a release.

LiveATC is run by volunteers on donated bandwidth. Concurrency is deliberately
low and each stream probe stops as soon as it has heard enough.
"""

from __future__ import annotations

import argparse
import json
import socket
import sys
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from pathlib import Path

CATALOG = Path(__file__).resolve().parent.parent / "app/src/main/assets/feeds.json"

PLAYLIST_URL = "https://www.liveatc.net/play/{mount}.pls"
SEARCH_URL = "https://www.liveatc.net/search/?icao={icao}"

# LiveATC's web host turns away obvious bots; the streams themselves don't care.
BROWSER_UA = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
)

PLAYLIST_TIMEOUT = 12
STREAM_TIMEOUT = 10

# An Icecast mount starts sending MP3 frames immediately. Hearing this much is
# enough to say someone is broadcasting without holding the connection open.
AUDIO_BYTES_NEEDED = 4096

LIVE, SILENT, DEAD, ERROR = "LIVE", "SILENT", "DEAD", "ERROR"


@dataclass
class Result:
    icao: str
    name: str
    mount: str
    frequency: str | None
    catalog_host: str
    status: str = ERROR
    resolved_host: str = ""
    note: str = ""
    moved: bool = field(default=False)


# ── LiveATC ──────────────────────────────────────────────────────────────────


def _get(url: str, timeout: int):
    request = urllib.request.Request(
        url,
        headers={"User-Agent": BROWSER_UA, "Referer": "https://www.liveatc.net/"},
    )
    return urllib.request.urlopen(request, timeout=timeout)


def parse_playlist(body: str) -> list[str]:
    """Stream URLs from a .pls, in index order. Mirrors LiveAtc.parsePlaylist."""
    numbered: list[tuple[int, str]] = []
    for raw in body.splitlines():
        line = raw.strip()
        if not line.lower().startswith("file"):
            continue
        key, _, value = line.partition("=")
        index = key[4:].strip()
        if index.isdigit() and value.strip():
            numbered.append((int(index), value.strip()))
    return [url for _, url in sorted(numbered)]


def host_of(url: str) -> str:
    return url.split("://", 1)[-1].split("/", 1)[0].split(":")[0].lower()


def resolve(mount: str) -> tuple[list[str], str]:
    """Stream URLs LiveATC currently lists for the mount, plus a note on failure."""
    try:
        with _get(PLAYLIST_URL.format(mount=mount), PLAYLIST_TIMEOUT) as response:
            body = response.read(64 * 1024).decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        # 404 here is the finding, not an error: LiveATC has no such mount.
        return [], f"no such mount (HTTP {e.code})" if e.code == 404 else f"HTTP {e.code}"
    except (urllib.error.URLError, socket.timeout, OSError) as e:
        return [], f"playlist unreachable ({type(e).__name__})"
    urls = parse_playlist(body)
    return urls, "" if urls else "playlist had no stream entry"


def hear_audio(url: str) -> tuple[bool, str]:
    """True once enough stream bytes arrive to call the feed on the air."""
    try:
        with _get(url, STREAM_TIMEOUT) as response:
            heard = 0
            while heard < AUDIO_BYTES_NEEDED:
                chunk = response.read(1024)
                if not chunk:
                    return False, f"stream ended after {heard} bytes"
                heard += len(chunk)
            return True, ""
    except urllib.error.HTTPError as e:
        return False, f"stream HTTP {e.code}"
    except (urllib.error.URLError, socket.timeout, OSError) as e:
        return False, f"stream {type(e).__name__}"


def check(feed: dict) -> Result:
    url = feed.get("streamUrl") or ""
    mount = url.rstrip("/").rsplit("/", 1)[-1]
    result = Result(
        icao=(feed.get("code") or mount[:4]).upper(),
        name=feed.get("name", ""),
        mount=mount,
        frequency=feed.get("frequency"),
        catalog_host=host_of(url),
    )

    urls, note = resolve(mount)
    if not urls:
        result.status = DEAD if "no such mount" in note else ERROR
        result.note = note
        return result

    result.resolved_host = host_of(urls[0])
    result.moved = result.resolved_host != result.catalog_host

    # Try each edge the playlist offers before calling a feed silent — one of
    # them being unreachable doesn't mean nobody is broadcasting.
    for candidate in urls:
        ok, why = hear_audio(candidate)
        if ok:
            result.status = LIVE
            result.resolved_host = host_of(candidate)
            result.moved = result.resolved_host != result.catalog_host
            return result
        result.note = why
    result.status = SILENT
    return result


# ── Reporting ────────────────────────────────────────────────────────────────

BADGE = {LIVE: "LIVE  ", SILENT: "SILENT", DEAD: "DEAD  ", ERROR: "ERROR "}


def report(results: list[Result]) -> int:
    """Print the findings grouped by airport. Returns the process exit code."""
    width = max((len(r.name) for r in results), default=10)
    for icao in sorted({r.icao for r in results}):
        print(f"\n{icao}")
        for r in sorted((x for x in results if x.icao == icao), key=lambda x: x.mount):
            freq = (r.frequency or "").rjust(7)
            where = r.resolved_host or "—"
            flag = "  ← MOVED, catalog says " + r.catalog_host if r.moved else ""
            note = f"  ({r.note})" if r.note and r.status != LIVE else ""
            print(f"  {BADGE[r.status]}  {r.name:<{width}}  {freq}  {r.mount}")
            print(f"          {where}{flag}{note}")

    counts = {s: sum(1 for r in results if r.status == s) for s in (LIVE, SILENT, DEAD, ERROR)}
    print(
        f"\n{len(results)} mounts checked — "
        + ", ".join(f"{counts[s]} {s.strip().lower()}" for s in (LIVE, SILENT, DEAD, ERROR))
    )

    dead = [r for r in results if r.status == DEAD]
    if dead:
        # A dead mount can't be repaired by guessing a new name — that is how
        # these got into the catalog. Point at the page that lists the real ones.
        print("\nThese mounts do not exist and must be replaced from LiveATC's own listing:")
        for icao in sorted({r.icao for r in dead}):
            names = ", ".join(r.mount for r in dead if r.icao == icao)
            print(f"  {icao}: {names}")
            print(f"       {SEARCH_URL.format(icao=icao.lower())}")
        print(
            "\n  On each page, view source and search for  myHTML5Popup('  — the mount is the\n"
            "  first quoted argument at every call site."
        )

    moved = [r for r in results if r.moved and r.status == LIVE]
    if moved:
        plural = "mount has" if len(moved) == 1 else "mounts have"
        print(f"\n{len(moved)} live {plural} moved off the host in the catalog.")
        print("  The app resolves this at play time, so nothing is broken; the URLs are just stale.")

    return 1 if dead else 0


# ── Self-test ────────────────────────────────────────────────────────────────


def self_test() -> int:
    """Exercise the parsing and reporting without touching the network."""
    cases = [
        ("[playlist]\nNumberOfEntries=1\nFile1=https://s1-fmt2.liveatc.net/kclt7_twr_118100?nocache=1\n",
         ["https://s1-fmt2.liveatc.net/kclt7_twr_118100?nocache=1"]),
        ("[playlist]\nFile2=https://dd.liveatc.net/b\nFile1=https://d.liveatc.net/a\n",
         ["https://d.liveatc.net/a", "https://dd.liveatc.net/b"]),
        ("<html><title>404</title></html>", []),
        ("", []),
    ]
    for body, expected in cases:
        got = parse_playlist(body)
        assert got == expected, f"parse_playlist({body!r}) -> {got}, wanted {expected}"
    assert host_of("https://s1-fmt2.liveatc.net/x?y=1") == "s1-fmt2.liveatc.net"
    print("parse_playlist and host_of OK\n")

    print("Sample report (synthetic data, no network):")
    demo = [
        Result("KCLT", "Charlotte Tower", "kclt7_twr_118100", "118.100", "d.liveatc.net",
               LIVE, "s1-fmt2.liveatc.net", "", True),
        Result("KCLT", "Charlotte Arrival", "kclt4_arr", "132.700", "d.liveatc.net",
               LIVE, "d.liveatc.net"),
        Result("KRDU", "Raleigh-Durham Tower", "krdu_twr", "119.750", "d.liveatc.net",
               DEAD, "", "no such mount (HTTP 404)"),
        Result("KBOS", "Boston Tower", "kbos_twr", "128.800", "d.liveatc.net",
               SILENT, "d.liveatc.net", "stream ended after 0 bytes"),
    ]
    code = report(demo)
    print(f"\n(self-test exit code would be {code}; 1 is correct here because KRDU is dead)")
    return 0


# ── Entry point ──────────────────────────────────────────────────────────────


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--catalog", type=Path, default=CATALOG)
    parser.add_argument("--icao", nargs="*", help="only these airports, e.g. --icao kclt krdu")
    parser.add_argument("--json", type=Path, help="also write results here")
    parser.add_argument("--workers", type=int, default=6, help="parallel checks (default 6)")
    parser.add_argument("--self-test", action="store_true", help="check the logic, no network")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    feeds = json.loads(args.catalog.read_text())["feeds"]
    wanted = {i.upper() for i in args.icao} if args.icao else None
    targets = [
        f for f in feeds
        if f.get("source") == "LIVEATC" and f.get("streamUrl")
        and (wanted is None or (f.get("code") or "").upper() in wanted)
    ]
    if not targets:
        print("No matching LiveATC feeds in the catalog.", file=sys.stderr)
        return 2

    print(f"Checking {len(targets)} LiveATC mounts…", file=sys.stderr)
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        results = list(pool.map(check, targets))

    code = report(results)
    if args.json:
        args.json.write_text(json.dumps([vars(r) for r in results], indent=2))
        print(f"\nWrote {args.json}")
    return code


if __name__ == "__main__":
    sys.exit(main())
