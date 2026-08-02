#!/usr/bin/env python3
"""Check that a real X APK still has the shapes HostResolver hooks.

The unit tests run against hand-written fixtures. They prove the *predicates* are right and say
nothing about whether they still match the shipped app — versions 1.1 through 1.3 all passed their
tests and did nothing on the device. This applies the same predicates to a real host APK.

What is asserted, mirroring HostResolver:

  1. click contract  -- the interface on BaseDialogFragment with the 5-method contract shape,
                        and the ``void(int)`` on it that a tap is dispatched through
  2. bind points     -- ``void(X, contract)`` where X declares exactly one ``java.util.List``.
                        BOTH declaring classes must appear: the share panel overrides the base
                        ViewHolder without calling super, so hooking one covers only one surface.
                        This is precisely the 1.3.0 bug.
  3. sheet model     -- the X above, and the List field an entry gets appended to
  4. sheet link      -- a constructor taking both a sheet model and a shareable, which is what
                        associates a panel with its tweet without timing guesswork
  5. tweet field     -- a ``com.twitter.model.core.*`` field somewhere on the shareable's chain

Exits non-zero when the host stops matching, so this can gate a release rather than produce a wall
of output nobody reads. ``--self-test`` proves it can fail.

Usage:
  verify_host_anchors.py <host.apk>
  verify_host_anchors.py --self-test
"""
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])

from dexdefs import load_classes  # noqa: E402

CONTRACT_SHAPE_METHODS = 5
LIST_TYPE = "Ljava/util/List;"
DIALOG_FRAGMENT = "Lcom/twitter/app/common/dialog/BaseDialogFragment;"
SHARE_PREFIX = "Lcom/twitter/share/"
TWEET_PREFIX = "Lcom/twitter/model/core/"

BIND_PACKAGES = (
    "Lcom/twitter/ui/dialog/actionsheet/",
    "Lcom/twitter/app/share/ui/",
    "Lcom/twitter/subsystems/nudges/engagements/",
)
LINK_PACKAGES = (
    "Lcom/twitter/menu/share/full/providers/",
    "Lcom/twitter/menu/share/half/",
)

# Verified on com.twitter.android 12.13.0-release.0 (versionCode 312130000).
EXPECT_BINDER_COUNT = 2
EXPECT_DISPATCH_PARAM = "I"


def analyse(classes):
    """Returns (results, failures). Pure, so the self-test can drive it with fixtures."""
    results = {}
    failures = []

    frag = classes.get(DIALOG_FRAGMENT)
    if frag is None:
        return results, ["%s not present" % DIALOG_FRAGMENT]

    contract = dispatch = None
    for iname in frag["interfaces"]:
        i = classes.get(iname)
        if i is None or i["fields"] or len(i["methods"]) != CONTRACT_SHAPE_METHODS:
            continue
        ms = i["methods"]
        vi = [m for m in ms if m["ret"] == "V" and m["params"] == [EXPECT_DISPATCH_PARAM]]
        vb = [m for m in ms if m["ret"] == "V" and m["params"] == ["Z"]]
        vn = [m for m in ms if m["ret"] == "V" and not m["params"]]
        nr = [m["ret"] for m in ms if not m["params"] and m["ret"] != "V"]
        if len(vi) == 1 and len(vb) == 1 and len(vn) == 1 and len(nr) == 2 and len(set(nr)) == 1:
            contract, dispatch = iname, vi[0]["name"]
    if contract is None:
        return results, ["no interface on the dialog fragment matched the contract shape"]
    results["contract"] = contract
    results["dispatch"] = dispatch

    binders = []
    for cname, cls in classes.items():
        if not cname.startswith(BIND_PACKAGES):
            continue
        for m in cls["methods"]:
            if m["ret"] != "V" or m["static"] or len(m["params"]) != 2:
                continue
            if m["params"][1] != contract:
                continue
            sheet = classes.get(m["params"][0])
            if sheet is None:
                continue
            lists = [f for f in sheet["fields"] if f["type"] == LIST_TYPE and not f["static"]]
            if len(lists) != 1:
                continue
            binders.append((cname, m["name"], m["params"][0], lists[0]["name"]))
    results["binders"] = sorted(binders)

    owners = sorted({b[0] for b in binders})
    results["binder_owners"] = owners
    if len(owners) < EXPECT_BINDER_COUNT:
        failures.append(
            "found %d binder class(es) %s, expected at least %d — the share panel overrides the "
            "base ViewHolder, so a single hook leaves one surface inert"
            % (len(owners), owners, EXPECT_BINDER_COUNT)
        )

    sheet_models = sorted({b[2] for b in binders})
    results["sheet_models"] = sheet_models
    if len(sheet_models) != 1:
        failures.append("expected exactly one sheet model, got %s" % sheet_models)

    links = []
    for cname, cls in classes.items():
        if not cname.startswith(LINK_PACKAGES):
            continue
        for m in cls["methods"]:
            if m["name"] != "<init>" or len(m["params"]) < 2:
                continue
            if not any(p in sheet_models for p in m["params"]):
                continue
            shareables = [p for p in m["params"] if p and p.startswith(SHARE_PREFIX)]
            if not shareables:
                continue
            links.append((cname, shareables))
    results["links"] = sorted(links)
    if not links:
        failures.append("no constructor pairs a sheet model with a shareable")

    tweets = {}
    for _cname, shareables in links:
        for sh in shareables:
            chain = [sh] + [n for n, c in classes.items() if c["super"] == sh]
            for c in chain:
                cls = classes.get(c)
                if cls is None:
                    continue
                hits = [(f["name"], f["type"]) for f in cls["fields"]
                        if f["type"] and f["type"].startswith(TWEET_PREFIX) and not f["static"]]
                if hits:
                    tweets[c] = hits
    results["tweets"] = tweets
    if not tweets:
        failures.append("no tweet field on any shareable in the chain")

    return results, failures


