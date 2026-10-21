#!/usr/bin/env python3
"""Builds RESULTS.md (release notes) from unit-test XML, device reports and catalog checks."""
import glob
import json
import os
import xml.etree.ElementTree as ET

out = ["# TCL Stream X: CI results\n"]

tests = fails = skipped = 0
failed_names = []
for f in glob.glob("app/build/test-results/testDebugUnitTest/*.xml"):
    root = ET.parse(f).getroot()
    tests += int(root.get("tests", 0))
    skipped += int(root.get("skipped", 0))
    for tc in root.iter("testcase"):
        if tc.find("failure") is not None or tc.find("error") is not None:
            fails += 1
            failed_names.append(f"{tc.get('classname', '').split('.')[-1]}.{tc.get('name')}")
out.append(f"## Unit tests (JVM)\n\n{tests - fails - skipped} passed, {fails} failed, {skipped} skipped.")
out += [f"- FAILED {n}" for n in failed_names]

def load(p):
    try:
        return json.load(open(p, encoding="utf-8"))
    except (OSError, ValueError):
        return None

for cls in ["ImportOnDeviceTest", "PlaybackOnDeviceTest", "UiTourTest"]:
    p = f"ci-out/{cls}.txt"
    if os.path.exists(p):
        ok = any(l.startswith("OK (") for l in open(p, encoding="utf-8", errors="replace"))
        out.append(f"\n- Emulator `{cls}`: {'passed' if ok else 'FAILED'}")

imp = load("ci-out/import-report.json")
if imp:
    out.append(f"\n## Large playlist import (emulator)\n\n{imp['channels']:,} channels, {imp['fileMb']} MB file: imported in {imp['importMs']/1000:.1f} s, "
               f"heap delta {imp['heapDeltaMb']} MB, group page query {imp['groupPageQueryMs']} ms, search {imp['searchMs']} ms.")

pb = load("ci-out/playback-report.json")
if pb:
    out.append(f"\n## Real playback on the emulator ({pb['device']})\n")
    out.append("| Stream | Group | Ready | Time playing | First frame | Video | Quality options | Audio | Subtitles | Error |")
    out.append("|---|---|---|---|---|---|---|---|---|---|")
    for r in pb["results"]:
        out.append(f"| {r['name']} | {r['group']} | {'yes' if r['ready'] else 'no'} ({r['readyMs']} ms) | {r["advancedMs"]/1000:.1f} s | "
                   f"{'yes' if r['firstFrameRendered'] else 'no'} | {r['video']} | {r['qualityOptions']} | {r['audioTracks']} | {r['textOptions']} | {r.get('error', '')} |")

if os.path.exists("catalog/REPORT.md"):
    out.append("\n" + open("catalog/REPORT.md", encoding="utf-8").read().replace("# Built-in catalog check", "## Built-in catalog check (from CI)"))
if os.path.exists("catalog/SOURCES_REPORT.md"):
    out.append("\n" + open("catalog/SOURCES_REPORT.md", encoding="utf-8").read().replace("# Source probe", "## Other sources (from CI)"))

open("RESULTS.md", "w", encoding="utf-8").write("\n".join(out) + "\n")
print("\n".join(out)[:3000])
