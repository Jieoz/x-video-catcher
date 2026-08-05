#!/usr/bin/env python3
"""Check that a real X APK still has the shapes AND the call sites HostResolver hooks.

The unit tests run against hand-written fixtures. They prove the *predicates* are right and say
nothing about whether they still match the shipped app. This applies the same predicates to a real
host APK — and then does the thing the unit tests structurally cannot.

## Why this gate has a reachability half

Versions 1.2, 1.3 and 1.4 each hooked a different class in the action-sheet family. All three passed
their tests, resolved their anchors on the real APK, installed cleanly, logged success, and did
nothing. Instruction-level cross-referencing finally showed why: `com.twitter.app.share.ui.d.n0`, the
1.4.0 anchor, has **zero call sites in the entire application**, as does `ShareSheetDialogFragment`.
That sheet is dead code in 12.13.

The lesson is not "those were the wrong classes". It is that **shape cannot detect unreachability** —
dead code has the right shape, so a shape-only gate reports healthy on an anchor the host never
executes. Three releases of evidence say a shape check alone is not a release gate.

So every anchor here is checked twice:

  * SHAPE        -- the structural predicate from HostResolver still matches exactly one class
  * REACHABILITY -- something in the APK actually invokes it, counted from the instruction stream

An anchor that resolves but has no callers FAILS. That is the check that would have caught this bug
in 1.2 instead of 1.5.

## Anchors (the live Compose share sheet)

  1. sheet open   -- (X)->boolean on the class holding a ComposeView and an Activity: chooser.j.J0,
                     where the sheet is attached to the window
  2. row model    -- 3 String + Drawable + boolean value type: models.share.a, one row
  3. row provider -- (String)->ArrayList on the class owning a Context and a PackageManager getter:
                     share.impl.c.a, which builds the row list
  4. action       -- the sealed subtype whose fields are exactly (String, row): sharesheet.t$g
  5. dispatch     -- (action_root)->void on classes declaring getState(); ALL must be found, since an
                     implementation that does not delegate is its own entry point
  6. tweet field  -- a com.twitter.model.core.* field on the shareable chain

Exits non-zero when the host stops matching, so this gates a release rather than producing a wall of
output nobody reads. ``--self-test`` proves every check can fail.

Usage:
  verify_host_anchors.py <host.apk>
  verify_host_anchors.py --self-test
"""
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])

from dexdefs import load_classes  # noqa: E402
from dexinvoke import ScanError, find_callers, find_instantiations, pretty  # noqa: E402

STR = "Ljava/lang/String;"
DRAWABLE = "Landroid/graphics/drawable/Drawable;"
CONTEXT = "Landroid/content/Context;"
ACTIVITY = "Landroid/app/Activity;"
PACKAGE_MANAGER = "Landroid/content/pm/PackageManager;"
ARRAYLIST = "Ljava/util/ArrayList;"
COMPOSE_VIEW = "Landroidx/compose/ui/platform/ComposeView;"
TWEET_PREFIX = "Lcom/twitter/model/core/"

CHOOSER_PKG = "Lcom/twitter/share/chooser/"
SHARE_IMPL_PKG = "Lcom/x/share/impl/"
SHARE_ROW_PKG = "Lcom/x/models/share/"
SHARESHEET_PKG = "Lcom/x/dms/components/sharesheet/"

# --- share-sheet capture chain, measured on 12.13.0-RELEASE.0 (versionCode 312130000) -------
#
# Read this before touching the names below.
#
# Everything recorded before 20260805 came from the 12.13.0-BETA bundle, and beta and release
# are obfuscated SEPARATELY. That is what the device log's
# `com.twitter.model.core.entity.b0 not found` actually meant: channel drift, not a version
# bump, and not a wrong criterion. Five releases were spent chasing that as a search problem.
#
# So the controller is `e0` here where beta had `h0` -- same 15 fields in the same order, with
# `a` the row list and `b` the tweet wrapper. Its PACKAGE survives obfuscation, which is why it
# is findable at all; the class letter does not, which is why this gate exists.
#
# These stay literal, copied from the release APK, for the same reason every other anchor here
# is literal: a name derived at runtime cannot drift, and therefore cannot report the next
# rewrite.
SHEET_CONTROLLER = "Lcom/twitter/tweet/action/legacy/e0;"
SHEET_SHOW_METHOD = "h"          # h(FragmentManager) -> void, shows the sheet
SHEET_ROWS_FIELD = "a"           # java.util.List of sheet rows
SHEET_TWEET_FIELD = "b"          # the tweet wrapper, i.e. what four releases failed to reach
TWEET_WRAPPER = "Lcom/twitter/model/core/e;"
TWEET_BODY = "Lcom/twitter/model/core/d;"
MEDIA_ENTITY = "Lcom/twitter/model/core/entity/c0;"
MEDIA_TYPE_ENUM = "Lcom/twitter/model/core/entity/c0$d;"
VIDEO_INFO = "Lcom/twitter/media/av/model/z;"
VIDEO_VARIANT = "Lcom/twitter/media/av/model/a0;"

