#!/usr/bin/env python3
"""Ablate the tweet-model predicate: prove the shape test is load-bearing, axis by axis.

The predicate is where four releases died. 1.2-1.4 matched class names, 1.7 matched a package
prefix, and up to 1.9.0 it matched a three-package whitelist while carrying a comment claiming
to be structural -- measured on 20260804: two fixtures of identical shape, differing only in
the media entity's package, got opposite verdicts. So a green CriterionTest has already proven
worthless once, and each axis below has to be shown capable of failing.

Axes, each independent:
  1. package-bet     -- go back to the three-package whitelist (the 1.9.0 behaviour)
  2. url-only        -- drop the numeric requirement (predicate matches every config holder)
  3. numbers-only    -- drop the URL requirement (matches every geometry class)
  4. no-generics     -- stop reading erased element types (media held in a List becomes invisible)
  5. name-match      -- accept anything whose own name mentions media, ignoring shape
  6. one-number      -- lower the numeric floor to 1 (String+int is not a media variant)
  7. any-string-is-url -- accept any String as the URL (matches every codec/buffer config)

Run inside the android-builder container against a real gradle run. Restores on failure.
"""
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

# Derived, never literal: a hardcoded repo path only works on the machine that wrote it, and CI
# failed with FileNotFoundError on exactly that mistake.
REPO = pathlib.Path(__file__).resolve().parent.parent
SRC = REPO / "app/src/main/java/com/jiesa/xvideocatcher/hook/HostResolver.kt"
BACKUP = pathlib.Path(tempfile.mkdtemp(prefix="ablate-criterion-")) / "HostResolver.kt"

# Literal anchors, copied verbatim from the source. Deriving them would make them un-driftable
# and so incapable of reporting that the predicate was rewritten underneath this script.
SHAPE_RETURN = "        return url && numbers >= MEDIA_ENTITY_MIN_NUMBERS"

URL_CHECK = """                if (f.type == String::class.java && looksLikeUrlField(f.name)) url = true"""

NUMBER_CHECK = """                if (isNumeric(f.type)) numbers++"""

GENERIC_READ = """                for (arg in typeArgumentsOf(f)) if (isMediaEntity(arg)) return true"""

ENTITY_GUARD = """        if (type.isEnum || type.isPrimitive || type.isArray) return false
        if (type.name.startsWith("java.") || type.name.startsWith("kotlin.")) return false"""

MIN_NUMBERS = "    private const val MEDIA_ENTITY_MIN_NUMBERS = 2"


def gradle_failures() -> set:
    """Names of failing tests from a real gradle run."""
    r = subprocess.run(
        ["gradle", "--offline", "testDebugUnitTest", "--rerun-tasks"],
        cwd=REPO, capture_output=True, text=True, timeout=1800,
    )
    out = r.stdout + r.stderr
    if "BUILD SUCCESSFUL" in out:
        return set()
    names = set(re.findall(r"^\s*\S+ > (.+?) FAILED\s*$", out, re.M))
    if not names and "BUILD FAILED" in out:
        tail = "\n".join(out.strip().splitlines()[-25:])
        raise SystemExit("gradle failed without per-test names:\n" + tail)
    return names


ABLATIONS = []


def ablation(name, expect):
    def deco(fn):
        ABLATIONS.append((name, fn, expect))
        return fn
    return deco


@ablation("package-bet", "accepts media in a package no host build has used")
def _package_bet(s):
    """Restore the whitelist the device log proved dead.

    The predicate becomes "is the field's type in one of three packages", which is what shipped
    through 1.9.0. Every fixture in a host-shaped package still passes, so only an axis using a
    package X has never shipped can catch this.
    """
    assert s.count(SHAPE_RETURN) == 1, "shape return anchor missing"
    whitelist = '''        val packages = listOf(
            "com.x.models.media", "com.twitter.model.core.entity", "com.twitter.media.av.model",
        )
        return packages.any { type.name.startsWith(it) }'''
    return s.replace(SHAPE_RETURN, whitelist)


