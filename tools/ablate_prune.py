#!/usr/bin/env python3
"""Ablate the DI prune: prove InjectionPruneTest is load-bearing, axis by axis.

Green tests are not evidence. Each ablation below reintroduces one specific bug and must turn a
*named* test red. An ablation that stays green means that test is decorative.

Axes, each independent:
  1. no-prune        -- remove the refusal entirely (the actual 1.8.0 device bug)
  2. no-count        -- prune but do not report it (silent pruning is unfalsifiable)
  3. skip-visit      -- do not count a refused node as visited (under-reports the walk)
  4. overbroad       -- match on class name "Provider"/"Factory" instead of DI packages
                        (cuts host paths; must break the host-graph test)
  5. enter-if-tweet  -- allow descending into a wrapper that holds a tweet

Run inside the android-builder container against a real gradle run. Restores the file afterwards
even on failure, so a crash cannot leave the tree ablated.
"""
import pathlib
import re
import shutil
import subprocess
import sys

REPO = pathlib.Path("/workspace/tmp/xvc-standalone")
SRC = REPO / "app/src/main/java/com/jiesa/xvideocatcher/hook/TweetSearch.kt"
BACKUP = pathlib.Path("/tmp/TweetSearch.kt.ablate-backup")

PRUNE_BLOCK = """                if (isInjectionPlumbing(node.javaClass)) {
                    val p = packagePrefix(node.javaClass.name)
                    pruned[p] = (pruned[p] ?: 0) + 1
                    continue
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
    assert s.count(PRUNE_BLOCK) == 1
    return s.replace(PRUNE_BLOCK, "")


@ablation("no-count", "reports which plumbing packages it refused")
def _no_count(s):
    """Prune, but report nothing -- silent pruning."""
    silent = """                if (isInjectionPlumbing(node.javaClass)) {
                    continue
                }"""
    assert s.count(PRUNE_BLOCK) == 1
    return s.replace(PRUNE_BLOCK, silent)


@ablation("skip-visit", "a pruned wrapper still counts as visited")
def _skip_visit(s):
    """Move the refusal ahead of the visit counter so refused nodes vanish from `visits`."""
    old = """                if (++visits > MAX_VISITS) {"""
    assert s.count(old) == 1, "visit-counter anchor missing"
    # Refuse before counting: the node is never tallied.
    early = """                if (isInjectionPlumbing(node.javaClass)) continue
                if (++visits > MAX_VISITS) {"""
    s = s.replace(old, early)
    assert s.count(PRUNE_BLOCK) == 1
    return s.replace(PRUNE_BLOCK, "")


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
    relaxed = """                if (isInjectionPlumbing(node.javaClass)) {
                    val p = packagePrefix(node.javaClass.name)
                    pruned[p] = (pruned[p] ?: 0) + 1
                    val holdsTweet = childrenOf(node).any {
                        HostResolver.isTweetModel(it.second.javaClass)
                    }
                    if (!holdsTweet) continue
                }"""
    assert s.count(PRUNE_BLOCK) == 1
    return s.replace(PRUNE_BLOCK, relaxed)


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
