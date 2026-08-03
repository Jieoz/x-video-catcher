#!/usr/bin/env python3
"""Ablation for the three reachability axes.

A gate that passes proves nothing on its own; each axis has to be shown to be the thing that
produces the verdict. Three ablations, each expected to break exactly one case and leave the others
alone:

  A. hierarchy widening off (classes=None)        -> "override via supertype" must be REJECTED
  B. instantiation evidence off                   -> "orphan override" must be ACCEPTED
  C. self-super exclusion off                     -> "self-super-only" must be ACCEPTED
  D. exclusion keyed on hierarchy, not opcode     -> "sibling delegation" must be REJECTED

If an ablation changes nothing, that axis is decoration. D is the inverse shape: it is not a
feature being removed but the plausible wrong implementation of C, and it must break a case the
correct one keeps -- otherwise the opcode test is unjustified precision.
"""
import sys

sys.path.insert(0, __file__.rsplit("/", 1)[0])

import dexinvoke
import verify_host_anchors as v
from dexdefs import load_classes

CASES = ["dead", "direct", "override", "override_orphan", "self_super_only",
         "sibling_interface_delegation"]


def verdict(apk, classes):
    results = {"sheet_open": [(v.CHOOSER_PKG + "j;", "J0")],
               "row": [], "provider": [], "dispatch": []}
    counts, rf = v.check_reachability(apk, results, classes)
    n = next(iter(counts.values()), 0) if counts else 0
    return ("PASS" if not rf else "FAIL"), n, (rf[0][:70] if rf else "")


def main():
    apks = {k: v._fixture_apk(k) for k in CASES}
    classes = {k: load_classes(apks[k]) for k in CASES}

    print("=== baseline (both axes on) ===")
    base = {}
    for k in CASES:
        base[k] = verdict(apks[k], classes[k])
        print("  %-16s %s  sites=%d  %s" % (k, base[k][0], base[k][1], base[k][2]))

    print("\n=== ablation A: hierarchy widening OFF (classes=None) ===")
    a = {}
    for k in CASES:
        a[k] = verdict(apks[k], None)
        print("  %-16s %s  sites=%d  %s" % (k, a[k][0], a[k][1], a[k][2]))

    print("\n=== ablation B: instantiation evidence OFF ===")
    real_find_new = v.find_instantiations
    # Blind the axis the way the old code was blind to it: pretend every class is constructed, so the
    # verdict can no longer depend on instantiation evidence. Everything else stays identical.
    v.find_instantiations = lambda apk, types, **kw: {t: ["<ablated>"] for t in types}
    b = {}
    for k in CASES:
        b[k] = verdict(apks[k], classes[k])
        print("  %-16s %s  sites=%d  %s" % (k, b[k][0], b[k][1], b[k][2]))
    v.find_instantiations = real_find_new

    print("\n=== ablation C: self-super exclusion OFF ===")
    real_is_self_super = dexinvoke.CallSite.is_self_super
    # Count a super call as a real entry point, which is what counting raw call sites did.
    dexinvoke.CallSite.is_self_super = lambda self: False
    c = {}
    for k in CASES:
        c[k] = verdict(apks[k], classes[k])
        print("  %-16s %s  sites=%d  %s" % (k, c[k][0], c[k][1], c[k][2]))
    dexinvoke.CallSite.is_self_super = real_is_self_super

    print("\n=== ablation D: exclusion keyed on hierarchy instead of opcode ===")
    # The plausible-but-wrong version of axis C: drop any call whose caller is in the hierarchy,
    # regardless of opcode. Must break sibling interface delegation, which is live in the real host.
    dexinvoke.CallSite.is_self_super = lambda self: True
    d = {}
    for k in CASES:
        d[k] = verdict(apks[k], classes[k])
        print("  %-30s %s  sites=%d  %s" % (k, d[k][0], d[k][1], d[k][2]))
    dexinvoke.CallSite.is_self_super = real_is_self_super

    print("\n=== load-bearing verdict ===")
    ok = True
    checks = [
        ("A breaks override acceptance",
         base["override"][0] == "PASS" and a["override"][0] == "FAIL"),
        ("A leaves direct call alone",
         base["direct"][0] == "PASS" and a["direct"][0] == "PASS"),
        ("B breaks orphan rejection",
         base["override_orphan"][0] == "FAIL" and b["override_orphan"][0] == "PASS"),
        ("B leaves genuine override alone",
         base["override"][0] == "PASS" and b["override"][0] == "PASS"),
        ("C breaks self-super rejection",
         base["self_super_only"][0] == "FAIL" and c["self_super_only"][0] == "PASS"),
        ("C leaves genuine override alone",
         base["override"][0] == "PASS" and c["override"][0] == "PASS"),
        ("C keeps sibling delegation live",
         base["sibling_interface_delegation"][0] == "PASS"
         and c["sibling_interface_delegation"][0] == "PASS"),
        ("D (hierarchy-keyed) kills sibling delegation",
         base["sibling_interface_delegation"][0] == "PASS"
         and d["sibling_interface_delegation"][0] == "FAIL"),
        ("dead stays rejected in every config",
         base["dead"][0] == a["dead"][0] == b["dead"][0] == c["dead"][0]
         == d["dead"][0] == "FAIL"),
    ]
    for label, good in checks:
        print("  %-44s %s" % (label, "ok" if good else "NOT LOAD-BEARING"))
        ok = ok and good
    print("\n%s" % ("all axes load-bearing, opcode test justified"
                    if ok else "ABLATION FAILED"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