# getId survived R8 on the tweet body, so it is the one lookup key on this chain that is not a
# single obfuscated letter.
TWEET_ID_GETTER = "getId"

# Enum.name() must return the real string at runtime, so R8 keeps these constants even while
# renaming the class that holds them. That is what located the media entity on release, and it
# is the property this gate leans on rather than any letter.
MEDIA_TYPE_CONSTANTS = ("VIDEO", "ANIMATED_GIF", "IMAGE")

# The controller's package is unobfuscated. Assert that so a future rename of the package
# itself -- the one drift that would defeat every letter below -- fails here first.
SHEET_PKG = "Lcom/twitter/tweet/action/legacy/"

ROW_FIELD_COUNT = 5
VALUE_TYPE_METHODS = ("equals", "hashCode", "toString")

# Verified on com.twitter.android 12.13.0-release.0 (versionCode 312130000).
EXPECT_DISPATCH_MIN = 2


def _instance(fields):
    return [f for f in fields if not f["static"]]


def _in_pkg(classes, pkg):
    """Classes directly in pkg, excluding nested types unless asked for."""
    return {n: c for n, c in classes.items() if n.startswith(pkg) and "$" not in n[len(pkg):]}


def _in_pkg_nested(classes, pkg):
    return {n: c for n, c in classes.items() if n.startswith(pkg)}


def is_row_shape(cls):
    """The row predicate, mirroring HostResolver.isRowShape."""
    fields = _instance(cls["fields"])
    if len(fields) != ROW_FIELD_COUNT:
        return False
    types = [f["type"] for f in fields]
    if types.count(STR) != 3 or types.count(DRAWABLE) != 1 or types.count("Z") != 1:
        return False
    names = {m["name"] for m in cls["methods"]}
    return all(v in names for v in VALUE_TYPE_METHODS)


def analyse(classes):
    """Shape half. Returns (results, failures). Pure, so the self-test can drive it with fixtures."""
    results = {}
    failures = []

    # 1. sheet open ---------------------------------------------------------
    opens = []
    for name, cls in _in_pkg(classes, CHOOSER_PKG).items():
        types = [f["type"] for f in _instance(cls["fields"])]
        if COMPOSE_VIEW not in types or ACTIVITY not in types:
            continue
        for m in cls["methods"]:
            if m["ret"] == "Z" and len(m["params"]) == 1 and not m["static"]:
                opens.append((name, m["name"]))
    results["sheet_open"] = sorted(opens)
    if len(opens) != 1:
        failures.append(
            "expected exactly one sheet-open method (ComposeView+Activity, (X)->boolean), got %s"
            % sorted(opens)
        )

    # 2. row model ---------------------------------------------------------
    rows = sorted(n for n, c in _in_pkg(classes, SHARE_ROW_PKG).items() if is_row_shape(c))
    results["row"] = rows
    if len(rows) != 1:
        failures.append("expected exactly one row model, got %s" % rows)
        return results, failures
    row = rows[0]

    # 3. row provider ------------------------------------------------------
    providers = []
    for name, cls in _in_pkg(classes, SHARE_IMPL_PKG).items():
        if not any(f["type"] == CONTEXT for f in _instance(cls["fields"])):
            continue
        if not any(m["ret"] == PACKAGE_MANAGER and not m["params"] for m in cls["methods"]):
            continue
        for m in cls["methods"]:
            if m["params"] == [STR] and m["ret"] == ARRAYLIST and not m["static"]:
                providers.append((name, m["name"]))
    results["provider"] = sorted(providers)
    if len(providers) != 1:
        failures.append(
            "expected exactly one row provider ((String)->ArrayList on a Context+PackageManager "
            "class), got %s" % sorted(providers)
        )

    # 4. action ------------------------------------------------------------
    actions = sorted(
        n for n, c in _in_pkg_nested(classes, SHARESHEET_PKG).items()
        if [f["type"] for f in _instance(c["fields"])] == [STR, row]
    )
    results["action"] = actions
    if len(actions) != 1:
        failures.append("expected exactly one action carrying a row, got %s" % actions)
        return results, failures
    action_root = classes[actions[0]]["super"]
    results["action_root"] = action_root

    # 5. dispatch ----------------------------------------------------------
    dispatch = []
    for pkg in (SHARE_IMPL_PKG, SHARESHEET_PKG):
        for name, cls in _in_pkg(classes, pkg).items():
            if not any(m["name"] == "getState" and not m["params"] for m in cls["methods"]):
                continue
            for m in cls["methods"]:
                if m["ret"] == "V" and m["params"] == [action_root]:
                    dispatch.append((name, m["name"]))
    results["dispatch"] = sorted(dispatch)
    if len(dispatch) < EXPECT_DISPATCH_MIN:
        failures.append(
            "found %d dispatch point(s) %s, expected at least %d — an implementation that does not "
            "delegate is its own entry point, so a single hook leaves one path inert"
            % (len(dispatch), sorted(dispatch), EXPECT_DISPATCH_MIN)
        )

    # 6. tweet field -------------------------------------------------------
    tweets = {}
    for name, cls in classes.items():
        if not name.startswith("Lcom/twitter/share/"):
            continue
        hits = [(f["name"], f["type"]) for f in _instance(cls["fields"])
                if f["type"] and f["type"].startswith(TWEET_PREFIX)]
        if hits:
            tweets[name] = hits
    results["tweets"] = tweets
    if not tweets:
        failures.append("no tweet field on any com.twitter.share.* class")

    return results, failures


