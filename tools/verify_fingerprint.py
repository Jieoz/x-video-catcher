#!/usr/bin/env python3
"""Assert the module's capture anchor still agrees with the measured host fingerprint.

## Why this exists

The reachability gate (``verify_host_anchors.py`` + ``ablate_capture.py``) needs the host APK,
which is ~200MB and cannot live in the repo. On CI it therefore skips with a warning -- and a
skipped gate is indistinguishable from a passing one in the checks list. 1.11.0 shipped exactly
that way: green checks, a warning nobody blocked on, and a module that did nothing on the device.

This gate cannot re-derive reachability without the APK. What it CAN do, with no APK present, is
assert that the constants the module and the verifier depend on still match what was actually
measured -- so an edit to ``MediaSpy.kt`` or to ``verify_host_anchors.py`` that quietly diverges
from the verified host fails here instead of on Jay's phone.

It is deliberately narrow: agreement with a recorded measurement, not proof about the current
host. Regenerate the fingerprint with ``tools/fingerprint_host.py`` when measuring a new bundle.

Usage: verify_fingerprint.py
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
FP = os.path.join(HERE, "host_fingerprint.json")
SPY = os.path.join(ROOT, "app", "src", "main", "java", "com", "jiesa",
                   "xvideocatcher", "hook", "MediaSpy.kt")

sys.path.insert(0, HERE)


def kotlin_spec_fields(text):
    """The SPEC_FIELDS list as written in MediaSpy.kt, in order.

    Parsed from source rather than imported, because there is no JVM here and the point is to
    compare what the shipped Kotlin says with what was measured.
    """
    m = re.search(r"private val SPEC_FIELDS = listOf\((.*?)\)", text, re.S)
    if not m:
        return None
    return re.findall(r'"([^"]+)"', m.group(1))


def descriptor_to_reflect(desc):
    """``Landroid/net/Uri;`` -> ``android.net.Uri``; ``[B`` stays ``[B``; ``J`` -> ``long``.

    The fingerprint records dex descriptors; MediaSpy compares ``Class.getName()`` output. The two
    spell the same nine types differently, and that mismatch is itself a bug this project shipped:
    ``byte[]`` was written where reflection says ``[B``, making the predicate always false while
    every negative test stayed green.
    """
    prims = {"J": "long", "I": "int", "Z": "boolean", "B": "byte", "C": "char",
             "S": "short", "F": "float", "D": "double", "V": "void"}
    if desc in prims:
        return prims[desc]
    if desc.startswith("["):
        return desc  # reflection reports arrays in descriptor form, e.g. [B
    if desc.startswith("L") and desc.endswith(";"):
        return desc[1:-1].replace("/", ".")
    return desc


def main():
    failures = []

    if not os.path.exists(FP):
        print("FAIL: %s missing -- the capture anchor has never been measured against a host"
              % os.path.relpath(FP, ROOT))
        return 1
    fp = json.load(open(FP))
    ds = fp["dataspec"]

    # 1. The measurement itself has to be one worth trusting.
    if not ds.get("all_final"):
        failures.append("fingerprint records a DataSpec that is not fully final -- the finality "
                        "discriminator was not verified, so the recording is not usable")
    if ds.get("builder_present") != "present":
        failures.append("fingerprint records builder %s -- the ambiguity the finality check "
                        "guards against was not present when measured, so the check was untested"
                        % ds.get("builder_present"))
    sites = fp.get("construction_sites") or []
    if not sites:
        failures.append("fingerprint records no construction sites -- reachability was never "
                        "established, which is the 1.11 failure exactly")
    media_sites = [s for s in sites if s.startswith("androidx.media3.")]
    if not media_sites:
        failures.append("fingerprint records construction only outside media3 %s -- a hook there "
                        "would not see playback" % sites[:4])

    # 2. The verifier's constants must still match the recording.
    try:
        import verify_host_anchors as v
    except Exception as exc:  # pragma: no cover - import failure is itself the finding
        failures.append("cannot import verify_host_anchors: %s" % exc)
        v = None

    if v is not None:
        if v.DATASPEC != ds["descriptor"]:
            failures.append("verifier DATASPEC is %s, fingerprint measured %s"
                            % (v.DATASPEC, ds["descriptor"]))
        if v.DATASPEC_BUILDER != ds["builder"]:
            failures.append("verifier DATASPEC_BUILDER is %s, fingerprint measured %s"
                            % (v.DATASPEC_BUILDER, ds["builder"]))
        if v.DATASPEC_URI_FIELD != ds["uri_field"]:
            failures.append("verifier DATASPEC_URI_FIELD is %s, fingerprint measured %s"
                            % (v.DATASPEC_URI_FIELD, ds["uri_field"]))
        if list(v.DATASPEC_FIELDS) != list(ds["field_types"]):
            failures.append("verifier DATASPEC_FIELDS %s != fingerprint %s"
                            % (list(v.DATASPEC_FIELDS), ds["field_types"]))

    # 3. The module's reflection-side list must describe the same nine types.
    if not os.path.exists(SPY):
        failures.append("MediaSpy.kt not found at %s" % os.path.relpath(SPY, ROOT))
    else:
        text = open(SPY, encoding="utf-8").read()
        spy_fields = kotlin_spec_fields(text)
        if spy_fields is None:
            failures.append("could not find SPEC_FIELDS in MediaSpy.kt -- the module no longer "
                            "declares the shape this fingerprint verifies")
        else:
            expect = [descriptor_to_reflect(d) for d in ds["field_types"]]
            if spy_fields != expect:
                failures.append(
                    "MediaSpy SPEC_FIELDS %s does not match the measured host shape %s"
                    % (spy_fields, expect))
        # The finality clause is what separates the spec from its builder. If it is edited out,
        # the module matches both classes again and this gate is the only thing that would say so.
        if "isFinal" not in text:
            failures.append("MediaSpy no longer tests field finality -- it would match "
                            "DataSpec.Builder too, which declares an identical field sequence")

    print("host fingerprint: %s (%d bytes, %d classes)"
          % (fp["host"]["apk_sha256"][:16], fp["host"]["apk_bytes"], fp["host"]["class_count"]))
    print("dataspec: %s  uri=%s  all_final=%s  builder=%s"
          % (ds["descriptor"], ds["uri_field"], ds["all_final"], ds["builder_present"]))
    print("construction sites: %d (%d in media3)" % (len(sites), len(media_sites)))

    if failures:
        print()
        for f in failures:
            print("FAIL: %s" % f)
        return 1
    print("\nmodule constants agree with the measured host")
    return 0


if __name__ == "__main__":
    sys.exit(main())
