#!/usr/bin/env python3
"""Verify a class and its methods really exist in a dex, by parsing the dex itself.

Written because dexdump in this image is an x86_64 binary that silently produced no output on this
ARM host (Exec format error) — its empty result looked exactly like "class not found", which is the
kind of false negative that gets a broken artifact shipped. A `strings` grep is the opposite
failure: it matches an unrelated string constant. This walks the method_ids table, so a hit means
the method is genuinely defined or referenced with that class as its owner.

Usage: dexcheck.py <class-substring> <method,method,...> <dex> [dex ...]
"""
import sys

sys.path.insert(0, __file__.rsplit("/", 1)[0])

from dexdefs import Dex  # noqa: E402


def main():
    if len(sys.argv) < 4:
        print(__doc__.strip())
        return 2

    want_class = sys.argv[1]
    want_methods = sys.argv[2].split(",")

    found = {}
    for path in sys.argv[3:]:
        with open(path, "rb") as fh:
            dex = Dex(fh.read())
        short = path.rsplit("/", 1)[-1]
        for owner, name in dex.method_ids():
            if owner and want_class in owner:
                found.setdefault(name, set()).add(short)

    print("class matching %r: %s" % (want_class, "FOUND" if found else "NOT FOUND"))
    missing = []
    for m in want_methods:
        where = found.get(m)
        if where:
            print("  ok       %s  (%s)" % (m, ", ".join(sorted(where))))
        else:
            print("  MISSING  %s" % m)
            missing.append(m)

    print("\nall methods in that class: %d" % len(found))
    for m in sorted(found):
        print("   ", m)
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