def report(results, failures):
    if "contract" in results:
        print("[contract] %s   dispatch = %s(int)"
              % (results["contract"], results.get("dispatch")))
    for cname, mname, sheet, lf in results.get("binders", []):
        print("[bind]     %s.%s" % (cname, mname))
        print("           sheet model = %s   item list field = %s" % (sheet, lf))
    for cname, sh in results.get("links", []):
        print("[link]     %s.<init>   shareable(s) = %s" % (cname, sh))
    for c, hits in sorted(results.get("tweets", {}).items()):
        print("[tweet]    %s -> %s" % (c, hits))
    print()
    if failures:
        for f in failures:
            print("FAIL: %s" % f)
        return 1
    print("all anchors match this host build")
    return 0


def _fixture():
    """A minimal host shaped like the real one, for the self-test."""
    def m(name, ret, params, static=False):
        return {"name": name, "ret": ret, "params": params, "static": static}

    def f(name, type_, static=False):
        return {"name": name, "type": type_, "static": static}

    contract = "Lcom/twitter/app/common/dialog/o;"
    sheet = "Lcom/twitter/ui/dialog/actionsheet/h;"
    shareable = "Lcom/twitter/share/api/e;"
    sub = "Lcom/twitter/share/api/m;"
    return {
        DIALOG_FRAGMENT: {
            "name": DIALOG_FRAGMENT, "super": None, "interfaces": [contract],
            "fields": [], "methods": [m("u", "V", ["I"])],
        },
        contract: {
            "name": contract, "super": None, "interfaces": [], "fields": [],
            "methods": [
                m("u", "V", ["I"]), m("T", "V", ["Z"]), m("D0", "V", []),
                m("Y", "Lio/reactivex/b;", []), m("h", "Lio/reactivex/b;", []),
            ],
        },
        sheet: {
            "name": sheet, "super": None, "interfaces": [],
            "fields": [f("g", LIST_TYPE), f("h", "I")], "methods": [],
        },
        "Lcom/twitter/ui/dialog/actionsheet/f;": {
            "name": "Lcom/twitter/ui/dialog/actionsheet/f;", "super": None, "interfaces": [],
            "fields": [], "methods": [m("n0", "V", [sheet, contract])],
        },
        "Lcom/twitter/app/share/ui/d;": {
            "name": "Lcom/twitter/app/share/ui/d;", "super": None, "interfaces": [],
            "fields": [], "methods": [m("n0", "V", [sheet, contract])],
        },
        "Lcom/twitter/menu/share/full/providers/l;": {
            "name": "Lcom/twitter/menu/share/full/providers/l;", "super": None,
            "interfaces": [], "fields": [],
            "methods": [m("<init>", "V", [shareable, sheet])],
        },
        shareable: {
            "name": shareable, "super": None, "interfaces": [], "fields": [], "methods": [],
        },
        sub: {
            "name": sub, "super": shareable, "interfaces": [],
            "fields": [f("b", "Lcom/twitter/model/core/e;")], "methods": [],
        },
    }


def self_test():
    """Every check must be able to fail; a gate that cannot fail proves nothing."""
    good = _fixture()
    _r, failures = analyse(good)
    if failures:
        print("SELF-TEST FAIL: healthy fixture reported %s" % failures)
        return 1

    cases = []

    # one binder only: the 1.3.0 bug
    c = {k: dict(v) for k, v in good.items()}
    del c["Lcom/twitter/app/share/ui/d;"]
    cases.append(("single binder", c))

    # sheet model with no list: nothing to append an entry to
    c = {k: dict(v) for k, v in good.items()}
    c["Lcom/twitter/ui/dialog/actionsheet/h;"] = dict(
        c["Lcom/twitter/ui/dialog/actionsheet/h;"], fields=[{"name": "h", "type": "I", "static": False}]
    )
    cases.append(("sheet model has no list", c))

    # no link ctor: panel cannot be associated with a tweet
    c = {k: dict(v) for k, v in good.items()}
    del c["Lcom/twitter/menu/share/full/providers/l;"]
    cases.append(("no sheet link", c))

    # shareable chain carries no tweet
    c = {k: dict(v) for k, v in good.items()}
    c["Lcom/twitter/share/api/m;"] = dict(c["Lcom/twitter/share/api/m;"], fields=[])
    cases.append(("no tweet field", c))

    # contract shape gone
    c = {k: dict(v) for k, v in good.items()}
    c["Lcom/twitter/app/common/dialog/o;"] = dict(
        c["Lcom/twitter/app/common/dialog/o;"],
        methods=c["Lcom/twitter/app/common/dialog/o;"]["methods"][:3],
    )
    cases.append(("contract shape changed", c))

    bad = 0
    for label, broken in cases:
        _r, fs = analyse(broken)
        if fs:
            print("  ok      %-28s -> %s" % (label, fs[0][:70]))
        else:
            print("  NO-OP   %-28s -> reported healthy; this check proves nothing" % label)
            bad += 1

    print("\nself-test: %d/%d checks can fail" % (len(cases) - bad, len(cases)))
    return 1 if bad else 0


def main():
    if len(sys.argv) < 2:
        print(__doc__.strip())
        return 2
    if sys.argv[1] == "--self-test":
        return self_test()

    t0 = time.time()
    classes = load_classes(sys.argv[1])
    print("[parse] %d classes in %.1fs\n" % (len(classes), time.time() - t0))
    return report(*analyse(classes))


if __name__ == "__main__":
    sys.exit(main())
