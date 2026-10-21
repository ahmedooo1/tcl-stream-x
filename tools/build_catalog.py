#!/usr/bin/env python3
"""Build the built-in channel catalog of TCL Stream X.

Pipeline:
  1. read catalog/official_sources.json (allowlist: tvg-id + broadcaster-owned URL regex)
  2. download iptv-org stream lists for the listed countries (index of URLs only)
  3. keep only entries matching a rule, enrich with iptv-org database (name, logo, website)
  4. optionally probe every candidate URL (manifest + one media segment) and record the result
  5. write app/src/main/assets/catalog.json and catalog/REPORT.md

Nothing here bypasses geo-blocking, tokens or DRM: a stream that refuses access is recorded as such.
Standard library only, so it runs unchanged on GitHub Actions.
"""
from __future__ import annotations

import argparse
import csv
import io
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field, asdict

RAW = "https://raw.githubusercontent.com/iptv-org"
USER_AGENT = "TCLStreamX/1.0 (Android TV; stream-availability-check)"
TIMEOUT_S = 12
MAX_MANIFEST_BYTES = 2 * 1024 * 1024
SEGMENT_PROBE_BYTES = 65535
MAX_CANDIDATES_PER_CHANNEL = 3


@dataclass
class Candidate:
    url: str
    name: str
    quality: int
    user_agent: str | None = None
    referrer: str | None = None
    labels: list[str] = field(default_factory=list)
    status: str = "unchecked"
    detail: str = ""
    checked_at: int = 0


def http_get(url: str, ua: str | None, referrer: str | None, byte_range: int | None = None,
             limit: int = MAX_MANIFEST_BYTES) -> tuple[int, str, bytes]:
    headers = {"User-Agent": ua or USER_AGENT, "Accept": "*/*"}
    if referrer:
        headers["Referer"] = referrer
    if byte_range is not None:
        headers["Range"] = f"bytes=0-{byte_range}"
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT_S) as resp:
            return resp.status, resp.geturl(), resp.read(limit)
    except urllib.error.HTTPError as e:
        return e.code, url, b""


def classify_http(code: int) -> str:
    if code in (401, 407):
        return "auth_required"
    if code in (403, 451):
        return "forbidden_or_geo"
    if code in (404, 410):
        return "gone"
    if code >= 500:
        return "server_error"
    return f"http_{code}"


def uris(text: str) -> list[str]:
    return [l.strip() for l in text.splitlines() if l.strip() and not l.startswith("#")]


def probe(c: Candidate) -> Candidate:
    """Fetch the manifest, one variant and one segment. Records what really happened."""
    c.checked_at = int(time.time())
    try:
        code, final_url, body = http_get(c.url, c.user_agent, c.referrer)
        if code not in (200, 206):
            c.status, c.detail = classify_http(code), f"manifest HTTP {code}"
            return c
        text = body.decode("utf-8", "replace")
        if "<MPD" in text[:2000]:
            c.status, c.detail = "ok", "DASH manifest reachable (segments not probed)"
            return c
        if "#EXTM3U" not in text[:1024]:
            c.status, c.detail = "invalid_manifest", "response is not an HLS/DASH manifest"
            return c
        media_url, media_text = final_url, text
        if "#EXT-X-STREAM-INF" in text:
            variants = uris(text)
            if not variants:
                c.status, c.detail = "invalid_manifest", "master playlist without variants"
                return c
            media_url = urllib.parse.urljoin(final_url, variants[-1])
            code, media_url, body = http_get(media_url, c.user_agent, c.referrer)
            if code not in (200, 206):
                c.status, c.detail = classify_http(code), f"variant HTTP {code}"
                return c
            media_text = body.decode("utf-8", "replace")
        if re.search(r'#EXT-X-(SESSION-)?KEY:[^\n]*(METHOD=SAMPLE-AES|KEYFORMAT="(?!identity))', media_text):
            c.status, c.detail = "drm", "protected by DRM, not playable without a licence"
            return c
        segments = uris(media_text)
        if not segments:
            c.status, c.detail = "invalid_manifest", "media playlist without segments"
            return c
        seg_url = urllib.parse.urljoin(media_url, segments[-1])
        code, _, seg = http_get(seg_url, c.user_agent, c.referrer, byte_range=SEGMENT_PROBE_BYTES,
                                limit=SEGMENT_PROBE_BYTES + 1)
        if code not in (200, 206) or not seg:
            c.status, c.detail = classify_http(code) if code not in (200, 206) else "empty_segment", f"segment HTTP {code}"
            return c
        c.status, c.detail = "ok", f"manifest + segment downloaded ({len(seg)} bytes)"
    except Exception as e:  # any failure of a remote stream is a result, never a crash
        reason = getattr(e, "reason", e)
        c.status = "timeout" if "timed out" in str(reason) else "network_error"
        c.detail = f"{type(e).__name__}: {str(reason)[:140]}"
    return c


EXTINF_ATTR = re.compile(r'([a-zA-Z0-9-]+)="([^"]*)"')
QUALITY = re.compile(r"\((\d{3,4})[pi]\)")
LABEL = re.compile(r"\[([^\]]+)\]")


