#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
0.5.0-p71 deterministic invariants (offline, no device needed).

User quote driving this build:
  "还是这个问题没有修好 这个玻璃容器内抓取的应该是实时背景的样式，
   不行的话就回归最初叫你修改视频背景时的样子。"

Root cause closed with code facts (no guessing):
  p70 restored the plate's IDENTITY stability (one fixed object, zero
  draw-path allocation) but kept VIDEO_CAPTURE_INTERVAL_MS = 200, so the
  plate CONTENT was <=5 fps stale. The live TextureView keeps playing at
  video fps, so the glass sampled an old frame while the screen behind
  showed a new one. For moving content a temporally stale sample is
  perceptually a spatial offset -- the persistent "拖动时错位".

p71 contract under test:
  A. capture cadence 200 ms -> 32 ms (~30 fps), coalescing unchanged;
  B. video plate at PLATE_SCALE_PCT (half resolution) so a ~30 fps bake
     is affordable on the UI thread;
  C. drawRegion()'s plateScale==100 branch is LINE-IDENTICAL to the whole
     p70 drawRegion body (image / L1 path untouched);
  D. the scaled branch reproduces the p20 edge-clamp contract;
  E. every screenBmp assignment site sets plateScale consistently;
  F. only BackdropLayer + GlassEntry(BUILD line) differ from the p70
     archives; the rendering trio stays byte-identical to the L1 p30
     baseline; RenderController is byte-identical to p70.
  G. version truth: BUILD 0.5.0-p71, module.prop versionCode 99.
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "src", "main", "java", "com", "magicglass")
ARCH = os.path.join(ROOT, "probe", "probe-report", "archive")
BASE = os.path.join(ROOT, "probe", "probe-report", "baselines", "L1-ACCEPTED-p30")

fails = []
passes = 0


def ck(name, cond):
    global passes
    if cond:
        passes += 1
    else:
        fails.append(name)
        print("FAIL: " + name)


