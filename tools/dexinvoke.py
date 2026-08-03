#!/usr/bin/env python3
"""Instruction-level invoke-xref scanner over raw dex, no androguard.

androguard builds a full object graph for every method in the dex, which is why the earlier
whole-package run took 4m33 for a 9.8s job. For "who invokes method M" we only need:

  * method_ids table          -> resolve an invoke's BBBB operand to class+name+proto
  * class_defs -> class_data  -> each method's code_item offset
  * code_item insns           -> scan 3-unit invoke-kind opcodes and read their method index

That is a linear pass over the instruction bytes with no per-method object allocation.

Invoke opcodes (all format 35c/3rc, 3 code units, method idx in the 2nd unit):
  0x6e invoke-virtual   0x6f invoke-super     0x70 invoke-direct
  0x71 invoke-static    0x72 invoke-interface
  0x74..0x78 the /range variants
"""
import collections
import sys
import struct
import zipfile

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from dexdefs import Dex

INVOKE = {0x6E, 0x6F, 0x70, 0x71, 0x72, 0x74, 0x75, 0x76, 0x77, 0x78}

# new-instance vAA, type@BBBB -- format 21c, 2 code units, type idx in the 2nd unit.
NEW_INSTANCE = 0x22

# invoke-super and its /range form. A super call is the *callee* reaching up into its own parent, so
# it is never evidence that anything outside the class can enter it.
INVOKE_SUPER = {0x6F, 0x75}


class CallSite(collections.namedtuple(
        "CallSite", "caller_class caller_method target_class target_method opcode dex")):
    """One invoke instruction, kept structured rather than pre-formatted.

    An earlier version returned display strings and the reachability gate had to decide "is this a
    super call from the anchor itself" by parsing them back apart. Formatting is not an interface:
    the caller identity and the opcode are what the verdict depends on, so they stay as fields.
    """

    __slots__ = ()

    def is_self_super(self):
        """True when this is the target's own subclass calling ``super.m()`` up into it.

        Such a call proves only that the override delegates upward; it says nothing about whether
        anything can reach the override in the first place.
        """
        return self.opcode in INVOKE_SUPER

    def __str__(self):
        return "%s.%s -> %s.%s  [%s]" % (
            pretty(self.caller_class), self.caller_method,
            pretty(self.target_class), self.target_method, self.dex,
        )


class ScanError(Exception):
    """Raised when instruction walking fails, so a decode bug cannot masquerade as "no callers"."""


class InvokeDex(Dex):
    """[Dex] plus an instruction walker. The class-data walk lives in dexdefs.code_methods."""

    def method_ref(self, idx):
        """``(class_desc, name)`` for a method_ids entry, tolerating a truncated proto."""
        cls, name, _ret, _params = self.method_id(idx)
        return (cls, name, None)

    def invokes_in(self, code_off):
        """Yield method_ids indices invoked by the code_item at code_off."""
        for _op, idx in self.invoke_ops_in(code_off):
            yield idx

    def invoke_ops_in(self, code_off):
        """Yield ``(opcode, method_ids index)`` for each invoke in the code_item at code_off.

        The opcode matters to callers that must tell invoke-super apart from an ordinary call.
        """
        for op, idx in self._refs_in(code_off):
            if op in INVOKE:
                yield op, idx

    def new_instances_in(self, code_off):
        """Yield type_ids indices instantiated by the code_item at code_off."""
        for op, idx in self._refs_in(code_off):
            if op == NEW_INSTANCE:
                yield idx

    def _refs_in(self, code_off):
        """Yield ``(opcode, operand_idx)`` for every invoke-kind and new-instance instruction.

        Both consumers need the same instruction walk over the same bytes; two separate walkers
        would be two places for the length table to drift.
        """
        insns_size = struct.unpack_from("<I", self.raw, code_off + 12)[0]
        base = code_off + 16
        i = 0
        raw = self.raw
        while i < insns_size:
            unit = struct.unpack_from("<H", raw, base + i * 2)[0]
            op = unit & 0xFF
            if op in INVOKE:
                yield op, struct.unpack_from("<H", raw, base + (i + 1) * 2)[0]
                i += 3
                continue
            if op == NEW_INSTANCE:
                yield op, struct.unpack_from("<H", raw, base + (i + 1) * 2)[0]
                i += 2
                continue
            i += _insn_len(raw, base, i, op)
        return

def _insn_len(raw, base, i, op):
    """Length in code units of the instruction at index i."""
    if op == 0x00:
        # nop / packed payloads
        unit = struct.unpack_from("<H", raw, base + i * 2)[0]
        ident = unit >> 8
        if ident == 0x01:      # packed-switch-payload
            size = struct.unpack_from("<H", raw, base + (i + 1) * 2)[0]
            return 4 + size * 2
        if ident == 0x02:      # sparse-switch-payload
            size = struct.unpack_from("<H", raw, base + (i + 1) * 2)[0]
            return 2 + size * 4
        if ident == 0x03:      # fill-array-data-payload
            width = struct.unpack_from("<H", raw, base + (i + 1) * 2)[0]
            size = struct.unpack_from("<I", raw, base + (i + 2) * 2)[0]
            return 4 + (size * width + 1) // 2
        return 1
    return _LEN[op]