def parse_m3u(text: str):
    attrs, name, ua, ref = None, "", None, None
    for raw in text.splitlines():
        line = raw.strip()
        if line.startswith("#EXTINF"):
            attrs = dict(EXTINF_ATTR.findall(line))
            name = line.split(",", 1)[-1].strip() if "," in line else ""
            ua, ref = attrs.get("user-agent"), attrs.get("referrer")
        elif line.startswith("#EXTVLCOPT:http-user-agent="):
            ua = line.split("=", 1)[1]
        elif line.startswith("#EXTVLCOPT:http-referrer="):
            ref = line.split("=", 1)[1]
        elif line and not line.startswith("#") and attrs is not None:
            yield attrs.get("tvg-id", ""), name, line, ua, ref
            attrs = None


def fetch_text(url: str) -> str:
    code, _, body = http_get(url, None, None, limit=50 * 1024 * 1024)
    if code != 200:
        raise RuntimeError(f"cannot download {url}: HTTP {code}")
    return body.decode("utf-8", "replace")


def load_csv(name: str) -> list[dict]:
    return list(csv.DictReader(io.StringIO(fetch_text(f"{RAW}/database/master/data/{name}"))))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--rules", default="catalog/official_sources.json")
    ap.add_argument("--out", default="app/src/main/assets/catalog.json")
    ap.add_argument("--report", default="catalog/REPORT.md")
    ap.add_argument("--check", action="store_true", help="probe every candidate stream")
    ap.add_argument("--checked-from", default="unknown")
    args = ap.parse_args()

    rules = json.load(open(args.rules, encoding="utf-8"))
    by_id = {r["id"]: r for r in rules["rules"]}
    found: dict[str, list[Candidate]] = {r["id"]: [] for r in rules["rules"]}

    for cc in rules["countries"]:
        try:
            text = fetch_text(f"{RAW}/iptv/master/streams/{cc}.m3u")
        except RuntimeError as e:
            print(f"warning: {e}", file=sys.stderr)
            continue
        for tvg_id, name, url, ua, ref in parse_m3u(text):
            rule = by_id.get(tvg_id)
            if not rule or not re.search(rule["url_regex"], url):
                continue
            if any(c.url == url for c in found[tvg_id]):
                continue
            q = QUALITY.search(name)
            found[tvg_id].append(Candidate(url=url, name=QUALITY.sub("", LABEL.sub("", name)).strip(),
                                           quality=int(q.group(1)) if q else 0, user_agent=ua, referrer=ref,
                                           labels=LABEL.findall(name)))

    for tvg_id in found:
        found[tvg_id].sort(key=lambda c: -c.quality)
        found[tvg_id] = found[tvg_id][:MAX_CANDIDATES_PER_CHANNEL]

    channels_db = {row["id"]: row for row in load_csv("channels.csv")}
    logos: dict[str, str] = {}
    for row in load_csv("logos.csv"):
        key = row["channel"] + ("@" + row["feed"] if row.get("feed") else "")
        logos.setdefault(key, row["url"])
        logos.setdefault(row["channel"], row["url"])

    if args.check:
        flat = [c for cands in found.values() for c in cands]
        with ThreadPoolExecutor(max_workers=12) as pool:
            list(pool.map(probe, flat))

    out_channels, report = [], []
    for rule in rules["rules"]:
        cands = found[rule["id"]]
        base_id, _, feed = rule["id"].partition("@")
        db = channels_db.get(base_id, {})
        if not cands:
            report.append((rule["id"], db.get("name", base_id), "not_listed", "no URL matching the broadcaster rule"))
            continue
        cands.sort(key=lambda c: (c.status != "ok", -c.quality))
        best = cands[0]
        display = best.name or db.get("name") or base_id
        out_channels.append({
            "id": rule["id"],
            "name": display,
            "logo": logos.get(rule["id"]) or logos.get(base_id),
            "category": rule["cat"],
            "language": rule["lang"],
            "country": db.get("country", ""),
            "website": db.get("website", ""),
            "owner": rule["owner"],
            "geo": rule.get("geo"),
            "status": best.status,
            "streams": [{"url": c.url, "userAgent": c.user_agent, "referrer": c.referrer, "quality": c.quality,
                         "status": c.status, "detail": c.detail, "checkedAt": c.checked_at} for c in cands],
        })
        report.append((rule["id"], display, best.status, best.detail))

    payload = {
        "schema": 1,
        "generatedAt": int(time.time()),
        "checkedFrom": args.checked_from if args.check else None,
        "policy": rules["_doc"],
        "channels": out_channels,
    }
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=1)

    ok = sum(1 for r in report if r[2] == "ok")
    with open(args.report, "w", encoding="utf-8") as f:
        f.write(f"# Built-in catalog check\n\nGenerated: {time.strftime('%Y-%m-%d %H:%M UTC', time.gmtime())}  \n")
        f.write(f"Checked from: {payload['checkedFrom']}  \nChannels with a reachable stream: {ok} / {len(report)}\n\n")
        f.write("| Channel | Rule id | Result | Detail |\n|---|---|---|---|\n")
        for rid, name, status, detail in report:
            f.write(f"| {name} | `{rid}` | {status} | {detail.replace('|', '/')} |\n")
    print(f"catalog: {len(out_channels)} channels written, {ok} verified ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
