#!/usr/bin/env python3
"""Cross-check the README's log-marker table against the markers the module declares.

Usage: check_markers.py [--show]

## Why this is a set relation and not a text match

The README table is what a device log gets read against, so a row promising a line the build cannot
print sends the reader hunting for output that will never appear.

Keeping it honest by comparing README prose to Kotlin string literals was tried three times and failed
three times. README reader-form (`N row(s)`) and template form (`${rows.size} row(s)`) only line up
through heuristics; each heuristic changed *which* markers it got wrong without reducing the count,
which is the signature of a judgement made at the wrong layer rather than a bug in the judgement.

So the marker words now exist as constants in `ProbeMarkers.kt`, the log lines are built from those
constants, and this compares two sets of **literal marker strings** -- the ones declared in the object
against the ones quoted in the README. No similarity measure is involved, so there is nothing left to
tune: a marker is either declared and documented, or it is reported.

Two directions, both of which have bitten this project:

  * documented but not declared -> the README promises a line that cannot appear
  * declared but not documented -> the log prints something the table cannot explain

Exits non-zero on either, so this gates a release.
"""
import os
import re
import sys

R = os.environ.get("GITHUB_WORKSPACE") or os.path.dirname(os.path.dirname(
    os.path.abspath(__file__)))
MARKERS_KT = f"{R}/app/src/main/java/com/jiesa/xvideocatcher/hook/ProbeMarkers.kt"
# Every file that builds a log line from a ProbeMarkers constant.
#
# A file missing from this list is not a small omission: the "declared but never used" check below
# reads only these, so a marker used solely in an unlisted file looks like decoration and gets
# reported, while a marker used *nowhere* in an unlisted file passes unnoticed. ShareSheetInjector
# was added when 1.11.0 started logging, for exactly that reason -- its INJECT markers would
# otherwise have sat outside this gate while appearing to be covered by it.
SOURCES = [
    f"{R}/app/src/main/java/com/jiesa/xvideocatcher/hook/SharePathProbe.kt",
    f"{R}/app/src/main/java/com/jiesa/xvideocatcher/hook/XVideoCatcherModule.kt",
    f"{R}/app/src/main/java/com/jiesa/xvideocatcher/hook/ShareSheetInjector.kt",
]
README = f"{R}/README.md"


def declared():
    """{constant name: marker text} from ProbeMarkers.kt."""
    src = open(MARKERS_KT, encoding="utf-8").read()
    out = {}
    for name, text in re.findall(r'const val (\w+)\s*=\s*"((?:[^"\\]|\\.)*)"', src):
        out[name] = text
    return out


def in_all_list():
    """Constant names listed in ProbeMarkers.ALL."""
    src = open(MARKERS_KT, encoding="utf-8").read()
    m = re.search(r"val ALL[^=]*=\s*listOf\((.*?)\)", src, re.S)
    if not m:
        return set()
    return set(re.findall(r"\b([A-Z][A-Z0-9_]+)\b", m.group(1)))


def documented(markers):
    """Marker texts that appear inside backticks somewhere in the README."""
    s = open(README, encoding="utf-8").read()
    # Fenced blocks first: a ``` fence contains single backticks, so scanning for inline spans
    # without removing fences pairs a fence delimiter with an unrelated backtick and swallows whole
    # sections into one bogus "span". That is why an earlier run reported every marker undocumented
    # while the table plainly contained them -- the parse was wrong, not the README.
    without_fences = re.sub(r"```.*?```", "\n", s, flags=re.S)
    quoted = re.findall(r"`([^`\n]+)`", without_fences)
    hits = set()
    for name, text in markers.items():
        # Exact containment in a quoted span: the README may add reader-form detail after the marker
        # word, but the declared word itself has to be there verbatim.
        if any(text in q for q in quoted):
            hits.add(name)
    return hits


def main():
    markers = declared()
    listed = in_all_list()
    docs = documented(markers)

    print(f"declared markers: {len(markers)}")
    print(f"listed in ALL:    {len(listed)}")
    print(f"documented:       {len(docs)}")

    if "--show" in sys.argv:
        for n, t in sorted(markers.items()):
            flags = []
            if n not in docs:
                flags.append("UNDOCUMENTED")
            if n not in listed:
                flags.append("NOT IN ALL")
            print(f"   {n:16} {t!r} {' '.join(flags)}")

    problems = []

    missing_docs = sorted(set(markers) - docs)
    if missing_docs:
        problems.append("declared but not documented: " + ", ".join(missing_docs))

    missing_all = sorted(set(markers) - listed)
    if missing_all:
        # ALL is what the unit test iterates, so a constant left out of it is unguarded.
        problems.append("declared but missing from ALL: " + ", ".join(missing_all))

    # Every declared marker must actually be used to build a log line, or it is decoration.
    used = set()
    joined = "".join(open(p, encoding="utf-8").read() for p in SOURCES)
    for name in markers:
        if f"ProbeMarkers.{name}" in joined:
            used.add(name)
    unused = sorted(set(markers) - used)
    if unused:
        problems.append("declared but never emitted: " + ", ".join(unused))

    if problems:
        print()
        for p in problems:
            print("FAIL:", p)
        return 1
    print("\nREADME table, ProbeMarkers.ALL and the emitting code agree")
    return 0


sys.exit(main())
