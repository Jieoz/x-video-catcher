#!/usr/bin/env python3
"""Prove the marker gate can fail, on each axis it claims to cover.

The gate went green immediately after the constants were adopted, which is exactly when a gate is
least trustworthy: green-on-first-run is indistinguishable from a gate that cannot fail. Three
ablations, one per axis, each expected to trip a *different* check.
"""
import os
import re
import shutil
import subprocess
import sys

R = os.environ.get("GITHUB_WORKSPACE") or os.path.dirname(os.path.dirname(
    os.path.abspath(__file__)))
MARKERS = f"{R}/app/src/main/java/com/jiesa/xvideocatcher/hook/ProbeMarkers.kt"
README = f"{R}/README.md"
PROBE = f"{R}/app/src/main/java/com/jiesa/xvideocatcher/hook/SharePathProbe.kt"
GATE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "check_markers.py")


def gate():
    r = subprocess.run(["python3", GATE], capture_output=True, text=True, cwd=R)
    fails = [l for l in r.stdout.splitlines() if l.startswith("FAIL:")]
    return r.returncode, fails


def main():
    rc, fails = gate()
    print(f"=== BASELINE: exit={rc} ===")
    if rc != 0:
        print("baseline not green:", fails)
        return 1

    cases = [
        (
            "undocument a marker (drop its README row)",
            README,
            lambda s: s.replace("`PROBE rows built:", "`PROBE rows assembled:", 1),
            "not documented",
        ),
        (
            "stop emitting a declared marker",
            PROBE,
            lambda s: s.replace("${ProbeMarkers.SHEET_OPENED}", "PROBE sheet opened via", 1),
            "never emitted",
        ),
        (
            "leave a declared marker out of ALL",
            MARKERS,
            lambda s: re.sub(r"\bTWEET_FOUND,\s*", "", s, count=1),
            "missing from ALL",
        ),
    ]

    problems = []
    for label, path, mutate, expect in cases:
        shutil.copy(path, path + ".ablate.bak")
        src = open(path, encoding="utf-8").read()
        mutated = mutate(src)
        if mutated == src:
            print(f"\n!!! {label}: mutation had no effect -- ablation invalid")
            problems.append(label)
            shutil.copy(path + ".ablate.bak", path)
            continue
        open(path, "w", encoding="utf-8").write(mutated)
        try:
            rc2, fails2 = gate()
            hit = any(expect in f for f in fails2)
            print(f"\n=== ABLATE {label} ===")
            print(f"  expected axis: {expect}")
            print(f"  exit={rc2}")
            for f in fails2:
                print("   ", f)
            if rc2 == 0:
                print("  [NOT LOAD-BEARING: gate stayed green]")
                problems.append(label)
            elif not hit:
                print(f"  [WRONG AXIS: failed, but not on {expect!r}]")
                problems.append(label)
            else:
                print("  [LOAD-BEARING]")
        finally:
            shutil.copy(path + ".ablate.bak", path)
            subprocess.run(["rm", "-f", path + ".ablate.bak"])

    rc3, fails3 = gate()
    print(f"\n=== RESTORED: exit={rc3} ===")
    if rc3 != 0:
        print("restore failed:", fails3)
        problems.append("restore")

    print("\nRESULT:", "all axes load-bearing" if not problems else f"PROBLEMS: {problems}")
    return 1 if problems else 0


sys.exit(main())
