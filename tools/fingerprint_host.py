import io, json, os, sys, subprocess, hashlib

sys.path.insert(0, "/workspace/xvc-standalone/tools")
import verify_host_anchors as v
from dexdefs import load_classes

APK = "/workspace/xvc-re/rel/com.twitter.android.apk"
OUT = "/workspace/xvc-standalone/tools/host_fingerprint.json"

classes = load_classes(APK)
res, failures = v.analyse_capture_chain(classes)
if failures:
    sys.exit("cannot fingerprint a failing host: %s" % failures)

spec = classes[v.DATASPEC]
inst = [f for f in spec["fields"] if not f["static"]]

sites, reach_failures = v.check_capture_reachability(APK)
if reach_failures:
    sys.exit("reachability failed: %s" % reach_failures)

h = hashlib.sha256()
with open(APK, "rb") as fh:
    for chunk in iter(lambda: fh.read(1 << 20), b""):
        h.update(chunk)

fp = {
    "_comment": [
        "Measured facts about the host build the DataSpec anchor was verified against.",
        "This exists because the reachability gate needs a ~200MB host APK that cannot live in",
        "the repo, so on CI it skips with a warning -- and a skipped gate reads like a passing one.",
        "1.11 shipped that way. CI cannot re-derive these numbers without the APK, but it CAN",
        "assert that the module's own constants still agree with what was measured here, which",
        "catches an edit to MediaSpy that silently diverges from the verified host.",
        "Regenerate with tools/fingerprint_host.py <host.apk> after measuring a new bundle.",
    ],
    "host": {
        "apk_sha256": h.hexdigest(),
        "apk_bytes": os.path.getsize(APK),
        "class_count": len(classes),
    },
    "dataspec": {
        "descriptor": v.DATASPEC,
        "builder": v.DATASPEC_BUILDER,
        "builder_present": res.get("dataspec_builder"),
        "uri_field": v.DATASPEC_URI_FIELD,
        "field_types": list(v.DATASPEC_FIELDS),
        "field_names": [f["name"] for f in inst],
        "all_final": all(f.get("final") for f in inst),
    },
    "construction_sites": sites,
}

io.open(OUT, "w", encoding="utf-8").write(json.dumps(fp, indent=2, sort_keys=True) + "\n")
print("wrote", OUT)
print("apk sha256:", fp["host"]["apk_sha256"][:16], "bytes:", fp["host"]["apk_bytes"])
print("classes:", fp["host"]["class_count"])
print("all_final:", fp["dataspec"]["all_final"], "builder:", fp["dataspec"]["builder_present"])
print("sites:", len(sites))
