#!/usr/bin/env python3
"""Method-level disassembly of the MagicOS in-app transparency stack.

Read-only, offline, no Android SDK and NO device commands. Consumes the APKs
already pulled into probe/probe-report/offline/.

Goal: replace four assumptions in the module with facts.
  1. Does an EMPTY HnTransFrameLayout (no children) actually draw glass?
     -> follow dispatchDraw() into the mediator and see what it invokes.
  2. What do the obfuscated StyleType / SceneType enum constants mean?
     -> dump <clinit> and every static field, plus all call sites that pass
        a concrete enum constant.
  3. How does HnMaterialAggregator choose a material for a View?
  4. What are the real numeric ranges in the liquid/frosted material repos?

Usage: python3 dex_deep.py [apk-path] [out-txt]
"""
import os
import sys
import zipfile
from collections import defaultdict

from loguru import logger

logger.remove()
from androguard.core.dex import DEX  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_APK = os.path.join(HERE, "probe-report", "offline", "com.android.settings.apk")
DEFAULT_OUT = os.path.join(HERE, "probe-report", "deep_uikit.txt")

OUT = []


def emit(*parts):
    line = " ".join(str(p) for p in parts)
    OUT.append(line)
    print(line)


def load_dexes(path):
    out = []
    with zipfile.ZipFile(path) as z:
        for name in sorted(n for n in z.namelist() if n.endswith(".dex")):
            blob = z.read(name)
            if blob[:4] == b"dex\n":
                out.append((name, DEX(blob)))
    return out


def short(desc):
    return desc.replace("Lcom/hihonor/uikit/transparency/", "~")


def ins_lines(m, limit=400):
    if m.get_code() is None:
        return ["    <no code>"]
    lines = []
    for i, ins in enumerate(m.get_instructions()):
        if i >= limit:
            lines.append("    ... truncated")
            break
        lines.append("    %04d  %-24s %s" % (i, ins.get_name(), ins.get_output()))
    return lines


def dump_method(m, label=None):
    emit("  -- %s %s%s" % (label or "", m.get_name(), short(m.get_descriptor())))
    for l in ins_lines(m):
        emit(l)


def find_classes(dexes, predicate):
    for dexname, d in dexes:
        for c in d.get_classes():
            if predicate(c.get_name()):
                yield dexname, d, c


