#!/usr/bin/env python3
"""Ablation for the fingerprint agreement gate.

``verify_fingerprint.py`` exists because the reachability gate skips on CI without a 200MB host
APK, and a skipped gate reads like a passing one. But a gate added for that reason is worth
nothing until it has been red: this file mutates each input it claims to check and requires the
verdict to flip.

Both directions matter. Only-reject cases would pass even if the checker rejected everything;
the baseline-accepts case is what proves it does not. That asymmetry is not hypothetical -- the
module's own shape predicate shipped in a state where it rejected the real class too, and a suite
of negative cases stayed green.

Usage: ablate_fingerprint.py
"""
import copy
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
FP = os.path.join(HERE, "host_fingerprint.json")
GATE = os.path.join(HERE, "verify_fingerprint.py")
SPY = os.path.join(os.path.dirname(HERE), "app", "src", "main", "java", "com", "jiesa",
                   "xvideocatcher", "hook", "MediaSpy.kt")


def run_gate():
    r = subprocess.run([sys.executable, GATE], capture_output=True, text=True)
    return r.returncode, r.stdout + r.stderr


def main():
    original_fp = io.open(FP, encoding="utf-8").read()
    original_spy = io.open(SPY, encoding="utf-8").read()
    base = json.loads(original_fp)

    code, out = run_gate()
    print("=== baseline ===")
    print("  %s" % ("PASS" if code == 0 else "FAIL"))
    if code != 0:
        print(out)
        print("BASELINE FAILS -- fix the fingerprint before ablating")
        return 1

    cases = []

    # The fingerprint no longer proves what it claims.
    d = copy.deepcopy(base); d["dataspec"]["all_final"] = False
    cases.append(("recorded spec not final", d, None, "not fully final"))

    d = copy.deepcopy(base); d["dataspec"]["builder_present"] = "absent"
    cases.append(("builder was absent when measured", d, None, "was not present"))

    d = copy.deepcopy(base); d["construction_sites"] = []
    cases.append(("no construction sites recorded", d, None, "never established"))

    d = copy.deepcopy(base)
    d["construction_sites"] = ["com.example.other.a -> new androidx.media3.datasource.j"]
    cases.append(("construction outside media3", d, None, "would not see playback"))

    # The verifier's constants drift away from the recording.
    d = copy.deepcopy(base); d["dataspec"]["descriptor"] = "Landroidx/media3/datasource/k;"
    cases.append(("descriptor disagrees", d, None, "verifier DATASPEC is"))

    d = copy.deepcopy(base); d["dataspec"]["uri_field"] = "c"
    cases.append(("uri field disagrees", d, None, "DATASPEC_URI_FIELD"))

    d = copy.deepcopy(base)
    d["dataspec"]["field_types"] = ["Landroid/net/Uri;"] * 9
    cases.append(("field types disagree", d, None, "DATASPEC_FIELDS"))

    # The module diverges from the measured host.
    spy_bad_shape = original_spy.replace('"[B", "java.util.Map"', '"byte[]", "java.util.Map"')
    if spy_bad_shape == original_spy:
        print("  WARN    could not build the byte[] ablation; MediaSpy shape line changed")
        spy_bad_shape = None
    else:
        cases.append(("module writes byte[] not [B", None, spy_bad_shape, "does not match"))

    spy_no_final = original_spy.replace(
        "return fields.all { Modifier.isFinal(it.modifiers) }", "return true")
    if spy_no_final == original_spy:
        print("  WARN    could not build the finality ablation; MediaSpy final clause changed")
    else:
        cases.append(("module drops the finality clause", None, spy_no_final, "field finality"))

    print("\n=== ablations (each must turn it red) ===")
    ok = True
    for label, fp_mut, spy_mut, needle in cases:
        try:
            if fp_mut is not None:
                io.open(FP, "w", encoding="utf-8").write(json.dumps(fp_mut, indent=2,
                                                                    sort_keys=True) + "\n")
            if spy_mut is not None:
                io.open(SPY, "w", encoding="utf-8").write(spy_mut)
            code, out = run_gate()
        finally:
            io.open(FP, "w", encoding="utf-8").write(original_fp)
            io.open(SPY, "w", encoding="utf-8").write(original_spy)

        red = code != 0
        matched = needle is None or needle in out
        good = red and matched
        note = "ok" if good else ("NOT LOAD-BEARING" if not red else "WRONG REASON")
        print("  %-38s %s  %s" % (label, "FAIL" if red else "PASS", note))
        if not good:
            tail = [l for l in out.splitlines() if l.startswith("FAIL:")]
            if tail:
                print("       got: %s" % tail[0][:100])
        ok = ok and good

    # Restoration is asserted, not assumed: a suite that leaves either file mutated would poison
    # every later run and read as green.
    restored = (io.open(FP, encoding="utf-8").read() == original_fp
                and io.open(SPY, encoding="utf-8").read() == original_spy)
    code, _ = run_gate()
    print("\n  %-38s %s" % ("files restored byte-identical", "ok" if restored else "DIRTY"))
    print("  %-38s %s" % ("gate green again after restore", "ok" if code == 0 else "STILL RED"))
    ok = ok and restored and code == 0

    print("\n%s" % ("the fingerprint gate is load-bearing on every input"
                    if ok else "ABLATION FAILED -- some check is decoration"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