def _field_type(cls, fname):
    """Type of instance field ``fname``, or None. Static fields are not host state."""
    for f in _instance(cls["fields"]):
        if f["name"] == fname:
            return f["type"]
    return None


def analyse_capture_chain(classes):
    """Anchor 7: the share-sheet capture chain. Returns (results, failures).

    Kept separate from ``analyse`` because it answers a different question. ``analyse`` asks
    whether the Compose share sheet under ``com.x.share.impl`` still looks the way HostResolver
    expects. This asks whether the tweet can be read directly off the LEGACY sheet controller,
    which is a different code path in the host -- and the one that actually holds the tweet.

    Four probe releases searched the object graph at share time and never found a tweet, with
    ``exhausted=false`` proving the walk had drained everything reachable. It was reachable all
    along, one field off a controller in an unobfuscated package; the searches were rooted in
    the wrong sheet.

    Every link is asserted rather than printed. The 1.10.0 device log carried
    ``media entity class com.twitter.model.core.entity.b0 not found`` -- a name recorded from
    the BETA bundle and never rechecked against release. That is exactly the failure this gate
    exists to turn red before an APK reaches a phone.
    """
    results = {}
    failures = []

    def need_field(owner, fname, expect, what):
        cls = classes.get(owner)
        if cls is None:
            failures.append("%s: class %s missing from host" % (what, pretty(owner)))
            return
        actual = _field_type(cls, fname)
        if actual is None:
            failures.append("%s: %s.%s not found" % (what, pretty(owner), fname))
        elif actual != expect:
            failures.append("%s: %s.%s is %s, expected %s"
                            % (what, pretty(owner), fname, pretty(actual), pretty(expect)))

    # The unobfuscated package is what makes the controller findable at all; if it is ever
    # renamed, every letter below is void, so check it first and say so plainly.
    if not any(name.startswith(SHEET_PKG) for name in classes):
        failures.append("capture: package %s absent -- every anchor below is void"
                        % SHEET_PKG.strip("L;").replace("/", "."))

    # capture -- the sheet controller holds the tweet outright, no search
    need_field(SHEET_CONTROLLER, SHEET_TWEET_FIELD, TWEET_WRAPPER, "capture")
    need_field(SHEET_CONTROLLER, SHEET_ROWS_FIELD, "Ljava/util/List;", "sheet rows")

    # the hook point: h(FragmentManager) -> void, called to show the sheet
    ctrl = classes.get(SHEET_CONTROLLER)
    if ctrl is None:
        failures.append("hook point: controller %s missing from host"
                        % pretty(SHEET_CONTROLLER))
    elif not any(m["name"] == SHEET_SHOW_METHOD
                 and m["params"] == ["Landroidx/fragment/app/FragmentManager;"]
                 and m["ret"] == "V"
                 for m in ctrl["methods"]):
        failures.append("hook point: %s.%s(FragmentManager) -> void not found"
                        % (pretty(SHEET_CONTROLLER), SHEET_SHOW_METHOD))

    # wrapper -> body, and the quoted tweet, which carries its own media
    need_field(TWEET_WRAPPER, "a", TWEET_BODY, "wrapper")
    need_field(TWEET_WRAPPER, "c", TWEET_WRAPPER, "quoted tweet")

    # lookup key -- the share URL carries a status id, so capture is useless without this
    body = classes.get(TWEET_BODY)
    if body is None:
        failures.append("lookup key: class %s missing from host" % pretty(TWEET_BODY))
    elif not any(m["name"] == TWEET_ID_GETTER and m["ret"] == "J" and not m["params"]
                 for m in body["methods"]):
        failures.append("lookup key: %s.%s() -> long not found"
                        % (pretty(TWEET_BODY), TWEET_ID_GETTER))

    # media -- entity carries its type and, for video, the variant list
    need_field(MEDIA_ENTITY, "p", MEDIA_TYPE_ENUM, "media type")
    need_field(MEDIA_ENTITY, "r", VIDEO_INFO, "video info")
    need_field(VIDEO_INFO, "c", "Ljava/util/List;", "variant list")
    # The variant is where the playable URL actually lives, so its identity has to rest on more
    # than the bitrate: av/model/b0 on this build is (int a, int b, int c) and would satisfy a
    # bitrate-only assertion while carrying no URL at all. Requiring the String alongside is what
    # makes a rename of this class detectable -- the ablation proved the bitrate alone was not.
    need_field(VIDEO_VARIANT, "a", "I", "variant bitrate")
    need_field(VIDEO_VARIANT, "b", "Ljava/lang/String;", "variant url")

    enum = classes.get(MEDIA_TYPE_ENUM)
    if enum is None:
        failures.append("media type: enum %s missing from host" % pretty(MEDIA_TYPE_ENUM))
    else:
        consts = {f["name"] for f in enum["fields"]
                  if f["static"] and f["type"] == MEDIA_TYPE_ENUM}
        missing = [c for c in MEDIA_TYPE_CONSTANTS if c not in consts]
        if missing:
            failures.append("media type: %s missing constant(s) %s"
                            % (pretty(MEDIA_TYPE_ENUM), missing))
        results["media_types"] = sorted(consts)

    results["capture_chain"] = [SHEET_CONTROLLER, TWEET_WRAPPER, TWEET_BODY, MEDIA_ENTITY]
    return results, failures


