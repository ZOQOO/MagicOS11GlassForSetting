#!/usr/bin/env python3
"""Per-scope capability audit of the offline ROM assets.

Unlike scan_jars.py (string grep) this resolves DEX class_def items, so a
class reported as PRESENT is really DEFINED in that APK, and it dumps the real
method signatures of the uikit container. That matters because
MaterialBridge.probe() loads classes through the HOST ClassLoader: a scope that
only *references* HnTransFrameLayout, or ships a differently obfuscated build,
would silently fail at runtime.

Usage:
  python3 scope_audit.py <offline-dir> [output.txt]

Read-only: only local files are read; nothing is sent to the device.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dex_api import Dex, iter_dex  # noqa: E402

# Classes MaterialBridge.probe() tries to load in the host process.
APP_CLASSES = {
    "frame": "Lcom/hihonor/uikit/transparency/widget/HnTransFrameLayout;",
    "relative": "Lcom/hihonor/uikit/transparency/widget/HnTransRelativeLayout;",
    "linear": "Lcom/hihonor/uikit/transparency/widget/HnTransLinearLayout;",
    "style": "Lcom/hihonor/uikit/transparency/style/StyleType;",
    "scene": "Lcom/hihonor/uikit/transparency/effect/SceneType;",
    "aggregator": "Lcom/hihonor/uikit/transparency/adapters/HnMaterialAggregator;",
}
BOOT_CLASSES = {
    "RenderEffect": "Landroid/graphics/RenderEffect;",
    "Material": "Landroid/graphics/material/Material;",
    "LiquidGlassMaterial": "Landroid/graphics/material/LiquidGlassMaterial;",
    "FrostGlassMaterial": "Landroid/graphics/material/FrostGlassMaterial;",
    "GlassParam": "Landroid/graphics/material/atomiceffect/GlassParam;",
    "HnVisualEffectEx": "Lcom/hihonor/android/vieweffect/HnVisualEffectEx;",
    "HnMaterialOpt": "Lcom/hihonor/android/graphics/material/HnMaterialOpt;",
}
SDK_CLASSES = {
    "api.HnVisualEffectLayout": "Lcom/hihonor/uikit/transparency/api/HnVisualEffectLayout;",
    "api.HnVisualEffect": "Lcom/hihonor/uikit/transparency/api/HnVisualEffect;",
    "api.HnGlassEffect": "Lcom/hihonor/uikit/transparency/api/HnGlassEffect;",
}
# Public entry points our reflection bridge calls on the container.
NEEDED_METHODS = (
    "setViewStyleType", "setViewStyleWithScenes", "setCornerRadius",
    "setBlurType", "onConfigurationChanged", "<init>",
)

# Real scope packages, in the same order as scope.list.
SCOPE_ORDER = (
    "com.android.settings", "com.hihonor.systemmanager",
    "com.hihonor.controlcenter", "com.hihonor.assistant", "com.hihonor.id",
    "com.hihonor.wallet", "com.hihonor.parentcontrol", "com.android.systemui",
)


def audit_file(path):
    """Return (dex_count, class_index, errors). class_index maps cls -> (dex, off)."""
    data = open(path, "rb").read()
    index = {}
    errors = []
    n_dex = 0
    for dexname, blob in iter_dex(data):
        n_dex += 1
        try:
            dex = Dex(blob)
            for cls, off in dex.classes():
                index.setdefault(cls, (dex, dexname, off))
        except Exception as e:  # a truncated / non-DEX member
            errors.append("%s: %s" % (dexname, e))
    return n_dex, index, errors


def methods_of(entry, wanted):
    if entry is None:
        return []
    dex, _, off = entry
    try:
        methods, _ = dex.class_members(off)
    except Exception:
        return []
    found = set()
    for m in methods:
        # m = "<owner> <name>(<args>)<ret>"
        name = m.split(" ", 1)[-1].split("(", 1)[0]
        if name in wanted:
            found.add(m.strip())
    return sorted(found)


def present(index, descriptor):
    return descriptor in index


def yesno(v):
    return "YES" if v else "-"


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    out_path = sys.argv[2] if len(sys.argv) > 2 else None
    lines = []
    def emit(s=""):
        lines.append(s)
        print(s)

    files = {}
    for name in sorted(os.listdir(root)):
        if name.endswith((".apk", ".jar")):
            files[name.rsplit(".", 1)[0]] = os.path.join(root, name)

    emit("=" * 96)
    emit("MAGICGLASS PER-SCOPE CAPABILITY AUDIT (class_def level, offline ROM assets)")
    emit("=" * 96)
    emit("root: %s" % root)
    emit("files: %d" % len(files))
    emit("")

    summaries = {}
    for label in list(SCOPE_ORDER) + ["framework", "framework-magic"]:
        if label not in files:
            emit("!! MISSING ASSET: %s" % label)
            continue
        path = files[label]
        n_dex, index, errors = audit_file(path)
        emit("-" * 96)
        emit("## %s  (%s, %.1f MB, %d dex)" %
             (label, os.path.basename(path), os.path.getsize(path) / 1048576.0, n_dex))
        if errors:
            emit("   parse errors: %s" % "; ".join(errors[:3]))
        uikit = sorted(c for c in index if "Lcom/hihonor/uikit/transparency/" in c)
        emit("   uikit classes defined : %d" % len(uikit))
        emit("   APP  : " + "  ".join("%s=%s" % (k, yesno(present(index, v)))
                                    for k, v in APP_CLASSES.items()))
        emit("   BOOT : " + "  ".join("%s=%s" % (k, yesno(present(index, v)))
                                    for k, v in BOOT_CLASSES.items()))
        emit("   SDK  : " + "  ".join("%s=%s" % (k, yesno(present(index, v)))
                                    for k, v in SDK_CLASSES.items()))
        frame = index.get(APP_CLASSES["frame"])
        style = index.get(APP_CLASSES["style"])
        ms = methods_of(frame, NEEDED_METHODS)
        emit("   HnTransFrameLayout public API (%d matched):" % len(ms))
        for m in ms[:22]:
            emit("      %s" % m)
        if frame and not ms:
            emit("      !! class exists but NO expected method was resolved")

        # The parameter type of setViewStyleType IS the StyleType class, even
        # when its name is obfuscated (Lqe8; / Ls29;). MaterialBridge now
        # resolves it dynamically instead of hard-coding the class name.
        style_param = None
        for m in ms:
            if " setViewStyleType(" in m:
                style_param = m.split("(", 1)[1].split(")", 1)[0].split(",")[0]
                break
        emit("   setViewStyleType param (real StyleType): %s" % (style_param or "none"))
        if style_param and not present(index, style_param):
            emit("      !! parameter type is not defined in this APK (external dep)")

        view_entry = index.get("Landroid/view/View;")
        backdrop = methods_of(view_entry, {"setBackdropRenderEffect"})
        if backdrop:
            emit("   framework View: %s" % backdrop[0])

        legacy_host = frame is not None and style is not None
        dynamic_host = frame is not None and style_param is not None
        summaries[label] = {
            "dex": n_dex, "uikit": len(uikit), "frame": frame is not None,
            "style": style is not None, "param": style_param,
            "legacy": legacy_host, "dynamic": dynamic_host,
            "boot": present(index, BOOT_CLASSES["RenderEffect"]),
            "backdrop": bool(backdrop),
        }

    # BOOT_CLASSES live in the boot classpath, so their availability is GLOBAL
    # for every scope process -- not something to look for inside a scope APK.
    boot_global = any(summaries.get(k, {}).get("backdrop")
                      or summaries.get(k, {}).get("boot")
                      for k in ("framework", "framework-magic"))
    def chosen(host):
        return "HOST_UIKIT" if host else ("BOOT_BACKDROP" if boot_global else "NONE")

    emit("")
    emit("=" * 96)
    emit("SUMMARY MATRIX  (boot classpath available = %s)" % yesno(boot_global))
    emit("=" * 96)
    emit("%-28s %-5s %-7s %-6s %-6s %-11s %-14s %-14s" %
         ("scope", "dex", "uikit", "frame", "style", "style-param",
          "legacy mode", "dynamic mode"))
    for label, s in summaries.items():
        is_boot = label.startswith("framework")
        emit("%-28s %-5d %-7d %-6s %-6s %-11s %-14s %-14s" %
             (label, s["dex"], s["uikit"], yesno(s["frame"]), yesno(s["style"]),
              (s["param"] or "-")[:11],
              "-" if is_boot else chosen(s["legacy"]),
              "-" if is_boot else chosen(s["dynamic"])))
    emit("")
    emit("NOTE: 'legacy mode' is what a hard-coded CLS_STYLE_TYPE lookup gives;")
    emit("      'dynamic mode' is what MaterialBridge.resolveEnums() gives after")
    emit("      this audit. systemmanager / wallet have frame=YES, style=NO but a")
    emit("      perfectly usable obfuscated StyleType (Lqe8; / Ls29;) whose")
    emit("      Enum.name() constants are still plaintext (verified in <clinit>).")
    emit("      They therefore upgrade from BOOT_BACKDROP to HOST_UIKIT.")
    emit("      framework/framework-magic rows describe the shared boot classpath.")

    if out_path:
        with open(out_path, "w", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")
        print("\n[written] %s" % out_path)


if __name__ == "__main__":
    main()
