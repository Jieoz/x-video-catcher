#!/usr/bin/env python3
"""Ablate the DI prune: prove InjectionPruneTest is load-bearing, axis by axis.

Green tests are not evidence. Each ablation below reintroduces one specific bug and must turn a
*named* test red. An ablation that stays green means that test is decorative.

Axes, each independent:
  1. no-prune        -- remove the refusal entirely (the actual 1.8.0 device bug)
  2. no-count        -- prune but do not report it (silent pruning is unfalsifiable)
  3. charge-visit    -- refuse at dequeue, spending a visit per refusal (the 1.9.0 waste:
                        1122 of 4001 visits on the 20260804 log)
  4. no-root-prune   -- guard children but not roots (regression seen this release: 586
                        visits where 1 was expected)
  5. overbroad       -- match on class name "Provider"/"Factory" instead of DI packages
                        (cuts host paths; must break the host-graph test)
  6. enter-if-tweet  -- allow descending into a wrapper that holds a tweet

Run inside the android-builder container against a real gradle run. Restores the file afterwards
even on failure, so a crash cannot leave the tree ablated.
"""
import pathlib
import tempfile
import re
import shutil
import subprocess
import sys

# Derived, not hardcoded: a literal path is only valid on the machine that wrote it, and CI failed
# with FileNotFoundError on exactly that mistake. Same form ablate_search.py already uses.
REPO = pathlib.Path(__file__).resolve().parent.parent
SRC = REPO / "app/src/main/java/com/jiesa/xvideocatcher/hook/TweetSearch.kt"
# A fixed /tmp name collides when two ablation scripts run at once and survives a crash.
BACKUP = pathlib.Path(tempfile.mkdtemp(prefix="ablate-")) / "TweetSearch.kt"

# The child-side refusal, copied verbatim from the source. Literal on purpose: an anchor derived
# from the file it checks can never drift, and therefore can never report that the traversal was
# rewritten underneath it.
PRUNE_BLOCK = """                for ((label, child) in childrenOf(node)) {
                    if (isInjectionPlumbing(child.javaClass)) {
                        val p = packagePrefix(child.javaClass.name)
                        pruned[p] = (pruned[p] ?: 0) + 1
                        continue
                    }
                    next.add("$path.$label" to child)
                }"""

# The walk with no refusal at all: children enqueued unconditionally.
NO_PRUNE = """                for ((label, child) in childrenOf(node)) {
                    next.add("$path.$label" to child)
                }"""

# The root-side refusal, which is a separate entry point into the same rule.
ROOT_BLOCK = """        var frontier = roots.mapNotNull { (name, value) ->
            value?.let {
                if (isInjectionPlumbing(it.javaClass)) {
                    val p = packagePrefix(it.javaClass.name)
                    pruned[p] = (pruned[p] ?: 0) + 1
                    null
                } else {
                    name to it
                }
            }
        }"""


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
        # Compile error or similar: surface it rather than reading it as "no failures".
        tail = "\n".join(out.strip().splitlines()[-25:])
        raise SystemExit("gradle failed without per-test names:\n" + tail)
    return names


ABLATIONS = []


def ablation(name, expect):
    def deco(fn):
        ABLATIONS.append((name, fn, expect))
        return fn
    return deco


@ablation("no-prune", "refusing plumbing keeps its subtree out of the walk")
def _no_prune(s):
    """The exact 1.8.0 bug: DI graph walked, budget consumed."""
    assert s.count(PRUNE_BLOCK) == 1, "child prune anchor missing"
    return s.replace(PRUNE_BLOCK, NO_PRUNE)


@ablation("no-count", "reports which plumbing packages it refused")
def _no_count(s):
    """Prune, but report nothing -- silent pruning."""
    silent = """                for ((label, child) in childrenOf(node)) {
                    if (isInjectionPlumbing(child.javaClass)) continue
                    next.add("$path.$label" to child)
                }"""
    assert s.count(PRUNE_BLOCK) == 1, "child prune anchor missing"
    return s.replace(PRUNE_BLOCK, silent)


