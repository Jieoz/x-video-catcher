#!/usr/bin/env python3
"""Ablation for anchor 7, the share-sheet capture chain.

Anchor 7 went green on the first run against the release APK. That is exactly when a gate is
least trustworthy: the whole reason it exists is that the previous anchor set was recorded from
the BETA bundle, passed nothing, and shipped to a phone as
``com.twitter.model.core.entity.b0 not found``. So every constant it asserts is mutated here and
required to turn the gate red.

Each ablation is a name this host does not use. If the gate stays green under one, that assertion
is decoration and cannot report the next obfuscation rename -- which is the only job it has.

The APK is parsed ONCE and the class index reused, because parsing 231k classes per axis would
make this too slow to run and a gate nobody runs is worse than no gate.

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
    ("controller class renamed",
     "SHEET_CONTROLLER", "Lcom/twitter/tweet/action/legacy/h0;", "capture"),
    ("tweet field moved",
     "SHEET_TWEET_FIELD", "c", "capture"),
    ("rows field moved",
     "SHEET_ROWS_FIELD", "d", "sheet rows"),
    ("show method renamed",
     "SHEET_SHOW_METHOD", "g", "hook point"),
    ("controller package renamed",
     "SHEET_PKG", "Lcom/twitter/tweet/action/modern/", "capture"),
    ("wrapper class renamed",
     "TWEET_WRAPPER", "Lcom/twitter/model/core/f;", "capture"),
    ("body class renamed",
     "TWEET_BODY", "Lcom/twitter/model/core/c;", "wrapper"),
    ("id getter renamed",
     "TWEET_ID_GETTER", "getTweetId", "lookup key"),
    ("media entity renamed (the real 1.10.0 bug)",
     "MEDIA_ENTITY", "Lcom/twitter/model/core/entity/b0;", "media type"),
    ("media type enum renamed",
     "MEDIA_TYPE_ENUM", "Lcom/twitter/model/core/entity/c0$e;", "media type"),
    ("video info renamed",
     "VIDEO_INFO", "Lcom/twitter/media/av/model/y;", "video info"),
    # b0 is the sharpest possible wrong answer here: it sits next to a0 in the same package and
    # also starts with an int, so it satisfied a bitrate-only check. It is caught on the URL
    # field, which is the thing the download actually needs.
    ("video variant renamed",
     "VIDEO_VARIANT", "Lcom/twitter/media/av/model/b0;", "variant url"),
    ("media constant no longer required",
     "MEDIA_TYPE_CONSTANTS", ("VIDEO", "ANIMATED_GIF", "IMAGE", "LIVEPHOTO"), "media type"),
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