# opcode -> length in 16-bit code units (Dalvik spec)
_LEN = [1] * 256
for _o in range(0x00, 0x100):
    _LEN[_o] = 1
for _o, _l in {
    0x01: 1, 0x02: 2, 0x03: 3, 0x04: 1, 0x05: 2, 0x06: 3, 0x07: 1, 0x08: 2, 0x09: 3,
    0x0A: 1, 0x0B: 1, 0x0C: 1, 0x0D: 1, 0x0E: 1, 0x0F: 1, 0x10: 1, 0x11: 1,
    0x12: 1, 0x13: 2, 0x14: 3, 0x15: 2, 0x16: 2, 0x17: 3, 0x18: 5, 0x19: 2,
    0x1A: 2, 0x1B: 3, 0x1C: 2, 0x1D: 1, 0x1E: 1, 0x1F: 2, 0x20: 2, 0x21: 1,
    0x22: 2, 0x23: 2, 0x24: 3, 0x25: 3, 0x26: 3, 0x27: 1, 0x28: 1, 0x29: 2, 0x2A: 3,
    0x2B: 3, 0x2C: 3, 0x2D: 2, 0x2E: 2, 0x2F: 2, 0x30: 2, 0x31: 2,
    0x32: 2, 0x33: 2, 0x34: 2, 0x35: 2, 0x36: 2, 0x37: 2, 0x38: 2, 0x39: 2,
    0x3A: 2, 0x3B: 2, 0x3C: 2, 0x3D: 2,
    0x44: 2, 0x45: 2, 0x46: 2, 0x47: 2, 0x48: 2, 0x49: 2, 0x4A: 2, 0x4B: 2, 0x4C: 2,
    0x4D: 2, 0x4E: 2, 0x4F: 2, 0x50: 2, 0x51: 2,
    0x52: 2, 0x53: 2, 0x54: 2, 0x55: 2, 0x56: 2, 0x57: 2, 0x58: 2, 0x59: 2, 0x5A: 2,
    0x5B: 2, 0x5C: 2, 0x5D: 2, 0x5E: 2, 0x5F: 2, 0x60: 2, 0x61: 2, 0x62: 2, 0x63: 2,
    0x64: 2, 0x65: 2, 0x66: 2, 0x67: 2, 0x68: 2, 0x69: 2, 0x6A: 2, 0x6B: 2, 0x6C: 2,
    0x6D: 2,
    0x6E: 3, 0x6F: 3, 0x70: 3, 0x71: 3, 0x72: 3,
    0x74: 3, 0x75: 3, 0x76: 3, 0x77: 3, 0x78: 3,
    0x7B: 1, 0x7C: 1, 0x7D: 1, 0x7E: 1, 0x7F: 1, 0x80: 1, 0x81: 1, 0x82: 1, 0x83: 1,
    0x84: 1, 0x85: 1, 0x86: 1, 0x87: 1, 0x88: 1, 0x89: 1, 0x8A: 1, 0x8B: 1, 0x8C: 1,
    0x8D: 1, 0x8E: 1, 0x8F: 1,
    0x90: 2, 0x91: 2, 0x92: 2, 0x93: 2, 0x94: 2, 0x95: 2, 0x96: 2, 0x97: 2, 0x98: 2,
    0x99: 2, 0x9A: 2, 0x9B: 2, 0x9C: 2, 0x9D: 2, 0x9E: 2, 0x9F: 2, 0xA0: 2, 0xA1: 2,
    0xA2: 2, 0xA3: 2, 0xA4: 2, 0xA5: 2, 0xA6: 2, 0xA7: 2, 0xA8: 2, 0xA9: 2, 0xAA: 2,
    0xAB: 2, 0xAC: 2, 0xAD: 2, 0xAE: 2, 0xAF: 2,
    0xB0: 1, 0xB1: 1, 0xB2: 1, 0xB3: 1, 0xB4: 1, 0xB5: 1, 0xB6: 1, 0xB7: 1, 0xB8: 1,
    0xB9: 1, 0xBA: 1, 0xBB: 1, 0xBC: 1, 0xBD: 1, 0xBE: 1, 0xBF: 1, 0xC0: 1, 0xC1: 1,
    0xC2: 1, 0xC3: 1, 0xC4: 1, 0xC5: 1, 0xC6: 1, 0xC7: 1, 0xC8: 1, 0xC9: 1, 0xCA: 1,
    0xCB: 1, 0xCC: 1, 0xCD: 1, 0xCE: 1, 0xCF: 1,
    0xD0: 2, 0xD1: 2, 0xD2: 2, 0xD3: 2, 0xD4: 2, 0xD5: 2, 0xD6: 2, 0xD7: 2,
    0xD8: 2, 0xD9: 2, 0xDA: 2, 0xDB: 2, 0xDC: 2, 0xDD: 2, 0xDE: 2, 0xDF: 2,
    0xE0: 2, 0xE1: 2, 0xE2: 2,
    0xFA: 4, 0xFB: 4, 0xFC: 3, 0xFD: 3, 0xFE: 2, 0xFF: 2,
}.items():
    _LEN[_o] = _l