def main():
    apk = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_APK
    out = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_OUT
    emit("SOURCE", apk)
    dexes = load_dexes(apk)
    emit("DEXES", ", ".join(n for n, _ in dexes))

    # ------------------------------------------------------------------ 1
    emit("")
    emit("=" * 78)
    emit("SECTION 1  HnTrans* container: full method bodies")
    emit("=" * 78)
    mediator_names = set()
    for dexname, d, c in find_classes(
            dexes, lambda n: "/widget/HnTransFrameLayout;" in n
            or "/widget/HnTransLinearLayout;" in n
            or "/widget/HnTransRelativeLayout;" in n):
        emit("")
        emit("### CLASS %s   [%s]  super=%s" % (c.get_name(), dexname,
                                               c.get_superclassname()))
        for m in c.get_methods():
            dump_method(m)
            for ins in (m.get_instructions() if m.get_code() else []):
                o = ins.get_output()
                if "Luo/" in o or "Mediator" in o:
                    mediator_names.add(o)

    # mediator class: find by return type seen in getMediator()
    emit("")
    emit("--- mediator candidates discovered from signatures ---")
    for n in sorted(mediator_names):
        emit("   ", n[:200])

    emit("")
    emit("=" * 78)
    emit("SECTION 2  mediator / style classes actually doing the drawing")
    emit("=" * 78)
    med_prefixes = ("Luo/", "Lcom/hihonor/uikit/transparency/style/",
                    "Lcom/hihonor/uikit/transparency/adapters/",
                    "Lcom/hihonor/uikit/transparency/effect/")
    seen = 0
    for dexname, d, c in find_classes(
            dexes, lambda n: any(n.startswith(p) for p in med_prefixes)):
        name = c.get_name()
        methods = list(c.get_methods())
        interesting = [m for m in methods if m.get_name() in (
            "dispatchDraw", "draw", "onDraw", "a", "b", "c", "d", "e",
            "<clinit>", "<init>", "getHnMaterial", "getHnMaterialOpt",
            "setViewStyleType", "setViewStyleWithScenes", "init", "update",
            "apply", "onConfigurationChanged", "setBlurType")]
        if not interesting:
            continue
        seen += 1
        if seen > 90:
            break
        emit("")
        emit("### CLASS %s   [%s]  super=%s" % (name, dexname,
                                               c.get_superclassname()))
        emit("  FIELDS:")
        for f in c.get_fields():
            emit("    %s %s : %s" % (f.get_name(), short(f.get_descriptor()),
                                    hex(f.get_access_flags())))
        for m in interesting:
            if m.get_code() is not None and len(list(m.get_instructions())) > 220:
                emit("  -- %s%s  <LARGE %d ins, skimmed>" % (
                    m.get_name(), short(m.get_descriptor()),
                    len(list(m.get_instructions()))))
                continue
            dump_method(m)

    # ------------------------------------------------------------------ 2
    emit("")
    emit("=" * 78)
    emit("SECTION 3  Enum constants: fields + <clinit>")
    emit("=" * 78)
    enum_names = ("style/StyleType;", "effect/SceneType;",
                  "effect/ShapeType;", "effect/ViewEffectType;",
                  "style/AbsViewStyle;")
    for dexname, d, c in find_classes(
            dexes, lambda n: any(n.endswith(x) for x in enum_names)):
        emit("")
        emit("### ENUM %s   [%s]  super=%s" % (c.get_name(), dexname,
                                              c.get_superclassname()))
        for f in c.get_fields():
            emit("  FIELD %-24s %s  flags=%s" % (
                f.get_name(), short(f.get_descriptor()),
                hex(f.get_access_flags())))
        for m in c.get_methods():
            if m.get_name() in ("<clinit>", "<init>", "values", "valueOf"):
                dump_method(m)

    # ------------------------------------------------------------------ 3
    emit("")
    emit("=" * 78)
    emit("SECTION 4  call sites that pass a StyleType / SceneType constant")
    emit("=" * 78)
    hits = defaultdict(list)
    for dexname, d in dexes:
        for c in d.get_classes():
            cn = c.get_name()
            if cn.startswith("Lcom/hihonor/uikit/transparency/"):
                continue  # skip the library internals themselves
            for m in c.get_methods():
                if m.get_code() is None:
                    continue
                ins = list(m.get_instructions())
                for i, x in enumerate(ins):
                    o = x.get_output()
                    if ("setViewStyleType" in o or "setViewStyleWithScenes" in o
                            or "widgetSizeType" in o):
                        ctx = [y.get_output() for y in ins[max(0, i - 4):i + 1]]
                        hits[cn].append((m.get_name(), ctx))
    for cn in sorted(hits)[:80]:
        emit("")
        emit("### CALLSITE CLASS %s" % cn)
        for mn, ctx in hits[cn][:12]:
            emit("   in %s()" % mn)
            for l in ctx:
                emit("      " + short(l)[:220])

    # ------------------------------------------------------------------ 4
    emit("")
    emit("=" * 78)
    emit("SECTION 5  material repositories: class list + float constants")
    emit("=" * 78)
    repos = []
    for dexname, d, c in find_classes(
            dexes, lambda n: "/data/repo/" in n or "/data/" in n and "repo" in n):
        repos.append((dexname, c))
    emit("repo classes:", len(repos))
    for dexname, c in repos[:70]:
        emit("  %s  [%s]" % (c.get_name(), dexname))
        for m in c.get_methods():
            if m.get_name() in ("<clinit>", "<init>"):
                lines = ins_lines(m, limit=160)
                floats = [l for l in lines
                          if "const" in l and ("#" in l or "float" in l)]
                for l in floats[:28]:
                    emit("   ", l.strip())
                if floats:
                    emit("    (%d const lines total)" % len(floats))

    # ------------------------------------------------------------------ 5
    emit("")
    emit("=" * 78)
    emit("SECTION 6  framework material parameter setters (range hints)")
    emit("=" * 78)
    fw = os.path.join(os.path.dirname(DEFAULT_APK), "framework.jar")
    if os.path.exists(fw):
        try:
            with zipfile.ZipFile(fw) as z:
                blobs = [z.read(n) for n in z.namelist() if n.endswith(".dex")]
            for b in blobs[:3]:
                if b[:4] != b"dex\n":
                    continue
                dd = DEX(b)
                for c in dd.get_classes():
                    n = c.get_name()
                    if "android/graphics/material/" not in n:
                        continue
                    if not any(k in n for k in ("GlassParam", "BlurParam",
                                                "MaterialOpt", "LiquidGlass",
                                                "FrostGlass")):
                        continue
                    emit("")
                    emit("### %s" % n)
                    for m in c.get_methods():
                        if m.get_name().startswith("set") or m.get_name() == "<clinit>":
                            dump_method(m)
        except Exception as e:
            emit("framework.jar skipped:", e)

    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        f.write("\n".join(OUT) + "\n")
    print("\nWROTE", out, len(OUT), "lines")


if __name__ == "__main__":
    main()
