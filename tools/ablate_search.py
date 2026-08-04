#!/usr/bin/env python3
"""Prove the tweet-search gates can fail, one axis at a time.

Run from the repo root. Exit 0 only when every axis is load-bearing, the axes are independent, and the
control run is green.

## Why this lives in tools/ and runs in CI

The previous version of this script only ever existed in `/tmp` and was run by hand once. Both of its
structural anchors then rotted -- `Outcome` gained a parameter and the tweet predicate was rewritten --
and nothing noticed for two releases, because an ablation nobody runs is indistinguishable from one
that passes. A gate-checking tool that is not itself gated is decoration.

## Anchor-not-found is a failure, not a pass

Each ablation asserts its anchor appears exactly once before editing. A missing anchor means the
ablation edited nothing, so a green suite proves nothing -- reporting that as success is how a fake
ablation launders itself into evidence. This distinction was learned the hard way: an earlier
"depth-first" ablation reordered elements *within* a BFS frontier, which cannot change visit order at
all, and its green result was nearly read as "the ordering test is vacuous".
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TS = os.path.join(REPO, "app/src/main/java/com/jiesa/xvideocatcher/hook/TweetSearch.kt")
RESULTS = os.path.join(REPO, "app/build/test-results/testDebugUnitTest")

# Scoped to the classes that own these behaviours, so a failure elsewhere cannot be mistaken for an
# axis breaking -- and so one axis leaking into another shows up as a different failing class.
GRADLE = [
    "timeout", "900", "gradle", "--offline", "testDebugUnitTest", "--rerun-tasks",
    "--tests", "*TweetSearchTest*", "--tests", "*CriterionTest*", "--tests", "*CensusGuardTest*",
]

FINAL_RETURN = """        return Outcome(
            found, visits, exhausted = false,
            census = packageCensus(census), pruned = packageCensus(pruned),
        )"""

OLD_TRAVERSAL = """        var frontier = roots.mapNotNull { (name, value) ->
            value?.let {
                if (isInjectionPlumbing(it.javaClass)) {
                    val p = packagePrefix(it.javaClass.name)
                    pruned[p] = (pruned[p] ?: 0) + 1
                    null
                } else {
                    name to it
                }
            }
        }
        var depth = 0

        while (frontier.isNotEmpty() && depth <= maxDepth) {
            val next = mutableListOf<Pair<String, Any>>()
            for ((path, node) in frontier) {
                if (!seen.add(node)) continue
                if (++visits > maxVisits) {
                    return Outcome(
                        found, visits, exhausted = true,
                        census = packageCensus(census), pruned = packageCensus(pruned),
                    )
                }
                val prefix = packagePrefix(node.javaClass.name)
                census[prefix] = (census[prefix] ?: 0) + 1

                if (HostResolver.isTweetModel(node.javaClass)) {
                    found.add(Candidate(node, path, depth))
                    if (found.size >= MAX_CANDIDATES)
                        return Outcome(
                            found, visits, false,
                            packageCensus(census), packageCensus(pruned),
                        )
                    // Do not descend into a candidate: its own fields are the tweet's internals,
                    // and a quoted tweet hanging off it is a different tweet, reported separately
                    // if it is reachable another way.
                    continue
                }

                if (depth == maxDepth) continue

                // A DI provider is a hub to the entire application singleton graph. Walking one
                // costs hundreds of visits and cannot pay out, because a tweet is request state,
                // never an injected singleton.
                //
                // Refused here, as a child, rather than on arrival: charging a visit for an object
                // we have already decided not to walk spent 28% of the budget on 20260804, where
                // `census dagger.internal.d` and `pruned dagger.internal.d` were both 967. Counted
                // in `pruned` either way, so a prune that matches nothing is still visible.
                for ((label, child) in childrenOf(node)) {
                    if (isInjectionPlumbing(child.javaClass)) {
                        val p = packagePrefix(child.javaClass.name)
                        pruned[p] = (pruned[p] ?: 0) + 1
                        continue
                    }
                    next.add("$path.$label" to child)
                }
            }
            frontier = next
            depth++
        }"""

DFS_TRAVERSAL = """        // ablated: depth-first via an explicit stack. Permuting a level's internal order -- which
        // two earlier attempts at this ablation did -- leaves the level-by-level structure intact
        // and cannot change which depth is reached first. Only replacing the structure does.
        val stack = ArrayDeque(
            roots.mapNotNull { (name, value) -> value?.let { Triple(name, it, 0) } }.reversed()
        )
        while (stack.isNotEmpty()) {
            val (path, node, depth) = stack.removeLast()
            if (!seen.add(node)) continue
            if (++visits > MAX_VISITS) {
                return Outcome(
                    found, visits, exhausted = true,
                    census = packageCensus(census), pruned = packageCensus(pruned),
                )
            }
            val prefix = packagePrefix(node.javaClass.name)
            census[prefix] = (census[prefix] ?: 0) + 1

            if (HostResolver.isTweetModel(node.javaClass)) {
                found.add(Candidate(node, path, depth))
                if (found.size >= MAX_CANDIDATES)
                    return Outcome(
                        found, visits, false,
                        packageCensus(census), packageCensus(pruned),
                    )
                continue
            }

            if (depth >= MAX_DEPTH) continue

            // Kept identical to the real walk: this axis isolates traversal order, so pruning must
            // not also change between the two versions.
            if (isInjectionPlumbing(node.javaClass)) {
                val p = packagePrefix(node.javaClass.name)
                pruned[p] = (pruned[p] ?: 0) + 1
                continue
            }

            for ((label, child) in childrenOf(node).reversed()) {
                stack.addLast(Triple("$path.$label", child, depth + 1))
            }
        }"""

ABLATIONS = [
    # Traversal axes only. Predicate axes live in ablate_criterion.py: `structural predicate` and
    # `media shape requires a real field` used to sit here and mutated HostResolver, which meant
    # every rewrite of the predicate drifted this file's anchors -- twice in consecutive releases.
    # One authoritative suite per subject.
    (
        "depth-first traversal (structural)",
        "nearest-first ordering is what picks the shared tweet over a quoted one",
        TS,
        OLD_TRAVERSAL,
        DFS_TRAVERSAL,
    ),
    (
        "visit budget",
        "an unbounded walk would freeze the host UI on a tap",
        TS,
        "                if (++visits > maxVisits) {",
        "                if (++visits > Int.MAX_VALUE) {",
    ),
    (
        "census populated",
        "an empty census on a failed walk is the case it exists for",
        TS,
        FINAL_RETURN,
        FINAL_RETURN.replace("census = packageCensus(census)", "census = emptyList()"),
    ),
]


def run_suite():
    subprocess.run(GRADLE, cwd=REPO, capture_output=True, text=True, timeout=1200)
    if not os.path.isdir(RESULTS):
        sys.exit("no test-results dir: the build never ran tests")
    failed, total, seen = set(), 0, False
    for name in os.listdir(RESULTS):
        if not (name.startswith("TEST-") and name.endswith(".xml")):
            continue
        seen = True
        head = open(os.path.join(RESULTS, name), encoding="utf-8").read(4000)
        t = re.search(r'tests="(\d+)"', head)
        f = re.search(r'failures="(\d+)"', head)
        e = re.search(r'errors="(\d+)"', head)
        total += int(t.group(1)) if t else 0
        bad = (int(f.group(1)) if f else 0) + (int(e.group(1)) if e else 0)
        if bad:
            m = re.search(r'name="([^"]+)"', head)
            failed.add((m.group(1) if m else name).split(".")[-1])
    if not seen:
        sys.exit("no TEST-*.xml: cannot tell a pass from a suite that never ran")
    return failed, total


def main():
    backups = tempfile.mkdtemp(prefix="ablate-search-")
    # Derived from the axes, not hand-listed: an axis added later has its file protected without
    # anyone remembering to extend a second list. A stale backup set fails silently, which is worse
    # than a stale anchor -- an anchor announces itself by not matching.
    targets = {path for _, _, path, _, _ in ABLATIONS}
    for path in targets:
        shutil.copy2(path, os.path.join(backups, os.path.basename(path)))

    def restore():
        for p in targets:
            shutil.copy2(os.path.join(backups, os.path.basename(p)), p)

    base_failed, base_total = run_suite()
    print("baseline: {} tests, {} failing classes".format(base_total, len(base_failed)))
    if base_failed or base_total == 0:
        restore()
        sys.exit("baseline not green -- ablation results would be meaningless")

    problems, results = [], {}
    for name, why, path, old, new in ABLATIONS:
        src = open(path, encoding="utf-8").read()
        n = src.count(old)
        if n != 1:
            # Not a pass. The edit did not happen, so the suite result says nothing.
            problems.append("{}: anchor appears {} times, expected 1 -- ablation invalid".format(name, n))
            print("\n!!! {}: anchor not found ({} matches) -- ablation invalid, not a pass"
                  .format(name, n))
            continue

        open(path, "w", encoding="utf-8").write(src.replace(old, new))
        failed, total = run_suite()
        restore()

        print("\naxis: {}\n  {}".format(name, why))
        print("  -> {} failing class(es): {}".format(len(failed), sorted(failed) or "none"))
        results[name] = frozenset(failed)
        if not failed:
            problems.append("{}: suite stayed green -- not load-bearing".format(name))

    ctrl_failed, ctrl_total = run_suite()
    print("\ncontrol after restore: {} tests, {} failing".format(ctrl_total, len(ctrl_failed)))
    if ctrl_failed:
        problems.append("control red after restore: {}".format(sorted(ctrl_failed)))

    sets = [s for s in results.values() if s]
    if len(sets) > 1 and len(set(sets)) == 1:
        problems.append("every ablation broke the same classes -- axes are not independent")

    print()
    if problems:
        for p in problems:
            print("FAIL:", p)
        return 1
    print("all {} axes load-bearing, independent, control green".format(len(results)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