def pretty(d):
    if d and d.startswith("L") and d.endswith(";"):
        return d[1:-1].replace("/", ".")
    return str(d)


def find_callers(apk, targets, dexes=None, max_errors=0):
    """Find every call site of each target.

    targets: list of (class_desc, method_name or None). Returns {target: [caller strings]}.

    Counts call sites by the class named *at the call site*, which is the declared type, not the
    runtime one. An override therefore shows zero here even when the host calls it constantly --
    see check_reachability for the virtual-dispatch handling built on top of this.

    Raises ScanError if instruction walking fails anywhere. That strictness is the point: a scanner
    that swallows decode errors reports "0 callers" for a *parse* bug exactly as it does for genuinely
    dead code, and "0 callers" is the signal this tool exists to produce. Silence there is what let
    three releases ship against an anchor nothing calls. Pass max_errors to tolerate a known-bad dex
    deliberately.
    """
    out = {t: [] for t in targets}
    errors = []
    with zipfile.ZipFile(apk) as z:
        names = sorted(n for n in z.namelist() if n.endswith(".dex"))
        if dexes:
            names = [n for n in names if n in dexes]
        if not names:
            raise ScanError("no .dex entries in %s" % apk)
        for entry in names:
            d = InvokeDex(z.read(entry))
            # Pre-resolve which method_ids indices matter, so the instruction pass is a plain
            # integer lookup per invoke rather than a string comparison.
            want = {}
            for i in range(d.method_ids_size):
                cls, name, _ = d.method_ref(i)
                for t in targets:
                    tc, tn = t
                    if cls == tc and (tn is None or name == tn):
                        want[i] = t
            if not want:
                continue
            for cls, mname, code_off in d.code_methods():
                try:
                    for op, midx in d.invoke_ops_in(code_off):
                        t = want.get(midx)
                        if t is not None:
                            _, tname, _ = d.method_ref(midx)
                            out[t].append(CallSite(
                                caller_class=cls,
                                caller_method=mname,
                                target_class=t[0],
                                target_method=tname,
                                opcode=op,
                                dex=entry,
                            ))
                except Exception as exc:
                    errors.append("%s %s.%s: %s" % (entry, pretty(cls), mname, exc))
    if len(errors) > max_errors:
        raise ScanError(
            "instruction walk failed in %d method(s); a zero-caller result would be "
            "indistinguishable from a decode bug. First: %s" % (len(errors), errors[0])
        )
    return out


def find_instantiations(apk, types, dexes=None, max_errors=0):
    """Find every ``new-instance`` site for each type descriptor.

    Returns ``{type_desc: [site strings]}``.

    This is the other half of proving an override is live. Call sites name the declared type, so a
    subclass method that overrides an interface or superclass method has no call site of its own; the
    question that actually decides whether it runs is whether anything ever constructs that subclass.
    Without this, the reachability gate rejects every override -- which is precisely what it did to
    two correct anchors.

    Same strictness on decode errors as find_callers, and for the same reason: a silent zero is
    indistinguishable from a parse bug.
    """
    out = {t: [] for t in types}
    errors = []
    with zipfile.ZipFile(apk) as z:
        names = sorted(n for n in z.namelist() if n.endswith(".dex"))
        if dexes:
            names = [n for n in names if n in dexes]
        if not names:
            raise ScanError("no .dex entries in %s" % apk)
        for entry in names:
            d = InvokeDex(z.read(entry))
            want = {}
            for i in range(d.type_ids_size):
                desc = d.type_(i)
                if desc in out:
                    want[i] = desc
            if not want:
                continue
            for cls, mname, code_off in d.code_methods():
                try:
                    for tidx in d.new_instances_in(code_off):
                        desc = want.get(tidx)
                        if desc is not None:
                            out[desc].append(
                                "%s.%s -> new %s  [%s]" % (pretty(cls), mname, pretty(desc), entry)
                            )
                except Exception as exc:
                    errors.append("%s %s.%s: %s" % (entry, pretty(cls), mname, exc))
    if len(errors) > max_errors:
        raise ScanError(
            "instruction walk failed in %d method(s); a zero-instantiation result would be "
            "indistinguishable from a decode bug. First: %s" % (len(errors), errors[0])
        )
    return out
