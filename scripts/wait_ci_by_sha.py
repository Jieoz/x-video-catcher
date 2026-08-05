#!/usr/bin/env python3
"""Block until the CI run for a given commit finishes, then exit non-zero unless it passed.

Why this exists: `docker exec ... python3 scripts/wait_ci_by_sha.py <sha>` returned exit 0 while
python was printing `No such file or directory`, because the exit status of the pipeline came from
the shell, not from the missing script. A wait-for-CI helper that cannot fail is worse than none:
it reports green for a run it never looked at.

So every exit here is explicit, and a skipped-but-required check is not treated as success.

Usage:  wait_ci_by_sha.py <sha> [--repo owner/name] [--timeout-seconds N]

Auth comes from GITHUB_PAT or GITHUB_TOKEN in the environment. Any non-200 from the API is a
failure, never a retry-forever, so a revoked token surfaces as an error rather than a hang.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

DEFAULT_REPO = "Jieoz/x-video-catcher"
POLL_SECONDS = 20

# Conclusions that mean "this run did not pass". `None` while in progress is handled separately.
BAD = {"failure", "cancelled", "timed_out", "action_required", "startup_failure", "stale"}


def api(path, token):
    """GET a JSON path off the GitHub API. Non-200 raises -- never silently returns empty."""
    req = urllib.request.Request(
        "https://api.github.com" + path,
        headers={"Authorization": "Bearer " + token,
                 "Accept": "application/vnd.github+json",
                 "User-Agent": "wait-ci-by-sha"},
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            if resp.status != 200:
                raise SystemExit("API returned HTTP %d for %s" % (resp.status, path))
            return json.load(resp)
    except urllib.error.HTTPError as exc:
        raise SystemExit("API returned HTTP %d for %s: %s"
                         % (exc.code, path, exc.read()[:200].decode("utf-8", "replace")))
    except urllib.error.URLError as exc:
        raise SystemExit("API unreachable for %s: %s" % (path, exc.reason))


def main():
    args = [a for a in sys.argv[1:]]
    if not args or args[0].startswith("-"):
        raise SystemExit(__doc__.strip().split("Usage:")[1].strip().splitlines()[0])
    sha = args[0]

    repo = DEFAULT_REPO
    timeout = 2400
    for i, a in enumerate(args):
        if a == "--repo" and i + 1 < len(args):
            repo = args[i + 1]
        if a == "--timeout-seconds" and i + 1 < len(args):
            timeout = int(args[i + 1])

    token = os.environ.get("GITHUB_PAT") or os.environ.get("GITHUB_TOKEN")
    if not token:
        raise SystemExit("no GITHUB_PAT or GITHUB_TOKEN in environment")

    deadline = time.time() + timeout
    run = None
    while time.time() < deadline:
        runs = api("/repos/%s/actions/runs?head_sha=%s" % (repo, sha), token)["workflow_runs"]
        if not runs:
            print("no run for %s yet, waiting" % sha[:12], flush=True)
        else:
            run = max(runs, key=lambda r: r["id"])
            if run["status"] == "completed":
                break
            print("run %s: %s" % (run["id"], run["status"]), flush=True)
        time.sleep(POLL_SECONDS)
    else:
        raise SystemExit("timed out after %ds waiting for CI on %s" % (timeout, sha[:12]))

    concl = run["conclusion"]
    print("\nrun %s -> %s" % (run["id"], concl), flush=True)

    jobs = api("/repos/%s/actions/runs/%s/jobs" % (repo, run["id"]), token)["jobs"]
    failed = []
    for job in jobs:
        for step in job["steps"]:
            if step["conclusion"] in BAD:
                failed.append("%s / %s -> %s" % (job["name"], step["name"], step["conclusion"]))
    for line in failed:
        print("  FAILED %s" % line)

    if concl != "success" or failed:
        raise SystemExit("CI did not pass for %s" % sha[:12])
    print("CI passed for %s" % sha[:12])
    return 0


if __name__ == "__main__":
    sys.exit(main())
