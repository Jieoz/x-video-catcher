#!/usr/bin/env python3
"""Ablate container transparency: prove ContainerDepthTest is load-bearing.

Axes, each reintroducing one specific defect:
  1. opaque-container  -- go back to enqueuing the container as its own node (the measured bug:
                          a tweet 4 hops down was not found)
  2. no-field-flatten  -- flatten only when the container is already a node, not when it is a
                          field value (the half-fix that looks right and changes nothing)
  3. no-nested         -- refuse nested containers, so list-of-lists hides a tweet
  4. unbounded-fanout  -- drop the per-container cap while flattening (transparency must not
                          become unbounded breadth)

Same contract as tools/ablate_prune.py: real gradle runs, named-test expectations, file restored
even on failure.
"""
import pathlib
import tempfile
import re
import shutil
import subprocess

# Derived, not hardcoded: a literal path is only valid on the machine that wrote it, and CI failed
# with FileNotFoundError on exactly that mistake. Same form ablate_search.py already uses.
REPO = pathlib.Path(__file__).resolve().parent.parent
SRC = REPO / "app/src/main/java/com/jiesa/xvideocatcher/hook/TweetSearch.kt"
# A fixed /tmp name collides when two ablation scripts run at once and survives a crash.
BACKUP = pathlib.Path(tempfile.mkdtemp(prefix="ablate-")) / "TweetSearch.kt"


def gradle_failures() -> set:
    r = subprocess.run(
        ["gradle", "--offline", "testDebugUnitTest", "--rerun-tasks"],
        cwd=REPO, capture_output=True, text=True, timeout=1800,
    )
    out = r.stdout + r.stderr
    if "BUILD SUCCESSFUL" in out:
        return set()
    names = set(re.findall(r"^\s*\S+ > (.+?) FAILED\s*$", out, re.M))
    if not names and "BUILD FAILED" in out:
        raise SystemExit(
            "gradle failed without per-test names:\n"
            + "\n".join(out.strip().splitlines()[-25:])
        )
    return names


ABLATIONS = []


def ablation(name, expect):
    def deco(fn):
        ABLATIONS.append((name, fn, expect))
        return fn
    return deco


@ablation("opaque-container", "reaches a tweet four object hops down a list chain")
def _opaque(s):
    """Enqueue the container itself again: every list hop costs a level."""
    old = """        if (isContainer(node)) {
            flattenContainer(node, "", out, 0)
            return out
        }"""
    assert s.count(old) == 1, "container-node anchor missing"
    s = s.replace(old, """        if (isContainer(node)) {
            flattenContainer(node, "", out, 0)
            return out
        }
        // ablation: no-op marker""")
    # The real regression: field containers become nodes rather than being flattened.
    old_field = """                if (isContainer(v)) flattenContainer(v, f.name, out, 0) else out.add(f.name to v)"""
    assert s.count(old_field) == 1, "field-flatten anchor missing"
    return s.replace(old_field, """                out.add(f.name to v)""")


@ablation("no-field-flatten", "reaches a tweet at the full model depth")
def _no_field_flatten(s):
    """Flatten containers only as nodes, never as field values -- the plausible half-fix."""
    old_field = """                if (isContainer(v)) flattenContainer(v, f.name, out, 0) else out.add(f.name to v)"""
    assert s.count(old_field) == 1
    return s.replace(old_field, """                out.add(f.name to v)""")


@ablation("no-nested", "flattens nested containers into a single level")
def _no_nested(s):
    """Stop collapsing nested containers: they get enqueued instead, costing an extra level."""
    old = """            when {
                // Collapse a nested container into this level, up to the bound.
                isContainer(v) && nesting < MAX_CONTAINER_NESTING ->
                    flattenContainer(v, label, out, nesting + 1)
                // Past the bound, hand it to the walk rather than discarding it.
                else -> out.add(label to v)
            }"""
    assert s.count(old) == 1, "nested-flatten anchor missing"
    return s.replace(old, """            out.add(label to v)""")


@ablation("drop-over-nested", "nesting deeper than the collapse bound is still reachable")
def _drop_over_nested(s):
    """Reintroduce the regression: discard containers past the nesting bound."""
    old = """                // Past the bound, hand it to the walk rather than discarding it.
                else -> out.add(label to v)"""
    assert s.count(old) == 1, "over-nested fallback anchor missing"
    return s.replace(
        old,
        """                isContainer(v) -> Unit // ablation: drop it
                else -> out.add(label to v)""",
    )


@ablation("unbounded-fanout", "still respects the per-container element cap")
def _unbounded(s):
    """Remove the element cap during flattening."""
    old = """        values.take(MAX_FANOUT).forEachIndexed { i, v ->"""
    assert s.count(old) == 1, "fanout-cap anchor missing"
    return s.replace(old, """        values.forEachIndexed { i, v ->""")


def main():
    original = SRC.read_text()
    shutil.copy(SRC, BACKUP)

    print("baseline ...", flush=True)
    base = gradle_failures()
    if base:
        SRC.write_text(original)
        raise SystemExit("baseline NOT green, refusing to ablate: %s" % sorted(base))
    print("baseline green\n", flush=True)

    results = []
    try:
        for name, mutate, expect in ABLATIONS:
            SRC.write_text(mutate(original))
            failures = gradle_failures()
            SRC.write_text(original)

            hit = expect in failures
            results.append((name, hit))
            print("%-18s %s" % (name, "LOAD-BEARING" if hit else "*** NOT LOAD-BEARING ***"))
            print("    expected red: %s" % expect)
            print("    actually red: %s\n" % (sorted(failures) or "NOTHING"), flush=True)
    finally:
        SRC.write_text(original)
        assert SRC.read_text() == original, "failed to restore TweetSearch.kt"

    print("=" * 60)
    for name, hit in results:
        print("%-18s %s" % (name, "ok" if hit else "FAIL"))
    bad = [r for r in results if not r[1]]
    if bad:
        raise SystemExit("\n%d ablation(s) did not make the intended test fail" % len(bad))
    print("\nall %d axes load-bearing" % len(results))


if __name__ == "__main__":
    main()