def check_capture_reachability(apk):
    """Prove the hook point is live code, not a shape that happens to hold a tweet.

    Shape cannot detect unreachability -- the lesson 1.2 through 1.4 paid for, when anchors
    matched a class nobody ever ran. The sheet controller is reached by being *constructed* and
    its show method by being *called*, so both halves are checked: something builds the
    controller, and something invokes the method being hooked.

    A hook on a method no caller reaches is silent at runtime and looks identical to a hook that
    simply never fired, which is the failure mode this rules out before shipping.

    Returns ``(sites, failures)`` where sites is the full list of constructing classes.
    """
    try:
        built = find_instantiations(apk, [SHEET_CONTROLLER])
    except ScanError as exc:
        return [], ["capture chain: instruction scan failed: %s" % exc]

    sites = sorted(set(built.get(SHEET_CONTROLLER, [])))
    if not sites:
        return sites, ["capture chain: %s is never constructed, so hooking it can never fire"
                       % pretty(SHEET_CONTROLLER)]

    # The method being hooked must have a caller; otherwise the hook is dead on arrival.
    try:
        callers = find_callers(apk, [(SHEET_CONTROLLER, SHEET_SHOW_METHOD)])
    except ScanError as exc:
        return sites, ["capture chain: caller scan failed: %s" % exc]

    show_callers = sorted(set(callers.get((SHEET_CONTROLLER, SHEET_SHOW_METHOD), [])))
    if not show_callers:
        return sites, [
            "capture chain: %s.%s has no call sites -- a hook there would never run"
            % (pretty(SHEET_CONTROLLER), SHEET_SHOW_METHOD)
        ]
    return sites, []


def supertypes(classes, name, seen=None):
    """``name`` plus every superclass and interface reachable from it, nearest first."""
    if seen is None:
        seen = []
    if name in seen or name not in classes:
        return seen
    seen.append(name)
    cls = classes[name]
    sup = cls.get("super")
    if sup:
        supertypes(classes, sup, seen)
    for iface in cls.get("interfaces", []) or []:
        supertypes(classes, iface, seen)
    return seen


def declaring_types(classes, cls_desc, method):
    """Every type in ``cls_desc``'s hierarchy that declares ``method``.

    A call site encodes the *declared* type, so these are the names an invoke of this method can
    possibly reference. ``method=None`` means "the class itself" (used for value types, which are
    referenced rather than invoked), so only the class matters.
    """
    if method is None:
        return [cls_desc]
    return [t for t in supertypes(classes, cls_desc)
            if any(m["name"] == method for m in classes.get(t, {}).get("methods", []))]


