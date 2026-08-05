#!/usr/bin/env python3
"""Ablation for anchor 7, the media capture chain.

Anchor 7 verifies that the player's ``DataSpec`` still looks the way MediaSpy expects. It replaced
a chain rooted at the legacy share-sheet controller, which had been green for five releases while
the class it asserted was never instantiated at runtime -- the gate proved structural presence and
was read as proof of use. 1.11 shipped on it and did nothing on the device.

So every constant the new gate asserts is mutated here and required to turn it red. A green run
under any ablation means that assertion is decoration and cannot report the next R8 rename, which
is the only job it has.

The APK is parsed ONCE and the class index reused: parsing 231k classes per axis would make this
too slow to run, and a gate nobody runs is worse than no gate.

Usage: ablate_capture.py <host.apk>
"""
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import verify_host_anchors as v  # noqa: E402
from dexdefs import load_classes  # noqa: E402

# Each entry: (label, attribute on the module, bad value, which failure text must appear).
# The bad values are deliberately plausible -- neighbouring letters in the same package, the kind
# of thing the next R8 run actually produces -- not obvious garbage like "NOPE".
ABLATIONS = [
    # The class letter. This is the drift that actually happens: `j` here, and a different letter
    # in the next bundle. Neighbouring letters in the same package are used rather than obvious
    # garbage, because a plausible wrong answer is what R8 produces.
    # Expects "re-measured": on this host the neighbouring letter is a real class in the same
    # package, so the gate does not take its `class is None` path -- it sees a class whose field
    # sequence does not match. Asserting the message that actually distinguishes a drifted name
    # from a changed DataSpec, rather than the one I assumed would fire.
    ("dataspec class renamed",
     "DATASPEC", "Landroidx/media3/datasource/i;", "re-measured"),
    # The package. If this is renamed, shape matching has nowhere to search and every field
    # assertion below is void, so it has to fail loudly and first.
    ("media3 datasource package renamed",
     "MEDIA3_DATASOURCE_PKG", "Landroidx/media3/datasrc/", "package"),
    # The field sequence, mutated one type at a time. Both ends are covered: the Uri the hook
    # reads positionally, and a tail field, so an assertion that only checked the head would show
    # up here.
    ("field sequence: uri type changed",
     "DATASPEC_FIELDS",
     ("Ljava/lang/String;", "J", "I", "[B", "Ljava/util/Map;",
      "J", "J", "Ljava/lang/String;", "I"),
     "fields are"),
    ("field sequence: tail field changed",
     "DATASPEC_FIELDS",
     ("Landroid/net/Uri;", "J", "I", "[B", "Ljava/util/Map;",
      "J", "J", "Ljava/lang/String;", "J"),
     "fields are"),
    # Field count, which a subset comparison would miss.
    ("field sequence: one field dropped",
     "DATASPEC_FIELDS",
     ("Landroid/net/Uri;", "J", "I", "[B", "Ljava/util/Map;",
      "J", "J", "Ljava/lang/String;"),
     "fields are"),
    # The uri field letter the hook reads positionally.
    ("uri field moved",
     "DATASPEC_URI_FIELD", "b", "expected android.net.Uri"),
    # The builder. Pointing this at a class that does not exist must FAIL: otherwise the constant
    # is unprotected and the gate would keep reporting that it verified the finality discriminator
    # while having nothing to compare against.
    ("builder class renamed",
     "DATASPEC_BUILDER", "Landroidx/media3/datasource/j$b;", "builder name has drifted"),
]


def run(classes):
    """Return (verdict, failure_texts) for anchor 7 alone."""
    _, failures = v.analyse_capture_chain(classes)
    return ("PASS" if not failures else "FAIL"), failures


def main():
    if len(sys.argv) != 2:
        print(__doc__.strip().splitlines()[-1])
        return 2
    apk = sys.argv[1]

    classes = load_classes(apk)
    print("parsed %d classes once; reused across %d axes\n" % (len(classes), len(ABLATIONS)))

    verdict, failures = run(classes)
    print("=== baseline ===")
    print("  %s" % verdict)
    for f in failures:
        print("    %s" % f)
    if verdict != "PASS":
        print("\nBASELINE FAILS -- fix anchor 7 before ablating")
        return 1

    print("\n=== ablations (each must turn it red) ===")
    ok = True
    for label, attr, bad, want in ABLATIONS:
        real = getattr(v, attr)
        setattr(v, attr, bad)
        try:
            res, fails = run(classes)
        finally:
            setattr(v, attr, real)

        # Red is necessary but not sufficient: it must fail *for the stated reason*, otherwise a
        # single over-broad assertion could be absorbing several axes and hiding a real gap.
        matched = any(want in f for f in fails)
        good = res == "FAIL" and matched
        note = "ok" if good else ("NOT LOAD-BEARING" if res == "PASS" else "WRONG REASON")
        print("  %-42s %s  %s" % (label, res, note))
        if not good and fails:
            print("       got: %s" % fails[0][:96])
        ok = ok and good

    # The restore must be verified, not assumed: an ablation suite that leaves the module mutated
    # would poison every later axis and read as green.
    res, _ = run(classes)
    restored = res == "PASS"
    print("\n  %-42s %s" % ("module restored after ablation", "ok" if restored else "DIRTY"))
    ok = ok and restored

    print("\n%s" % ("anchor 7 is load-bearing on every constant"
                    if ok else "ABLATION FAILED -- some assertion is decoration"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
