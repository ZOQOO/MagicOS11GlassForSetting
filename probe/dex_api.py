#!/usr/bin/env python3
"""Dump real class/method/field signatures from a DEX inside a jar/apk/vdex.

Unlike string-grep scanners this parses DEX tables (string/type/proto/method/
class_def/class_data), so it tells you WHICH class owns a method and its exact
argument types -- exactly what a reflection bridge needs.

Usage:
  python3 dex_api.py <file.jar|apk|dex|vdex> <class-substring> [--methods-only]
  python3 dex_api.py <file> --list <class-substring>

Examples:
  python3 dex_api.py framework.jar android/graphics/material
  python3 dex_api.py Settings.apk com/hihonor/uikit/transparency
  python3 dex_api.py framework-magic.jar HnVisualEffect

Read-only. No Android SDK required.
"""
import io
import struct
import sys
import zipfile


def u16(d, p):
    return struct.unpack_from("<H", d, p)[0]


def u32(d, p):
    return struct.unpack_from("<I", d, p)[0]


def uleb(d, p):
    result, shift = 0, 0
    while p < len(d):
        b = d[p]
        p += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            break
        shift += 7
        if shift > 35:
            break
    return result, p


class Dex:
    def __init__(self, data):
        self.d = data
        d = data
        if d[:4] != b"dex\n":
            raise ValueError("not a dex")
        (self.n_str, self.o_str) = struct.unpack_from("<II", d, 56)
        (self.n_type, self.o_type) = struct.unpack_from("<II", d, 64)
        (self.n_proto, self.o_proto) = struct.unpack_from("<II", d, 72)
        (self.n_field, self.o_field) = struct.unpack_from("<II", d, 80)
        (self.n_method, self.o_method) = struct.unpack_from("<II", d, 88)
        (self.n_class, self.o_class) = struct.unpack_from("<II", d, 96)
        self._str_cache = {}

    def string(self, i):
        if i in self._str_cache:
            return self._str_cache[i]
        if i >= self.n_str:
            return "<bad-str-%d>" % i
        p = u32(self.d, self.o_str + 4 * i)
        length, p = uleb(self.d, p)
        try:
            s = self.d[p:p + length].decode("utf-8", "ignore")
        except Exception:
            s = ""
        self._str_cache[i] = s
        return s

    def type(self, i):
        return self.string(u32(self.d, self.o_type + 4 * i)) if i < self.n_type else "?"

    def proto(self, i):
        if i >= self.n_proto:
            return "?"
        # proto_id_item = { uint shorty_idx; uint return_type_idx; uint parameters_off; }
        p = self.o_proto + 12 * i
        ret = self.type(u32(self.d, p + 4))
        off = u32(self.d, p + 8)
        args = []
        if off and off + 4 <= len(self.d):
            n = u32(self.d, off)
            for k in range(min(n, 32)):
                if off + 4 + 2 * k + 2 > len(self.d):
                    break
                args.append(self.type(u16(self.d, off + 4 + 2 * k)))
        return "(" + ",".join(args) + ")" + ret

    def method(self, i):
        if i >= self.n_method:
            return None
        p = self.o_method + 8 * i
        cls = self.type(u16(self.d, p))
        proto = self.proto(u16(self.d, p + 2))
        name = self.string(u32(self.d, p + 4))
        return (cls, name, proto)

    def field(self, i):
        if i >= self.n_field:
            return None
        p = self.o_field + 8 * i
        return (self.type(u16(self.d, p)), self.string(u32(self.d, p + 4)),
                self.type(u16(self.d, p + 2)))

    def classes(self):
        for c in range(min(self.n_class, 100000)):
            p = self.o_class + 32 * c
            if p + 32 > len(self.d):
                break
            class_idx = u32(self.d, p)
            class_data_off = u32(self.d, p + 24)
            yield self.type(class_idx), class_data_off

    def class_members(self, class_data_off):
        """Return (methods, fields) as formatted signature strings."""
        d, methods, fields = self.d, [], []
        if not class_data_off or class_data_off >= len(d):
            return methods, fields
        p = class_data_off
        ns, p = uleb(d, p)
        ni, p = uleb(d, p)
        nd, p = uleb(d, p)
        nv, p = uleb(d, p)

        # Each of the four groups starts its *_idx_diff accumulation from 0.
        def skip_fields(count, collect):
            nonlocal p
            idx = 0
            for _ in range(min(count, 20000)):
                diff, p = uleb(d, p)
                _, p = uleb(d, p)      # access flags
                idx += diff
                if collect:
                    f = self.field(idx)
                    if f:
                        fields.append("%s %s : %s" % (f[0], f[1], f[2]))

        def read_methods(count):
            nonlocal p
            idx = 0
            for _ in range(min(count, 20000)):
                diff, p = uleb(d, p)
                _, p = uleb(d, p)      # access flags
                _, p = uleb(d, p)      # code_off
                idx += diff
                m = self.method(idx)
                if m:
                    methods.append("%s %s%s" % (m[0], m[1], m[2]))

        skip_fields(ns, True)
        skip_fields(ni, True)
        read_methods(nd)
        read_methods(nv)
        return methods, fields


def iter_dex(data):
    if data[:2] == b"PK":
        try:
            with zipfile.ZipFile(io.BytesIO(data)) as z:
                for name in sorted(z.namelist()):
                    if name.endswith(".dex"):
                        blob = z.read(name)
                        if blob[:4] == b"dex\n":
                            yield name, blob
        except (zipfile.BadZipFile, RuntimeError, KeyError):
            pass
    elif data[:4] == b"dex\n":
        yield "<raw>", data
    else:
        pos, found = 0, 0
        while found < 40:
            pos = data.find(b"dex\n", pos)
            if pos < 0 or pos + 40 > len(data):
                break
            size = u32(data, pos + 32)
            if 64 <= size <= len(data) - pos:
                yield "embedded@%d" % pos, data[pos:pos + size]
                found += 1
                pos += size
            else:
                pos += 4


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    path, needle = sys.argv[1], sys.argv[2]
    needles = [n for n in needle.split(",") if n]
    methods_only = "--methods-only" in sys.argv
    # --method <substr>: reverse lookup, print the class that OWNS the method.
    method_needle = None
    if "--method" in sys.argv:
        i = sys.argv.index("--method")
        method_needle = sys.argv[i + 1] if i + 1 < len(sys.argv) else ""
    data = open(path, "rb").read()
    total = 0
    for dexname, blob in iter_dex(data):
        try:
            dex = Dex(blob)
        except Exception as e:
            print("!! %s: %s" % (dexname, e))
            continue
        for cls, off in dex.classes():
            methods, fields = dex.class_members(off)
            if method_needle is not None:
                matched = [m for m in sorted(set(methods))
                           if method_needle.lower() in m.lower()]
                if not matched:
                    continue
                print("\n### %s   [%s]" % (cls, dexname))
                for m in matched[:40]:
                    print("  METHOD %s" % m)
                total += 1
                continue
            if not any(n in cls for n in needles):
                continue
            print("\n### %s   [%s]" % (cls, dexname))
            if not methods_only:
                for f in sorted(set(fields))[:40]:
                    print("  FIELD  %s" % f)
            for m in sorted(set(methods))[:120]:
                print("  METHOD %s" % m)
            total += 1
    print("\nmatched classes: %d  (needle=%r, file=%s)" % (total, needle, path))


if __name__ == "__main__":
    main()