def check_reachability(apk, results, classes=None):
    """Reachability half: every anchor must actually be executed by the host.

    Returns ``(counts, failures)``, where counts maps label -> call-site count.

    ## Why this is not just "count call sites of the anchor"

    dex encodes the declared type at an invoke, never the runtime one. An override of an interface
    or superclass method therefore has **zero call sites under its own name** while the host calls it
    on every interaction. Counting by exact declaring class rejected two correct anchors here
    (``share.impl.c.a`` overriding ``sharesheet.n.a``, ``sharesheet.j.h`` overriding
    ``sharesheet.r.h``) and would reject every future override the same way.

    So an anchor is live when both hold:

      * some type in its hierarchy that declares the method has call sites, **and**
      * the concrete anchor class is instantiated somewhere (``new-instance``), or it is the very
        type being invoked

    The second condition is what keeps this from becoming a rubber stamp: without it, any dead class
    could inherit reachability from a busy base type. A class nothing ever constructs cannot receive
    a virtual call no matter how often the base method is invoked.

    ``classes`` is the parsed shape map; when omitted, hierarchy widening is unavailable and the
    check degrades to exact-name counting, which is reported so a caller cannot mistake it for a
    clean pass.
    """
    targets = []
    labels = {}
    anchors = []

    def add(label, cls_desc, method=None):
        owners = declaring_types(classes, cls_desc, method) if classes else [cls_desc]
        if not owners:
            owners = [cls_desc]
        anchors.append((label, cls_desc, method, owners))
        for owner in owners:
            t = (owner, method)
            if t not in labels:
                targets.append(t)
            labels.setdefault(t, label)

    for cls, meth in results.get("sheet_open", []):
        add("sheet open %s.%s" % (pretty(cls), meth), cls, meth)
    for cls, meth in results.get("provider", []):
        add("row provider %s.%s" % (pretty(cls), meth), cls, meth)
    for cls, meth in results.get("dispatch", []):
        add("dispatch %s.%s" % (pretty(cls), meth), cls, meth)
    for cls in results.get("row", []):
        add("row model %s" % pretty(cls), cls, None)

    # The sheet controller: method=None asks only "is this type referenced". The dedicated
    # check_capture_reachability pass is what proves the hook point has real callers.
    for cls in results.get("capture_chain", [])[:1]:
        add("sheet controller %s" % pretty(cls), cls, None)

    if not anchors:
        return {}, ["nothing to check for reachability — shape half found no anchors"]

    try:
        found = find_callers(apk, targets)
        # Only concrete anchors reached through a supertype need the instantiation evidence; asking
        # for the rest would scan type ids for nothing.
        need_new = sorted({cls for _l, cls, m, owners in anchors
                           if m is not None and owners != [cls]})
        built = find_instantiations(apk, need_new) if need_new else {}
    except ScanError as exc:
        return {}, ["instruction scan failed: %s" % exc]

    counts = {}
    failures = []
    for label, cls_desc, method, owners in anchors:
        sites = set()
        for owner in owners:
            sites.update(found.get((owner, method), []))

        # An invoke-super from inside the anchor's own hierarchy is the override delegating upward; it
        # cannot be the reason the override is entered. Counting it would let an anchor vouch for its
        # own reachability: an unreachable override whose only supertype call site is its own
        # super.m() would read as live.
        #
        # The opcode test has to come first and cannot be replaced by "caller is in the hierarchy".
        # In this host, share.impl.b.h contains an *invoke-interface* to sharesheet.r.h, and b
        # implements r -- so caller and target are both in one hierarchy while the call is ordinary
        # delegation to another instance, a genuine entry point that must keep counting. Only the
        # opcode distinguishes the two.
        entrances = {s for s in sites
                     if not (s.is_self_super() and s.caller_class in owners)}
        counts[label] = len(entrances)

        if not entrances:
            detail = ""
            if sites:
                detail = (" (%d call site(s) exist but all are super calls from within the "
                          "hierarchy itself)" % len(sites))
            failures.append(
                "%s has ZERO entry points on %s — it resolves by shape but nothing outside its own "
                "hierarchy invokes it%s. This is exactly the 1.2-1.4 failure: hooks install, "
                "nothing fires."
                % (label, " / ".join(pretty(o) for o in owners), detail)
            )
            continue

        # Reached only through a supertype: the concrete class must be constructed, or the virtual
        # call can never land on it.
        if method is not None and owners != [cls_desc]:
            direct = [s for s in found.get((cls_desc, method), []) if not s.is_self_super()]
            if not direct:
                new_sites = built.get(cls_desc, [])
                if not new_sites:
                    failures.append(
                        "%s is only reachable via supertype %s and is NEVER instantiated — a "
                        "virtual call cannot land on a class nothing constructs."
                        % (label, pretty(owners[1] if len(owners) > 1 else owners[0]))
                    )
    return counts, failures


def report(results, failures, counts=None, reach_failures=()):
    for cls, meth in results.get("sheet_open", []):
        print("[open]     %s.%s" % (pretty(cls), meth))
    for cls in results.get("row", []):
        print("[row]      %s" % pretty(cls))
    for cls, meth in results.get("provider", []):
        print("[provider] %s.%s" % (pretty(cls), meth))
    for cls in results.get("action", []):
        print("[action]   %s   root = %s" % (pretty(cls), pretty(results.get("action_root"))))
    for cls, meth in results.get("dispatch", []):
        print("[dispatch] %s.%s" % (pretty(cls), meth))
    for cls, hits in sorted(results.get("tweets", {}).items())[:5]:
        print("[tweet]    %s -> %s" % (pretty(cls), hits))

    chain = results.get("capture_chain")
    if chain:
        print("[capture]  %s.%s -> %s.a -> %s.%s()"
              % (pretty(chain[0]), SHEET_TWEET_FIELD, pretty(chain[1]), pretty(chain[2]),
                 TWEET_ID_GETTER))
        print("[capture]  hook %s.%s(FragmentManager)" % (pretty(chain[0]), SHEET_SHOW_METHOD))
        print("[capture]  media %s type=%s" % (pretty(chain[3]), results.get("media_types", [])))
    for site in results.get("capture_sites", [])[:8]:
        print("[capture]  built by %s" % site)

    if counts:
        print()
        for label, n in sorted(counts.items()):
            mark = "ok " if n else "DEAD"
            print("[reach] %s %-52s %d call site(s)" % (mark, label, n))

    print()
    all_failures = list(failures) + list(reach_failures)
    if all_failures:
        for f in all_failures:
            print("FAIL: %s" % f)
        return 1
    print("all anchors match this host build, and all are reachable")
    return 0


