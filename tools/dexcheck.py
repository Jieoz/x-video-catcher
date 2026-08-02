#!/usr/bin/env python3
"""Verify a class and its methods really exist in a dex, by parsing the dex itself.

Written because dexdump in this image is an x86_64 binary and silently produced no
output on this ARM host (Exec format error) — its empty result looked exactly like
"class not found", which is the kind of false negative that gets a broken artifact
shipped. A `strings` grep is the opposite failure: it matches an unrelated string
constant. This walks the method_ids table, so a hit means the method is genuinely
defined or referenced with that class as its owner.
"""
import struct
import sys


def read_uleb128(data, off):
    result = shift = 0
    while True:
        b = data[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, off
        shift += 7


def parse(path):
    d = open(path, "rb").read()
    if d[:4] not in (b"dex\n",):
        raise SystemExit(f"{path}: not a dex file")

    string_ids_size, string_ids_off = struct.unpack_from("<II", d, 0x38)
    type_ids_size, type_ids_off = struct.unpack_from("<II", d, 0x40)
    proto_ids_size, proto_ids_off = struct.unpack_from("<II", d, 0x48)
    _field_size, _field_off = struct.unpack_from("<II", d, 0x50)
    method_ids_size, method_ids_off = struct.unpack_from("<II", d, 0x58)

    strings = []
    for i in range(string_ids_size):
        (data_off,) = struct.unpack_from("<I", d, string_ids_off + 4 * i)
        _n, p = read_uleb128(d, data_off)
        end = d.index(b"\x00", p)
        strings.append(d[p:end].decode("utf-8", "replace"))

    types = []
    for i in range(type_ids_size):
        (idx,) = struct.unpack_from("<I", d, type_ids_off + 4 * i)
        types.append(strings[idx])

    methods = []
    for i in range(method_ids_size):
        cls, proto, name = struct.unpack_from("<HHI", d, method_ids_off + 8 * i)
        methods.append((types[cls], strings[name]))
    return methods


want_class = sys.argv[1]
want_methods = sys.argv[2].split(",")
found = {}
for path in sys.argv[3:]:
    for owner, name in parse(path):
        if want_class in owner:
            found.setdefault(name, set()).add(path.rsplit("/", 1)[-1])

print(f"class matching {want_class!r}: {'FOUND' if found else 'NOT FOUND'}")
missing = []
for m in want_methods:
    where = found.get(m)
    if where:
        print(f"  ok       {m}  ({', '.join(sorted(where))})")
    else:
        print(f"  MISSING  {m}")
        missing.append(m)

print(f"\nall methods in that class: {len(found)}")
for m in sorted(found):
    print("   ", m)
sys.exit(1 if missing else 0)