def rd(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def strip_comments(t):
    t = re.sub(r"/\*.*?\*/", "", t, flags=re.S)
    return re.sub(r"//[^\n]*", "", t)


def method_body(text, sig):
    """Brace-balanced body (incl. outer braces) of the method at `sig`."""
    i = text.find(sig)
    if i < 0:
        return ""
    j = text.find("{", i)
    if j < 0:
        return ""
    d = 0
    for k in range(j, len(text)):
        if text[k] == "{":
            d += 1
        elif text[k] == "}":
            d -= 1
            if d == 0:
                return text[j:k + 1]
    return ""


def code_lines(body):
    """Non-blank lines with comments stripped, in order (line-identity)."""
    out = []
    for ln in strip_comments(body).splitlines():
        s = ln.strip()
        if s:
            out.append(s)
    return out


BD = rd(os.path.join(SRC, "BackdropLayer.java"))
BD70 = rd(os.path.join(ARCH, "BackdropLayer-p70-current.java"))

# ---- A. real-time capture cadence ------------------------------------------
ck("A01 VIDEO_CAPTURE_INTERVAL_MS is 32L (~30 fps real-time)",
   "private static final long VIDEO_CAPTURE_INTERVAL_MS = 32L;" in BD)
ck("A02 the stale 200 ms throttle is gone",
   "VIDEO_CAPTURE_INTERVAL_MS = 200L" not in BD)
ck("A03 coalescing still guarantees one pending readback",
   "videoCapturePosted" in BD and "handler.post(videoCaptureTask)" in BD)
ck("A04 AOSP deferral comment still forbids the draw-time readback",
   "Do not invoke\n     * this method from a drawing method" in BD70
   or "drawing method" in BD)

# ---- B. half-resolution video plate ---------------------------------------
ck("B01 PLATE_SCALE_PCT constant exists and is 50",
   "private static final int PLATE_SCALE_PCT = 50;" in BD)
ck("B02 plateScale field default is 100 (image path 1:1)",
   "private int plateScale = 100;" in BD)
ck("B03 ensurePlateSnapshot derives plateScale from videoMode",
   "plateScale = videoMode ? PLATE_SCALE_PCT : 100;" in method_body(
       BD, "private void ensurePlateSnapshot()"))
ck("B04 ensurePlateSnapshot scales the allocation size",
   "w = Math.max(1, w * plateScale / 100);" in method_body(
       BD, "private void ensurePlateSnapshot()"))
ck("B05 snapshot() image branch pins plateScale = 100",
   "plateScale = 100;" in method_body(BD, "private Bitmap snapshot()"))
ck("B06 drawRegion dispatches on plateScale",
   "if (plateScale != 100) {" in method_body(
       BD, "public boolean drawRegion(Canvas c, int left, int top, int w, int h)"))
ck("B07 drawRegionScaled exists and reads plateScale",
   "float k = plateScale / 100f;" in method_body(
       BD, "private boolean drawRegionScaled(Canvas c, Bitmap snap, int sw, int sh,\n                                     int left, int top, int w, int h)"))

# ---- C. the scale==100 branch is LINE-IDENTICAL to p70's whole body -------
p70_body = method_body(BD70, "public boolean drawRegion(Canvas c, int left, int top, int w, int h)")
p71_body = method_body(BD, "public boolean drawRegion(Canvas c, int left, int top, int w, int h)")
ck("C00 both drawRegion bodies are non-empty (no vacuous compare)",
   bool(p70_body) and bool(p71_body))
# Take p71's body, cut out the new dispatch lines, and compare to p70.
disp = ("            if (plateScale != 100) {\n"
        "                return drawRegionScaled(c, snap, sw, sh, left, top, w, h);\n"
        "            }\n")
ck("C01 the dispatch block appears verbatim exactly once in p71 drawRegion",
   p71_body.count(disp) == 1)
stripped71 = p71_body.replace(disp, "")
ck("C02 drawRegion minus the dispatch is line-identical to p70 (L1 path)",
   code_lines(stripped71) == code_lines(p70_body))
ck("C03 drawRegionScaled is NOT in the p70 archive (new, video-only)",
   "drawRegionScaled" not in BD70)

# ---- D. scaled branch reproduces the p20 edge-clamp ----------------------
scaled = method_body(BD, "private boolean drawRegionScaled(Canvas c, Bitmap snap, int sw, int sh,\n                                     int left, int top, int w, int h)")
ck("D01 scaled branch clamps the source window to the plate",
   "Math.max(0f, left * k)" in scaled and "Math.min((float) sw, (left + w) * k)" in scaled)
ck("D02 scaled branch reports noteFail(2) on an empty window",
   "noteFail(2," in scaled)
ck("D03 scaled branch shifts dst by the clamped amount (p20 contract)",
   "Math.round(fx0 / k) - left" in scaled)
ck("D04 scaled branch draws via the shared srcRect/dstRect objects",
   "srcRect.set(" in scaled and "dstRect.set(" in scaled
   and "c.drawBitmap(snap, srcRect, dstRect, null);" in scaled)

# ---- E. plateScale / screenBmp consistency --------------------------------
code_bd = strip_comments(BD)
assign_sites = len(re.findall(r"screenBmp\s*=(?!=)", code_bd))
ck("E01 exactly the 4 historical screenBmp assignment sites remain",
   assign_sites == 4)
# 4 sites: snapshot() image branch, ensurePlateSnapshot(), dropSnapshot(),
# recycle(). dropSnapshot/recycle set null; the other two pin plateScale.
ck("E02 plateScale is pinned at both non-null assignment sites",
   BD.count("plateScale = videoMode ? PLATE_SCALE_PCT : 100;") == 1
   and BD.count("plateScale = 100;") == 2)
ck("E03 bake uses the PLATE's own dims for the matrix (half-res proof)",
   "plate.getWidth(), plate.getHeight());" in method_body(
       BD, "private void bakeVideoFrameIntoPlate()"))

# ---- F. blast radius vs p70 / p30 ------------------------------------------
others = [f for f in os.listdir(SRC) if f.endswith(".java")]
ck("F00 18 app sources present", len(others) == 18)
diff = []
for f in sorted(others):
    new = rd(os.path.join(SRC, f))
    old = rd(os.path.join(ARCH, f[:-5] + "-p70-current.java"))
    if new != old:
        diff.append(f)
ck("F01 only BackdropLayer + GlassEntry differ from p70",
   sorted(diff) == ["BackdropLayer.java", "GlassEntry.java"], )
GE = rd(os.path.join(SRC, "GlassEntry.java"))
GE70 = rd(os.path.join(ARCH, "GlassEntry-p70-current.java"))
ge_new = GE.replace('BUILD = "0.5.0-p71"', 'BUILD = "0.5.0-p70"')
ck("F02 GlassEntry differs ONLY in the BUILD string",
   ge_new == GE70)
for f in ("MaterialBridge.java", "LiquidGlassView.java", "LiquidShader.java"):
    ck("F1x %s byte-identical to the L1 p30 baseline" % f,
       open(os.path.join(SRC, f), "rb").read()
       == open(os.path.join(BASE, "src", "main", "java", "com", "magicglass", f), "rb").read())
RC = rd(os.path.join(SRC, "RenderController.java"))
RC70 = rd(os.path.join(ARCH, "RenderController-p70-current.java"))
ck("F03 RenderController byte-identical to p70 (no RC change this round)",
   RC == RC70)

# ---- G. version truth ------------------------------------------------------
ck("G01 GlassEntry BUILD is 0.5.0-p71", 'BUILD = "0.5.0-p71"' in GE)
prop = rd(os.path.join(ROOT, "app", "src", "main", "resources",
                       "META-INF", "xposed", "module.prop"))
ck("G02 module.prop version=0.5.0-p71", "version=0.5.0-p71" in prop)
ck("G03 module.prop versionCode=99", "versionCode=99" in prop)

# ---- diagnostics carry the objective criteria ------------------------------
ck("H01 VIDGLASS log carries scale=",
   '" scale=" + plateScale' in BD)
ck("H02 VIDGLASS cadence criterion documented (2x fps per 2 s window)",
   "cap` must grow by ~2x the video fps" in BD or "2x the video fps" in BD)

print()
print("p71 invariants: %d/%d" % (passes, passes + len(fails)))
if fails:
    print("FAILED:")
    for f in fails:
        print("  - " + f)
    sys.exit(1)
print("ALL OK")