@ablation("charge-visit", "a pruned wrapper costs no visit but is still reported")
def _charge_visit(s):
    """Reintroduce dequeue-time refusal, which spends a visit on every object it declines.

    This was the shipping behaviour through 1.9.0 and cost 1122 of 4001 visits on the 20260804
    device log. The axis is inverted from the previous release deliberately: the test now pins
    that a refusal is free, and this ablation makes it expensive again.
    """
    assert s.count(PRUNE_BLOCK) == 1, "child prune anchor missing"
    s = s.replace(PRUNE_BLOCK, NO_PRUNE)

    # The root guard has to go too. Leaving it in place made this ablation a no-op: the test's
    # wrapper is a root, so it was refused before the frontier existed and the dequeue branch
    # installed below was never reached. A green ablation meant a fake ablation, not a fake test.
    assert s.count(ROOT_BLOCK) == 1, "root prune anchor missing"
    s = s.replace(
        ROOT_BLOCK,
        """        var frontier = roots.mapNotNull { (name, value) -> value?.let { name to it } }""",
    )

    old = """                if (depth == maxDepth) continue"""
    assert s.count(old) == 1, "depth cutoff anchor missing"
    dequeue = """                if (depth == maxDepth) continue

                if (isInjectionPlumbing(node.javaClass)) {
                    val p = packagePrefix(node.javaClass.name)
                    pruned[p] = (pruned[p] ?: 0) + 1
                    continue
                }"""
    return s.replace(old, dequeue)


@ablation("no-root-prune", "a wrapper passed in as a root is refused")
def _no_root_prune(s):
    """Guard children but not roots -- the regression this release actually shipped mid-work.

    Pruning at enqueue left roots unguarded, so a caller handing in a DI wrapper had its whole
    subtree walked: 586 visits where 1 was expected. Trivial to reintroduce, which is why it
    needs an axis.
    """
    assert s.count(ROOT_BLOCK) == 1, "root prune anchor missing"
    unguarded = """        var frontier = roots.mapNotNull { (name, value) -> value?.let { name to it } }"""
    return s.replace(ROOT_BLOCK, unguarded)


@ablation("overbroad", "pruning does not fire on host or framework classes")
def _overbroad(s):
    """Match on names R8 also produces for host classes."""
    old = '''        return n.startsWith("dagger.") || n.startsWith("javax.inject.")'''
    assert s.count(old) == 1
    return s.replace(
        old,
        '''        return n.contains("Provider") || n.contains("Factory") || n.contains("Expensive")''',
    )


@ablation("enter-if-tweet", "a tweet held directly by a wrapper is still not entered")
def _enter_if_tweet(s):
    """Carve an exception for wrappers holding a tweet -- reopens the subtree."""
    relaxed = """                for ((label, child) in childrenOf(node)) {
                    if (isInjectionPlumbing(child.javaClass)) {
                        val p = packagePrefix(child.javaClass.name)
                        pruned[p] = (pruned[p] ?: 0) + 1
                        val holdsTweet = childrenOf(child).any {
                            HostResolver.isTweetModel(it.second.javaClass)
                        }
                        if (!holdsTweet) continue
                    }
                    next.add("$path.$label" to child)
                }"""
    assert s.count(PRUNE_BLOCK) == 1, "child prune anchor missing"
    s = s.replace(PRUNE_BLOCK, relaxed)
    # A root wrapper must be admitted too, or the root guard alone keeps the test green and this
    # ablation proves nothing about the child path.
    assert s.count(ROOT_BLOCK) == 1, "root prune anchor missing"
    return s.replace(
        ROOT_BLOCK,
        """        var frontier = roots.mapNotNull { (name, value) -> value?.let { name to it } }""",
    )


def main():
    original = SRC.read_text()
    shutil.copy(SRC, BACKUP)

    # Baseline must be green, or every result below is meaningless.
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
            print("%-16s %s" % (name, "LOAD-BEARING" if hit else "*** NOT LOAD-BEARING ***"))
            print("    expected red: %s" % expect)
            print("    actually red: %s\n" % (sorted(failures) or "NOTHING"), flush=True)
    finally:
        SRC.write_text(original)
        assert SRC.read_text() == original, "failed to restore TweetSearch.kt"

    print("=" * 60)
    bad = [r for r in results if not r[2]]
    for name, expect, hit, failures in results:
        print("%-16s %s" % (name, "ok" if hit else "FAIL"))
    if bad:
        raise SystemExit("\n%d ablation(s) did not make the intended test fail" % len(bad))
    print("\nall %d axes load-bearing" % len(results))


if __name__ == "__main__":
    main()
