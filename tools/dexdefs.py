"""Minimal dex reader: the definition tables, without instruction decoding.

Two callers share this, and both exist because dexdump in the build image is an x86_64 binary that
exits 126 with no output on an ARM host — an empty result indistinguishable from "not found", which
is exactly how a broken artifact gets shipped. A `strings` grep has the opposite failure: it matches
unrelated string constants.

  * ``dexcheck.py`` asks whether a class and its methods survived into the module's own APK.
  * ``verify_host_anchors.py`` asks whether the host X APK still has the shapes the module hooks.

Reading the tables directly is also what makes the host check usable: androguard builds a full
object graph per class and needs ~4½ minutes on X's 16-dex, 231k-class APK, versus under 10 seconds
here. No opcode widths are decoded anywhere in this file — only names, descriptors, superclass and
interfaces, which is all either caller inspects. Layout per
https://source.android.com/docs/core/runtime/dex-format.
"""
import struct
import zipfile

HEADER_MAGIC = b"dex\n"

# Offsets of the size/offset pairs in the dex header.
_HEADER_TABLES_OFF = 56


def read_uleb128(data, off):
    result = shift = 0
    while True:
        b = data[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, off
        shift += 7


class Dex:
    """Parsed definition tables of a single dex file."""

    def __init__(self, raw):
        if raw[:4] != HEADER_MAGIC:
            raise ValueError("not a dex file")
        (
            self.string_ids_size, self.string_ids_off,
            self.type_ids_size, self.type_ids_off,
            self.proto_ids_size, self.proto_ids_off,
            self.field_ids_size, self.field_ids_off,
            self.method_ids_size, self.method_ids_off,
            self.class_defs_size, self.class_defs_off,
        ) = struct.unpack_from("<12I", raw, _HEADER_TABLES_OFF)
        self.raw = raw
        self._strings = {}
        self._types = {}
        self._protos = {}

    # ---- primitive tables --------------------------------------------------

    def string(self, idx):
        cached = self._strings.get(idx)
        if cached is not None:
            return cached
        off = struct.unpack_from("<I", self.raw, self.string_ids_off + idx * 4)[0]
        _size, off = read_uleb128(self.raw, off)
        end = self.raw.index(b"\x00", off)
        val = self.raw[off:end].decode("utf-8", "replace")
        self._strings[idx] = val
        return val

    def type_(self, idx):
        if idx == 0xFFFFFFFF:
            return None
        cached = self._types.get(idx)
        if cached is not None:
            return cached
        sidx = struct.unpack_from("<I", self.raw, self.type_ids_off + idx * 4)[0]
        val = self.string(sidx)
        self._types[idx] = val
        return val

    def proto(self, idx):
        """``(return_type, [param_types])`` for a proto_id."""
        cached = self._protos.get(idx)
        if cached is not None:
            return cached
        _shorty, ret_idx, params_off = struct.unpack_from(
            "<3I", self.raw, self.proto_ids_off + idx * 12
        )
        params = []
        if params_off:
            size = struct.unpack_from("<I", self.raw, params_off)[0]
            for i in range(size):
                t = struct.unpack_from("<H", self.raw, params_off + 4 + i * 2)[0]
                params.append(self.type_(t))
        val = (self.type_(ret_idx), params)
        self._protos[idx] = val
        return val

    def field_id(self, idx):
        """``(owner_type, field_type, name)``"""
        cls_i, type_i, name_i = struct.unpack_from(
            "<HHI", self.raw, self.field_ids_off + idx * 8
        )
        return self.type_(cls_i), self.type_(type_i), self.string(name_i)

    def method_id(self, idx):
        """``(owner_type, name, return_type, [param_types])``"""
        cls_i, proto_i, name_i = struct.unpack_from(
            "<HHI", self.raw, self.method_ids_off + idx * 8
        )
        ret, params = self.proto(proto_i)
        return self.type_(cls_i), self.string(name_i), ret, params

    def method_ids(self):
        """Every ``(owner_type, name)`` in the method_ids table.

        Covers methods *referenced* as well as defined, which is what a presence check wants: a
        class kept only because something calls into it still shows up here.
        """
        for i in range(self.method_ids_size):
            cls_i, _proto_i, name_i = struct.unpack_from(
                "<HHI", self.raw, self.method_ids_off + i * 8
            )
            yield self.type_(cls_i), self.string(name_i)

    # ---- class defs --------------------------------------------------------

    def code_methods(self):
        """Yields ``(owner_type, method_name, code_off)`` for every method carrying bytecode.

        [classes] deliberately drops code offsets — it answers "what shape is this class" and nothing
        more. Reachability needs the instruction stream, so this exposes the offsets from the same
        walk rather than a second parser: two dex readers drifting apart is how a scan silently starts
        answering a different question than the shape check it is supposed to back up.
        """
        for i in range(self.class_defs_size):
            (
                class_idx, _flags, _super, _ifaces,
                _src, _annos, class_data_off, _statics,
            ) = struct.unpack_from("<8I", self.raw, self.class_defs_off + i * 32)
            if not class_data_off:
                continue
            owner = self.type_(class_idx)
            off = class_data_off
            n_static, off = read_uleb128(self.raw, off)
            n_inst, off = read_uleb128(self.raw, off)
            n_direct, off = read_uleb128(self.raw, off)
            n_virtual, off = read_uleb128(self.raw, off)

            for count in (n_static, n_inst):
                for _ in range(count):
                    _, off = read_uleb128(self.raw, off)   # field_idx_diff
                    _, off = read_uleb128(self.raw, off)   # access_flags

            for count in (n_direct, n_virtual):
                idx = 0
                for _ in range(count):
                    delta, off = read_uleb128(self.raw, off)
                    _access, off = read_uleb128(self.raw, off)
                    code_off, off = read_uleb128(self.raw, off)
                    idx += delta
                    if code_off:
                        _owner, mname, _ret, _params = self.method_id(idx)
                        yield owner, mname, code_off

    def classes(self):
        """Yields one dict per class_def: name, super, interfaces, fields, methods."""
        for i in range(self.class_defs_size):
            (
                class_idx, _flags, super_idx, interfaces_off,
                _src, _annos, class_data_off, _statics,
            ) = struct.unpack_from("<8I", self.raw, self.class_defs_off + i * 32)

            ifs = []
            if interfaces_off:
                n = struct.unpack_from("<I", self.raw, interfaces_off)[0]
                for k in range(n):
                    t = struct.unpack_from("<H", self.raw, interfaces_off + 4 + k * 2)[0]
                    ifs.append(self.type_(t))

            fields, methods = [], []
            if class_data_off:
                off = class_data_off
                n_static, off = read_uleb128(self.raw, off)
                n_inst, off = read_uleb128(self.raw, off)
                n_direct, off = read_uleb128(self.raw, off)
                n_virtual, off = read_uleb128(self.raw, off)

                for count, static in ((n_static, True), (n_inst, False)):
                    idx = 0
                    for _ in range(count):
                        delta, off = read_uleb128(self.raw, off)
                        _access, off = read_uleb128(self.raw, off)
                        idx += delta
                        _owner, ftype, fname = self.field_id(idx)
                        fields.append({"name": fname, "type": ftype, "static": static})

                for count in (n_direct, n_virtual):
                    idx = 0
                    for _ in range(count):
                        delta, off = read_uleb128(self.raw, off)
                        access, off = read_uleb128(self.raw, off)
                        _code, off = read_uleb128(self.raw, off)
                        idx += delta
                        _owner, mname, ret, params = self.method_id(idx)
                        methods.append({
                            "name": mname, "ret": ret, "params": params,
                            "static": bool(access & 0x8),
                        })

            yield {
                "name": self.type_(class_idx),
                "super": self.type_(super_idx),
                "interfaces": ifs,
                "fields": fields,
                "methods": methods,
            }


def dex_blobs(path):
    """Raw dex bytes from an APK, or from a bare .dex file."""
    if path.endswith(".dex"):
        with open(path, "rb") as fh:
            return [(path.rsplit("/", 1)[-1], fh.read())]
    out = []
    with zipfile.ZipFile(path) as z:
        for entry in sorted(n for n in z.namelist() if n.endswith(".dex")):
            out.append((entry, z.read(entry)))
    if not out:
        raise ValueError("%s contains no dex files" % path)
    return out


def load_classes(path):
    """All class definitions in an APK or dex, as ``name -> class dict``."""
    out = {}
    for _entry, raw in dex_blobs(path):
        for c in Dex(raw).classes():
            out[c["name"]] = c
    if not out:
        raise ValueError("%s yielded no classes" % path)
    return out
