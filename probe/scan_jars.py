#!/usr/bin/env python3
"""Offline scan of framework jars / APKs / VDEX for Honor transparency SDK traces.

Modes:
  python3 scan_jars.py <dir>                 scan every .jar/.apk/.dex/.vdex in a dir
  python3 scan_jars.py --stdin <label>       read ONE file from stdin (streaming,
                                             nothing is written to disk)

The --stdin mode is used by adb_probe.sh together with `adb exec-out cat <path>`,
which keeps the whole probe strictly read-only on the phone side and avoids
copying hundreds of megabytes of framework jars to the PC.

Read-only. Minimal DEX string-id parser; no Android SDK required.
"""
import io
import os
import struct
import sys
import zipfile

PATTERNS = (
    # Honor official translucent UI kit
    "com/hihonor/uikit/transparency",
    "com.hihonor.uikit.transparency",
    "HnVisualEffectLayout", "HnVisualEffect", "HnGlassEffect",
    "HnGradientBlurEffect", "HnColorPickEffect",
    "HnMaterialType", "HnSceneType", "HnWidgetSize",
    "setFollowSystemSettings", "setTransparencyLevel", "setVisualEffect",
    "setSkipRenderLayer", "setInteractive", "setBound",
    "TRANSPARENCY_MAIN_SWITCH", "transparency_material",
    "system_current_transparent_mode", "config_dynamic_transparency_switch",
    # Honor Settings view-tree signals
    "HnGradientBlurTopContainer", "HnBlurTopPattern", "HnListCardLayout",
    "CardItemDecoration", "FrameDecoration",
    # MIUI / HyperOS private API (must be ABSENT in the new module)
    "setMiGlass", "setMiViewMaterialType", "setMiBackgroundBlurMode",
    "setMiViewBlurMode",
    # Third-party liquid glass references
    "RuntimeShader", "LiquidGlass", "Backdrop",
)


def dex_strings(data):
    """Parse the string_id table of a raw DEX file."""
    if len(data) < 64 or data[:4] != b"dex\n":
        return []
    count, off = struct.unpack_from("<II", data, 56)
    if off + 4 * count > len(data):
        return []
    out = []
    for i in range(count):
        p = struct.unpack_from("<I", data, off + 4 * i)[0]
        if p >= len(data):
            continue
        q, length, shift = p, 0, 0
        while q < len(data):
            b = data[q]
            q += 1
            length |= (b & 0x7F) << shift
            if not (b & 0x80):
                break
            shift += 7
            if shift > 35:
                break
        if length > 4096:
            continue
        try:
            out.append(data[q:q + length].decode("utf-8", "ignore"))
        except Exception:
            pass
    return out


def collect(strings, hits):
    for s in strings:
        for pat in PATTERNS:
            if pat in s:
                hits.add((pat, s[:140]))


def raw_pattern_hits(data, hits):
    """Fallback for VDEX/ELF blobs: DEX strings are plain MUTF-8 bytes."""
    for pat in PATTERNS:
        needle = pat.encode("utf-8")
        pos = data.find(needle)
        if pos >= 0:
            lo, hi = max(0, pos - 60), min(len(data), pos + 90)
            snippet = data[lo:hi].decode("utf-8", "ignore")
            hits.add((pat, snippet.replace("\x00", ".")))


def embedded_dex_hits(data, hits):
    """VDEX/ART containers embed whole DEX files; slice them at each magic."""
    pos, found = 0, 0
    while found < 40:
        pos = data.find(b"dex\n", pos)
        if pos < 0 or pos + 40 > len(data):
            break
        size = struct.unpack_from("<I", data, pos + 32)[0]
        if 64 <= size <= len(data) - pos:
            collect(dex_strings(data[pos:pos + size]), hits)
            found += 1
            pos += size
        else:
            pos += 4


def scan_bytes(data, label):
    """Return (kind, hits) for one in-memory file."""
    hits = set()
    kind = "raw"
    if data[:2] == b"PK":
        kind = "zip"
        try:
            with zipfile.ZipFile(io.BytesIO(data)) as z:
                for name in z.namelist():
                    if not name.endswith(".dex"):
                        continue
                    blob = z.read(name)
                    if len(blob) <= 80 * 1024 * 1024:
                        collect(dex_strings(blob), hits)
                        embedded_dex_hits(blob, hits)
        except (zipfile.BadZipFile, OSError, IndexError, struct.error, KeyError, RuntimeError):
            raw_pattern_hits(data, hits)
    elif data[:4] == b"dex\n":
        kind = "dex"
        collect(dex_strings(data), hits)
    elif data[:4] in (b"vdex", b"oat\n") or b"dex\n" in data[:4096]:
        kind = "vdex"
        embedded_dex_hits(data, hits)
    raw_pattern_hits(data, hits)
    return kind, hits


def print_hits(label, kind, size, hits, quiet=False):
    if not hits and quiet:
        return
    print("### %s  [%s, %d bytes]  %d hits" % (label, kind, size, len(hits)))
    for pat, s in sorted(hits)[:60]:
        print("  [%s] %s" % (pat, s.strip()))


def scan_file(path):
    try:
        with open(path, "rb") as f:
            data = f.read()
    except OSError:
        return
    kind, hits = scan_bytes(data, path)
    if hits:
        print_hits(path, kind, len(data), hits)


def main():
    args = sys.argv[1:]
    if args and args[0] == "--stdin":
        label = args[1] if len(args) > 1 else "<stdin>"
        data = sys.stdin.buffer.read()
        kind, hits = scan_bytes(data, label)
        print_hits(label, kind, len(data), hits, quiet=True)
        return
    root = args[0] if args else "."
    files = []
    for base, _, names in os.walk(root):
        for n in names:
            if n.endswith((".jar", ".apk", ".dex", ".vdex")):
                files.append(os.path.join(base, n))
    print("scanning %d files under %s" % (len(files), root))
    total = 0
    for f in sorted(files):
        try:
            size = os.path.getsize(f)
        except OSError:
            continue
        try:
            data = open(f, "rb").read()
        except OSError:
            continue
        kind, hits = scan_bytes(data, f)
        if hits:
            total += 1
            print_hits(f, kind, size, hits)
    print("\nfiles with hits: %d" % total)
    if total == 0:
        print("No SDK traces found. Check app APKs and rely on in-process ProbeEntry.")


if __name__ == "__main__":
    main()
