#!/usr/bin/env python3
"""Probe every non-channel source the app relies on, from CI, and write an honest report.

- open media films (Blender films, DASH and HLS sample streams)
- ESPN public scoreboard endpoints for each league of the sports hub
- Internet Archive search API for the three VOD shelves
- free XMLTV guide candidates, and how many built-in channels each one covers
"""
from __future__ import annotations

import gzip
import io
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

UA = "TCLStreamX/1.0 (source-probe)"
TIMEOUT = 25

EPG_CANDIDATES = [
    "https://xmltvfr.fr/xmltv/xmltv.zip",
    "https://xmltvfr.fr/xmltv/xmltv_tnt.zip",
    "https://xmltvfr.fr/xmltv/xmltv.xml.gz",
    "https://epgshare01.online/epgshare01/epg_ripper_FR1.xml.gz",
    "https://epgshare01.online/epgshare01/epg_ripper_SA1.xml.gz",
    "https://epgshare01.online/epgshare01/epg_ripper_QA1.xml.gz",
    "https://epgshare01.online/epgshare01/epg_ripper_AE1.xml.gz",
    "https://epgshare01.online/epgshare01/epg_ripper_UK1.xml.gz",
    "https://epgshare01.online/epgshare01/epg_ripper_US1.xml.gz",
]


def get(url: str, limit: int = 300 * 1024 * 1024, headers: dict | None = None):
    req = urllib.request.Request(url, headers={"User-Agent": UA, **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            return r.status, r.read(limit)
    except urllib.error.HTTPError as e:
        return e.code, b""
    except Exception as e:  # network failures are results too
        return 0, str(e).encode()


def main() -> int:
    lines = [f"# Source probe\n\nRun: {time.strftime('%Y-%m-%d %H:%M UTC', time.gmtime())}\n"]

    media = json.load(open("app/src/main/assets/open_media.json", encoding="utf-8"))
    lines.append("\n## Open media\n\n| Title | HTTP | Bytes read | Note |\n|---|---|---|---|")
    for f in media["films"]:
        code, body = get(f["url"], limit=256 * 1024, headers={"Range": "bytes=0-262143"})
        note = ""
        if f["url"].endswith(".m3u8") and body:
            note = f"{body.count(b'#EXT-X-STREAM-INF')} variants, subtitles: {b'TYPE=SUBTITLES' in body}"
        if f["url"].endswith(".mpd") and body:
            note = f"{len(re.findall(rb'<Representation', body))} representations, DRM: {b'ContentProtection' in body}"
        lines.append(f"| {f['title']} | {code} | {len(body)} | {note} |")

    guide = json.load(open("app/src/main/assets/sports_destinations.json", encoding="utf-8"))
    today = time.strftime("%Y%m%d")
    lines.append("\n## Sports schedule (ESPN public scoreboard)\n\n| League | HTTP | Events (8 days) |\n|---|---|---|")
    end = time.strftime("%Y%m%d", time.gmtime(time.time() + 7 * 86400))
    for lg in guide["leagues"]:
        url = f"https://site.api.espn.com/apis/site/v2/sports/{lg['sport']}/{lg['id']}/scoreboard?dates={today}-{end}&limit=200"
        code, body = get(url, limit=20 * 1024 * 1024)
        events = "?"
        if code == 200:
            try:
                events = str(len(json.loads(body).get("events", [])))
            except ValueError:
                events = "invalid JSON"
        lines.append(f"| {lg['id']} | {code} | {events} |")

    lines.append("\n## Internet Archive shelves\n\n| Shelf | HTTP | numFound |\n|---|---|---|")
    for name, q in [("movies", "collection:(feature_films) AND mediatype:(movies)"),
                    ("series", "collection:(classic_tv) AND mediatype:(movies)"),
                    ("animation", "collection:(classic_cartoons) AND mediatype:(movies)")]:
        url = "https://archive.org/advancedsearch.php?" + urllib.parse.urlencode(
            {"q": q, "fl[]": "identifier", "rows": "1", "output": "json"})
        code, body = get(url)
        found = "?"
        if code == 200:
            try:
                found = str(json.loads(body)["response"]["numFound"])
            except (ValueError, KeyError):
                found = "invalid"
        lines.append(f"| {name} | {code} | {found} |")

    catalog = json.load(open("app/src/main/assets/catalog.json", encoding="utf-8"))
    wanted = {c["id"].lower() for c in catalog["channels"]} | {c["id"].split("@")[0].lower() for c in catalog["channels"]}
    lines.append("\n## XMLTV guide candidates\n\n| URL | HTTP | Size MB | Channels in guide | Built-in channels covered |\n|---|---|---|---|---|")
    for url in EPG_CANDIDATES:
        code, body = get(url)
        size = len(body) / 1048576
        ids: set[str] = set()
        if code == 200 and body:
            try:
                if body[:2] == b"\x1f\x8b":
                    body = gzip.decompress(body)
                elif body[:2] == b"PK":
                    with zipfile.ZipFile(io.BytesIO(body)) as z:
                        body = z.read(z.namelist()[0])
                ids = {m.decode("utf-8", "replace").lower() for m in re.findall(rb'<channel id="([^"]+)"', body)}
            except (OSError, zipfile.BadZipFile, EOFError) as e:
                ids = set()
                code = f"{code} (unreadable: {e})"
        covered = sorted(ids & wanted)
        lines.append(f"| {url} | {code} | {size:.1f} | {len(ids)} | {len(covered)} {', '.join(covered[:8])} |")

    open("catalog/SOURCES_REPORT.md", "w", encoding="utf-8").write("\n".join(lines) + "\n")
    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    sys.exit(main())
