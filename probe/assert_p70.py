#!/usr/bin/env python3
"""Deterministic invariant gate for MagicGlass 0.5.0-p70.

Pure static checks: no device, no build, no network.

User report: "背景换成视频以后已经有了液态玻璃效果，但是拖动时会出现错位，
往前找一下之前修复背景是图片时也有这个问题，应该可以用在这里。"

The reusable fix from the IMAGE era is the FIXED-SCREEN-SNAPSHOT invariant
(0.4.0-p20, findings 29.2): during a drag, snapshot() returns the SAME
Bitmap object, with zero allocation and zero recycle; drawRegion() samples
it with the CURRENT screen coordinates. p69 broke that invariant for video
by deferring a full-screen createBitmap()+recycle() into the draw frame
path (~5x/second) via `snapshotStyleKey = ""`.

p70 restores it. Section C proves the frame path is allocation-free again,
section E proves that ONLY BackdropLayer.java + the BUILD line changed.
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "src", "main", "java", "com", "magicglass")
META = os.path.join(ROOT, "app", "src", "main", "resources", "META-INF", "xposed")
ARCHIVE = os.path.join(ROOT, "probe", "probe-report", "archive")
BASE30 = os.path.join(ROOT, "probe", "probe-report", "baselines",
                      "L1-ACCEPTED-p30", "src", "main", "java", "com", "magicglass")

CHECKS = []


def ck(name, cond):
    CHECKS.append((name, bool(cond)))


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def raw(path):
    with open(path, "rb") as f:
        return f.read()


def java(name):
    return read(os.path.join(SRC, name + ".java"))


def snap(name):
    return read(os.path.join(ARCHIVE, name + ".java"))


def base(name):
    return read(os.path.join(BASE30, name + ".java"))


def strip_comments(s):
    s = re.sub(r'/\*.*?\*/', '', s, flags=re.S)
    return re.sub(r'//[^\n]*', '', s)


def method_body(text, sig):
    """Brace-balanced body (including outer braces) of one method."""
    i = text.find(sig)
    if i < 0:
        return ""
    j = text.find('{', i)
    if j < 0:
        return ""
    depth = 0
    for k in range(j, len(text)):
        if text[k] == '{':
            depth += 1
        elif text[k] == '}':
            depth -= 1
            if depth == 0:
                return text[j:k + 1]
    return ""


names = sorted(f for f in os.listdir(SRC) if f.endswith(".java"))
ALL = "\n".join(read(os.path.join(SRC, n)) for n in names)
code_only = re.sub(r'//[^\n]*', '', re.sub(r'/\*.*?\*/', '', ALL, flags=re.S))

CFG = java("ConfigContract")
CS = java("ConfigStore")
SA = java("SettingsActivity")
GE = java("GlassEntry")
RC = java("RenderController")
BD = java("BackdropLayer")
BD69 = snap("BackdropLayer-p69-current")
SM = java("ScaleMode")
STUBS = read(os.path.join(ROOT, "probe", "compile_check.py"))
PROP = read(os.path.join(META, "module.prop"))
SCOPE = read(os.path.join(META, "scope.list"))
m = re.search(r'BUILD\s*=\s*"([^"]+)"', GE)
BUILD = m.group(1) if m else ""
PROP_VER = dict(l.strip().split("=", 1) for l in PROP.splitlines() if "=" in l)

# ---- A. repository / version / red lines ---------------------------------
ck("A01 app source count is 18", len(names) == 18)
ck("A02 GlassEntry.BUILD = 0.5.0-p70", BUILD == "0.5.0-p70")
ck("A03 module.prop version = 0.5.0-p70", PROP_VER.get("version") == "0.5.0-p70")
ck("A04 module.prop versionCode = 98", PROP_VER.get("versionCode") == "98")
ck("A05 BUILD / module.prop agree", BUILD == PROP_VER.get("version"))
ck("A06 scope.list has 9 entries",
   len([l for l in SCOPE.splitlines() if l.strip()]) == 9)
try:
    ET.parse(os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml"))
    xml_ok = True
except Exception:
    xml_ok = False
ck("A07 AndroidManifest.xml parses", xml_ok)
ck("A08 build.sh present", os.path.isfile(os.path.join(ROOT, "build.sh")))
ck("A09 zero MIUI setMi calls", "setMi" not in code_only)
ck("A10 zero com.ciallo references", "com.ciallo" not in code_only)
ck("A11 mg-build startup signature kept", 'say("mg-build " + BUILD' in ALL)
ck("A12 PERF_DEBUG_LOG defaults false",
   'PERF_DEBUG_LOG("perf.debugLog", "false")' in CFG)
ck("A13 RECEIVER_EXPORTED kept", "Context.RECEIVER_EXPORTED" in RC)
ck("A14 WINDOW_BASE_COLOR kept", "WINDOW_BASE_COLOR" in CFG)
ck("A15 p69 APK archived before build.sh clears build-out",
   os.path.isfile(os.path.join(ARCHIVE, "MagicGlass-0.5.0-p69.apk")))
ck("A16 all 18 p69 source snapshots present",
   all(os.path.isfile(os.path.join(ARCHIVE, n[:-5] + "-p69-current.java"))
       for n in names))
ck("A17 p30 baseline present",
   all(os.path.isfile(os.path.join(BASE30, f + ".java"))
       for f in ("MaterialBridge", "LiquidGlassView", "LiquidShader")))

# ---- B. the reusable IMAGE-era fix is still in place ---------------------
ck("B01 drawRegion() documents the FIXED snapshot",
   "Draw the part of the FIXED snapshot" in BD)
ck("B02 p20 edge clamp is intact",
   "int x0 = Math.max(0, left);" in BD
   and "dstRect.set(x0 - left, y0 - top, x1 - left, y1 - top);" in BD)
ck("B03 LiquidGlassView reads the CURRENT screen position every frame",
   "getLocationOnScreen(loc);" in java("LiquidGlassView")
   and "layer.drawRegion(c, loc[0] + pad, loc[1] + pad, w, h);"
       in java("LiquidGlassView"))
ck("B04 snapshot() hot path is a single key comparison, no allocation",
   "styleKey.equals(snapshotStyleKey)" in BD
   and "return cached;" in BD)

# ---- C. p70 frame-path fix: fixed plate, zero draw-path allocation -------
snapshot_body = method_body(BD, "private Bitmap snapshot()")
snapshot_body69 = method_body(BD69, "private Bitmap snapshot()")
ck("C01 snapshot() is byte-identical to p69 (frame path untouched)",
   bool(snapshot_body) and snapshot_body == snapshot_body69)
draw_body = method_body(BD, "public boolean drawRegion(Canvas c,")
draw_body69 = method_body(BD69, "public boolean drawRegion(Canvas c,")
ck("C02 drawRegion() is byte-identical to p69",
   bool(draw_body) and draw_body == draw_body69)
ck("C03 plateKey() no longer contains the capture counter",
   'return "plate|" + styleKey + "|" + Integer.toHexString(windowBgColor);' in BD
   and "sVideoCapOk" not in method_body(BD, "private String plateKey()"))
# Comments in captureVideoFrame() quote the p69 lines being removed, so
# compare CODE ONLY -- otherwise a documentation string is a false positive.
cap_code = strip_comments(method_body(BD, "private void captureVideoFrame()"))
ck("C04 capture NO LONGER invalidates the plate key (the p69错位 trigger)",
   'snapshotStyleKey = ""' not in cap_code)
ck("C05 capture bakes IN PLACE instead of scheduling an allocation",
   "bakeVideoFrameIntoPlate();" in cap_code)
ck("C06 capture no longer invalidates the decor 5x/second",
   "refreshWindowBg()" not in cap_code)
ck("C07 capture refuses to paint into a foreign image snapshot",
   "!plateKey().equals(snapshotStyleKey)" in BD)
bake = method_body(BD, "private void bakeVideoFrameIntoPlate()")
ck("C08 bake exists and is non-empty", bool(bake))
ck("C09 bake never replaces screenBmp", "screenBmp =" not in bake)
ck("C10 bake never recycles the plate", ".recycle()" not in bake)
ck("C11 bake paints onto the existing object",
   "Canvas c = new Canvas(plate);" in bake
   and "c.drawColor(windowBgColor);" in bake
   and "c.drawBitmap(f, 0f, 0f, null);" in bake)
ck("C12 bake uses the SAME ScaleMode.matrix() as the on-screen video",
   "Matrix m = ScaleMode.matrix(scaleMode, scaleZoom," in bake
   and "scaleFocusX, scaleFocusY," in bake
   and "plate.getWidth(), plate.getHeight());" in bake)
ck("C13 AOSP evidence comment survives (no double transform)",
   "IGNORES setTransform()" in BD or "ignores setTransform" in BD)
ens = method_body(BD, "private void ensurePlateSnapshot()")
# One createBitmap in the IMAGE snapshot path (byte-identical to p69) and
# one in the plate path. No third one exists anywhere in the file.
ck("C14 exactly two full-screen allocations: image snapshot + plate",
   BD.count("Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)") == 2
   and "Bitmap.createBitmap" in ens
   and strip_comments(snapshot_body).count("Bitmap.createBitmap") == 1
   and strip_comments(ens).count("Bitmap.createBitmap") == 1)
ck("C15 allocation count is logged / observable",
   "sVideoAlloc++;" in ens and "public static int diagVideoAlloc()" in BD)
ck("C16 VIDGLASS log exposes alloc for the drag criterion",
   '" alloc=" + sVideoAlloc' in BD and '" bake=" + sVideoBake' in BD)
ck("C17 a fresh plate immediately shows the newest frame",
   "if (videoMode) bakeVideoFrameIntoPlate();" in ens)
ck("C18 ensurePlateSnapshot() still has the O(1) cache hit",
   "key.equals(snapshotStyleKey)" in ens
   and "return;                     // O(1) hot path, no allocation" in ens)

# ---- D. plate stays fixed across config refreshes ------------------------
ck("D01 releaseSourceOnly() exists", "private void releaseSourceOnly()" in BD)
rel = method_body(BD, "private void releaseSourceOnly()")
ck("D02 releaseSourceOnly keeps screenBmp / snapshotStyleKey",
   "screenBmp" not in rel and "snapshotStyleKey" not in rel
   and "current = null;" in rel and "imageName = null;" in rel)
apply_body = method_body(BD, "public void apply(ConfigContract.Slot slot,")
i_vid = apply_body.find('"video".equals(kind)')
i_none = apply_body.find('"none".equals(kind)')
vid_branch = apply_body[i_vid:i_none] if i_vid >= 0 and i_none > i_vid else ""
none_branch = apply_body[i_none:i_none + 400] if i_none >= 0 else ""
ck("D03 video branch uses releaseSourceOnly(), not recycle()",
   "releaseSourceOnly();" in vid_branch and "recycle();" not in vid_branch)
ck("D04 video branch still guarantees a plate before first draw",
   "ensurePlateSnapshot();" in vid_branch)
ck("D05 'none' branch bakes eagerly (no draw-phase allocation)",
   "ensurePlateSnapshot();" in none_branch)
ck("D06 BG_ENABLED=false path still re-bakes the plate",
   "recycle();\n        ensurePlateSnapshot();\n        refreshWindowBg();" in BD)
ck("D07 delayed capture / 3-fail fallback / repaint wiring all survive",
   all(t in BD for t in ("scheduleVideoCapture();", "handler.post(videoCaptureTask);",
                         "VIDEO_CAPTURE_MAX_FAIL = 3", "videoRepaint",
                         "setVideoRepaint(Runnable r)", "releaseVideoFrame()")) )
ck("D08 deferred readback still avoids the AOSP drawing-method hazard",
   "videoCaptureTask = new Runnable()" in BD
   and "Do not invoke\n     * this method from a drawing method" in BD)

# ---- E. negative protection: ONLY 2 files changed ------------------------
changed = []
for n in names:
    f = n[:-5]
    p69 = os.path.join(ARCHIVE, f + "-p69-current.java")
    if not os.path.isfile(p69):
        changed.append(f + " (no p69 snapshot)")
    elif raw(os.path.join(SRC, n)) != raw(p69):
        changed.append(f)
ck("E01 only BackdropLayer + GlassEntry differ from p69",
   sorted(changed) == ["BackdropLayer", "GlassEntry"])
ck("E02 GlassEntry differs ONLY by the BUILD line",
   GE.replace('"0.5.0-p70"', '"0.5.0-p69"') == snap("GlassEntry-p69-current"))
for f in ("MaterialBridge", "LiquidGlassView", "LiquidShader"):
    ck("E03 %s.java byte-identical to L1-ACCEPTED-p30" % f,
       raw(os.path.join(SRC, f + ".java")) == raw(os.path.join(BASE30, f + ".java")))
ck("E04 rebuildEffect (p30 shader fix) survives", "rebuildEffect" in java("LiquidGlassView"))
for token in ("extWhiteGate", "latchListRowsNow", "restripLatched",
              "RECEIVER_EXPORTED", "setWillNotDraw", "keepBackground"):
    ck("E05 accepted branch %s survives" % token, token in RC)
ck("E06 p69 position sliders / custom filling survive",
   '"左右位置"' in SA and '"上下位置"' in SA and '"拉伸大小"' in SA
   and 'ConfigContract.Param.BG_SCALE_FOCUS_X, "50"' not in CS)
ck("E07 p68 LiquidTab restore still in place",
   "restoreGlassDefaults" in CS and "FROZEN_GLASS" in CS)
ck("E08 compile stubs for getBitmap / Handler.post survive",
   "public android.graphics.Bitmap getBitmap(int w, int h)" in STUBS
   and "public boolean post(Runnable r) { return true; }" in STUBS)

bad = [n for n, ok in CHECKS if not ok]
print("assert_p70: %d/%d checks passed" % (len(CHECKS) - len(bad), len(CHECKS)))
for n in bad:
    print("  FAIL:", n)
sys.exit(1 if bad else 0)