def _fixture():
    """A minimal host shaped like the real one, for the self-test."""
    def m(name, ret, params, static=False):
        return {"name": name, "ret": ret, "params": params, "static": static}

    def f(name, type_, static=False):
        return {"name": name, "type": type_, "static": static}

    row = SHARE_ROW_PKG + "a;"
    action = SHARESHEET_PKG + "t$g;"
    root = SHARESHEET_PKG + "t;"
    value_methods = [m(v, "Z" if v == "equals" else "I", []) for v in VALUE_TYPE_METHODS]

    return {
        CHOOSER_PKG + "j;": {
            "name": CHOOSER_PKG + "j;", "super": None, "interfaces": [],
            "fields": [f("a", COMPOSE_VIEW), f("b", ACTIVITY)],
            "methods": [m("J0", "Z", ["Ljava/lang/Object;"])],
        },
        row: {
            "name": row, "super": None, "interfaces": [],
            "fields": [f("a", STR), f("b", STR), f("c", STR), f("d", DRAWABLE), f("e", "Z")],
            "methods": value_methods,
        },
        SHARE_IMPL_PKG + "c;": {
            "name": SHARE_IMPL_PKG + "c;", "super": None, "interfaces": [],
            "fields": [f("a", CONTEXT)],
            "methods": [m("getPackageManager", PACKAGE_MANAGER, []), m("a", ARRAYLIST, [STR])],
        },
        action: {
            "name": action, "super": root, "interfaces": [],
            "fields": [f("a", STR), f("b", row)], "methods": [],
        },
        root: {"name": root, "super": None, "interfaces": [], "fields": [], "methods": []},
        SHARE_IMPL_PKG + "b;": {
            "name": SHARE_IMPL_PKG + "b;", "super": None, "interfaces": [],
            "fields": [],
            "methods": [m("getState", "Ljava/lang/Object;", []), m("h", "V", [root])],
        },
        SHARESHEET_PKG + "r;": {
            "name": SHARESHEET_PKG + "r;", "super": None, "interfaces": [],
            "fields": [],
            "methods": [m("getState", "Ljava/lang/Object;", []), m("h", "V", [root])],
        },
        "Lcom/twitter/share/api/m;": {
            "name": "Lcom/twitter/share/api/m;", "super": None, "interfaces": [],
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

    # sheet open gone: no unconditional evidence the panel opened
    c = {k: dict(v) for k, v in good.items()}
    c[CHOOSER_PKG + "j;"] = dict(c[CHOOSER_PKG + "j;"], fields=[])
    cases.append(("sheet open loses ComposeView", c))

    # row model gains a field
    c = {k: dict(v) for k, v in good.items()}
    rowcls = c[SHARE_ROW_PKG + "a;"]
    c[SHARE_ROW_PKG + "a;"] = dict(
        rowcls, fields=rowcls["fields"] + [{"name": "x", "type": "I", "static": False}]
    )
    cases.append(("row model shape changed", c))

    # row model stops being a value type
    c = {k: dict(v) for k, v in good.items()}
    c[SHARE_ROW_PKG + "a;"] = dict(c[SHARE_ROW_PKG + "a;"], methods=[])
    cases.append(("row model not a value type", c))

    # provider returns an immutable List
    c = {k: dict(v) for k, v in good.items()}
    pc = c[SHARE_IMPL_PKG + "c;"]
    c[SHARE_IMPL_PKG + "c;"] = dict(pc, methods=[
        dict(m, ret="Ljava/util/List;") if m["name"] == "a" else m for m in pc["methods"]
    ])
    cases.append(("provider returns List not ArrayList", c))

    # only one dispatch point: the 1.3.0 bug
    c = {k: dict(v) for k, v in good.items()}
    del c[SHARESHEET_PKG + "r;"]
    cases.append(("single dispatch point", c))

    # dispatch loses getState
    c = {k: dict(v) for k, v in good.items()}
    for k in (SHARE_IMPL_PKG + "b;", SHARESHEET_PKG + "r;"):
        c[k] = dict(c[k], methods=[m for m in c[k]["methods"] if m["name"] != "getState"])
    cases.append(("dispatch loses getState", c))

    # action carries no row
    c = {k: dict(v) for k, v in good.items()}
    c[SHARESHEET_PKG + "t$g;"] = dict(
        c[SHARESHEET_PKG + "t$g;"], fields=[{"name": "a", "type": STR, "static": False}]
    )
    cases.append(("action carries no row", c))

    # tweet field gone
    c = {k: dict(v) for k, v in good.items()}
    c["Lcom/twitter/share/api/m;"] = dict(c["Lcom/twitter/share/api/m;"], fields=[])
    cases.append(("no tweet field", c))

    bad = 0
    for label, broken in cases:
        _r, fs = analyse(broken)
        if fs:
            print("  ok      %-32s -> %s" % (label, fs[0][:64]))
        else:
            print("  NO-OP   %-32s -> reported healthy; this check proves nothing" % label)
            bad += 1

    # The reachability half must be able to fail too, and it is the half that matters most: it is the
    # only check that would have caught the 1.2-1.4 bug. Driving the real check_reachability against
    # real dex bytes -- asserting a hand-made dict would test the assertion instead of the code, which
    # is the exact failure mode this file keeps warning about.
    #
    # Four fixtures, two expected-FAIL and two expected-PASS. Only-reject cases would still pass if
    # the scanner were blind and returned zero for everything; only-accept cases would still pass if
    # it waved everything through. Both directions are needed, on both the direct and the virtual
    # dispatch axis -- a suite covering only direct calls is how the override false-positive shipped.
    extra = 0
    reach_cases = [
        ("dead anchor rejected", "dead", False, "ZERO entry points"),
        ("direct call accepted", "direct", True, None),
        ("override via supertype accepted", "override", True, None),
        ("orphan override rejected", "override_orphan", False, "NEVER instantiated"),
        ("self-super-only rejected", "self_super_only", False, "ZERO entry points"),
        ("sibling interface delegation kept", "sibling_interface_delegation", True, None),
    ]
    try:
        fixtures = {kind: _fixture_apk(kind) for _l, kind, _e, _n in reach_cases}
    except ToolchainMissing as exc:
        print("  SKIP    %-32s -> no javac/d8 here (%s)" % ("reachability", exc))
        fixtures = None

    if fixtures:
        extra = len(reach_cases)
        for label, kind, expect_pass, needle in reach_cases:
            apk = fixtures[kind]
            # Hierarchy comes from the fixture's own dex, the same way it does for a real host: a
            # hand-written classes dict here would let the fixture disagree with the bytecode.
            fixture_classes = load_classes(apk)
            results = {"sheet_open": [(CHOOSER_PKG + "j;", "J0")],
                       "row": [], "provider": [], "dispatch": []}
            counts, rf = check_reachability(apk, results, fixture_classes)
            n = next(iter(counts.values()), 0) if counts else 0

            if expect_pass:
                if not rf and n > 0:
                    print("  ok      %-32s -> counted %d call site(s)" % (label, n))
                else:
                    print("  FALSE+  %-32s -> a live anchor was rejected: %d site(s), %s"
                          % (label, n, rf))
                    bad += 1
            else:
                if rf and needle in rf[0]:
                    print("  ok      %-32s -> %s" % (label, rf[0][:56]))
                else:
                    print("  NO-OP   %-32s -> expected %r, got %s" % (label, needle, rf))
                    bad += 1

    total = len(cases) + extra
    print("\nself-test: %d/%d checks can fail" % (total - bad, total))
    return 1 if bad else 0


class ToolchainMissing(Exception):
    """No javac/d8 on this box, so the reachability self-test cannot be built here."""


ANCHOR_PKG = "com.twitter.share.chooser"
ANCHOR_DIR = ("com", "twitter", "share", "chooser")

# The four reachability fixtures, as java sources. Each is a real host situation the gate has to
# judge, and every one of them has actually occurred in this project:
#
#   dead            -- the 1.2-1.4 bug: right shape, nothing calls it            -> must FAIL
#   direct          -- plain call on the declared type                          -> must PASS
#   override        -- override called through its supertype, class constructed  -> must PASS
#                      (rejecting this is the bug that flagged two good anchors)
#   override_orphan -- override of a busy base, but nothing constructs the class -> must FAIL
#                      (guards the widening from becoming a rubber stamp)
_FIXTURE_SOURCES = {
    "dead": {
        "j": "public class j {\n"
             "  public boolean J0(Object o) { return o != null; }\n"
             "}\n",
    },
    "direct": {
        "j": "public class j {\n"
             "  public boolean J0(Object o) { return o != null; }\n"
             "}\n",
        "Caller": "public class Caller {\n"
                  "  public boolean go(j sheet) { return sheet.J0(this); }\n"
                  "}\n",
    },
    "override": {
        "Base": "public class Base {\n"
                "  public boolean J0(Object o) { return o != null; }\n"
                "}\n",
        "j": "public class j extends Base {\n"
             "  @Override public boolean J0(Object o) { return o != this; }\n"
             "}\n",
        # Calls through Base (so j.J0 has no call site of its own) and constructs j, which is what
        # makes the virtual call able to land on the override.
        "Caller": "public class Caller {\n"
                  "  public boolean go() { Base b = new j(); return b.J0(this); }\n"
                  "}\n",
    },
    "override_orphan": {
        "Base": "public class Base {\n"
                "  public boolean J0(Object o) { return o != null; }\n"
                "}\n",
        "j": "public class j extends Base {\n"
             "  @Override public boolean J0(Object o) { return o != this; }\n"
             "}\n",
        # Base is busy, but nothing ever constructs j, so the override can never run.
        "Caller": "public class Caller {\n"
                  "  public boolean go() { Base b = new Base(); return b.J0(this); }\n"
                  "}\n",
    },
    # The override's only call site on its hierarchy is its own super.J0() -- the anchor vouching for
    # itself. j is even constructed, so instantiation evidence alone waves this through; nothing
    # outside j ever calls J0, so it cannot fire. Found in the real host: share.impl.b.h's site list
    # contains "b.h -> sharesheet.r.h", which is b.h calling its own parent.
    "self_super_only": {
        "Base": "public class Base {\n"
                "  public boolean J0(Object o) { return o != null; }\n"
                "}\n",
        "j": "public class j extends Base {\n"
             "  @Override public boolean J0(Object o) { return super.J0(o); }\n"
             "}\n",
        "Caller": "public class Caller {\n"
                  "  public Object go() { return new j(); }\n"
                  "}\n",
    },
    # The mirror of self_super_only, and the reason the exclusion keys on the opcode rather than on
    # "is the caller in this hierarchy": j implements Iface and calls J0 on a *different* Iface
    # instance. Caller and target sit in one hierarchy, yet this is ordinary delegation and a real
    # entry point. This exact shape is live in the host (share.impl.b.h invoke-interface
    # sharesheet.r.h), so widening the exclusion to any same-hierarchy caller would silently declare
    # a working dispatch anchor dead.
    "sibling_interface_delegation": {
        "Iface": "public interface Iface {\n"
                 "  boolean J0(Object o);\n"
                 "}\n",
        "j": "public class j implements Iface {\n"
             "  private final Iface next;\n"
             "  public j(Iface next) { this.next = next; }\n"
             "  @Override public boolean J0(Object o) { return next.J0(o); }\n"
             "}\n",
        # j has to be constructed somewhere or axis B rejects it first and this fixture would test
        # instantiation rather than the opcode distinction it exists for. The host does construct the
        # equivalent class: share.impl.b is new-instance'd in share.impl.o.a, and share.impl.c in two
        # Dagger factories.
        "Factory": "public class Factory {\n"
                   "  public Iface make(Iface next) { return new j(next); }\n"
                   "}\n",
    },
}


def _fixture_apk(kind):
    """Build one reachability fixture APK with real javac + d8.

    The dex has to be genuine: the scanner is being asked what it can see in actual bytecode, and a
    hand-forged blob would test the fixture instead of the scanner.

    Raises ToolchainMissing when javac/d8 are absent, and lets build errors propagate. An earlier
    version returned None for both cases and the caller printed SKIP — which is how a `public class j`
    in a file named `Dead.java` (rejected by javac) turned the single most important check in this gate
    into a no-op that still reported "8/8 checks can fail". A gate that cannot run must say so loudly,
    not blend into the passing output.
    """
    import glob
    import os
    import shutil
    import subprocess
    import tempfile
    import zipfile

    javac = shutil.which("javac")
    d8 = shutil.which("d8") or next(
        iter(sorted(glob.glob("/opt/android-sdk/build-tools/*/d8"), reverse=True)), None
    )
    if not javac or not d8:
        raise ToolchainMissing("javac=%s d8=%s" % (javac, d8))

    bodies = _FIXTURE_SOURCES[kind]
    tmp = tempfile.mkdtemp(prefix="xvc-selftest-%s-" % kind)
    paths = []
    for name, body in bodies.items():
        # Each file name must match its public class name or javac refuses to compile it.
        path = os.path.join(tmp, name + ".java")
        with open(path, "w") as fh:
            fh.write("package %s;\n%s" % (ANCHOR_PKG, body))
        paths.append(path)

    subprocess.run([javac, "-d", tmp] + paths, check=True, capture_output=True)
    built = glob.glob(os.path.join(tmp, *(ANCHOR_DIR + ("*.class",))))
    if len(built) != len(bodies):
        raise OSError("javac produced %d .class files, expected %d, in %s"
                      % (len(built), len(bodies), tmp))
    subprocess.run([d8, "--output", tmp] + built, check=True, capture_output=True)
    apk = os.path.join(tmp, "%s.apk" % kind)
    with zipfile.ZipFile(apk, "w") as z:
        z.write(os.path.join(tmp, "classes.dex"), "classes.dex")
    return apk


def main():
    if len(sys.argv) < 2:
        print(__doc__.strip())
        return 2
    if sys.argv[1] == "--self-test":
        return self_test()

    apk = sys.argv[1]
    t0 = time.time()
    classes = load_classes(apk)
    print("[parse] %d classes in %.1fs\n" % (len(classes), time.time() - t0))

    results, failures = analyse(classes)

    # Anchor 7 runs regardless of the Compose share-sheet verdict: it is a different code path
    # in the host, and it is the one the next release actually depends on.
    capture, capture_failures = analyse_capture_chain(classes)
    results.update(capture)
    failures.extend(capture_failures)

    if not capture_failures:
        sites, reach = check_capture_reachability(apk)
        results["capture_sites"] = sites
        failures.extend(reach)

    # Reachability is only meaningful once shapes resolved; if they did not, the shape failure is the
    # actionable one and a scan would just add noise.
    counts, reach_failures = ({}, [])
    if not failures:
        t1 = time.time()
        # classes is required for the virtual-dispatch model: without the hierarchy every override
        # looks dead.
        counts, reach_failures = check_reachability(apk, results, classes)
        print("[scan]  instruction xref in %.1fs" % (time.time() - t1))

    return report(results, failures, counts, reach_failures)


if __name__ == "__main__":
    sys.exit(main())