@ablation("url-only", "rejects a holder whose url carries no dimensions")
def _url_only(s):
    """Accept on a URL alone: matches endpoint configs, avatars, analytics payloads."""
    assert s.count(SHAPE_RETURN) == 1, "shape return anchor missing"
    return s.replace(SHAPE_RETURN, "        return url")


@ablation("numbers-only", "rejects a holder of numbers without a url")
def _numbers_only(s):
    """Accept on numbers alone: matches every geometry and timing class."""
    assert s.count(SHAPE_RETURN) == 1, "shape return anchor missing"
    return s.replace(SHAPE_RETURN, "        return numbers >= MEDIA_ENTITY_MIN_NUMBERS")


@ablation("no-generics", "accepts renamed media arriving as a list")
def _no_generics(s):
    """Stop recovering erased element types.

    Media arrives as List<Entity>, so a predicate that only inspects declared field types cannot
    see it. This is a real shape on the device, not a hypothetical one.
    """
    assert s.count(GENERIC_READ) == 1, "generic read anchor missing"
    return s.replace(GENERIC_READ, "")


@ablation("name-match", "rejects a class that merely mentions media in its own name")
def _name_match(s):
    """Decide by the class's own name instead of its shape -- the 1.2-1.4 failure mode.

    Replaces the *outer* predicate. Patching `isMediaEntity` instead left this ablation unable to
    fire: a class whose own name mentions media never reaches the inner function, because
    `holdsMediaEntities` walks its fields and returns false first.
    """
    outer = """        if (type.isEnum || type.isPrimitive || type.isArray) return false
        return holdsMediaEntities(type)"""
    assert s.count(outer) == 1, "outer predicate anchor missing"
    named = """        if (type.isEnum || type.isPrimitive || type.isArray) return false
        return type.name.lowercase().let { it.contains("media") || it.contains("tweet") }"""
    return s.replace(outer, named)


@ablation("any-string-is-url", "rejects two numbers beside a string that is not a url")
def _any_string_is_url(s):
    """Accept any String as the URL, dropping the field-name signal.

    The predicate degenerates into "declares a String and two numbers", which is the shape of codec
    configs, buffer settings, cache entries and window metrics. ablate_search.py ran this mutation
    and the whole suite stayed green, so the axis was missing entirely.
    """
    assert s.count(URL_CHECK) == 1, "url check anchor missing"
    return s.replace(
        URL_CHECK,
        "                if (f.type == String::class.java) url = true",
    )


@ablation("one-number", "rejects a url with a single number alongside it")
def _one_number(s):
    """Lower the floor to one number: String+int is most of an app's model layer."""
    assert s.count(MIN_NUMBERS) == 1, "min-numbers anchor missing"
    return s.replace(MIN_NUMBERS, "    private const val MEDIA_ENTITY_MIN_NUMBERS = 1")


def main():
    original = SRC.read_text()
    shutil.copy(SRC, BACKUP)

    print("baseline ...", flush=True)
    base = gradle_failures()
    if base:
        SRC.write_text(original)
        raise SystemExit("baseline is NOT green, refusing to ablate: %s" % sorted(base))
    print("baseline green\n", flush=True)

    results = []
    try:
        for name, mutate, expect in ABLATIONS:
            SRC.write_text(mutate(original))
            failures = gradle_failures()
            SRC.write_text(original)

            hit = expect in failures
            results.append((name, expect, hit, sorted(failures)))
            print("%-14s %s" % (name, "LOAD-BEARING" if hit else "*** NOT LOAD-BEARING ***"))
            print("    expected red: %s" % expect)
            print("    actually red: %s\n" % (sorted(failures) or "NOTHING"), flush=True)
    finally:
        SRC.write_text(original)
        assert SRC.read_text() == original, "failed to restore HostResolver.kt"

    print("=" * 60)
    bad = [r for r in results if not r[2]]
    for name, expect, hit, failures in results:
        print("%-14s %s" % (name, "ok" if hit else "FAIL"))
    if bad:
        raise SystemExit("\n%d ablation(s) did not make the intended test fail" % len(bad))
    print("\nall %d axes load-bearing" % len(results))


if __name__ == "__main__":
    main()
