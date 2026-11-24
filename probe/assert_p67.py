#!/usr/bin/env python3
"""Deterministic invariant gate for MagicGlass 0.5.0-p67 (filling + preview).

Pure static checks: no device, no build, no network. Every assertion is a
string/byte fact that can be re-verified from this repository. A failure means
the accepted render path or the new filling contract was broken.
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "src", "main", "java", "com", "magicglass")
META = os.path.join(ROOT, "app", "src", "main", "resources", "META-INF", "xposed")
ARCHIVE = os.path.join(ROOT, "probe", "probe-report", "archive")

CHECKS = []


def ck(name, cond):
    CHECKS.append((name, bool(cond)))


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def java(name):
    return read(os.path.join(SRC, name + ".java"))


names = sorted(f for f in os.listdir(SRC) if f.endswith(".java"))
ALL = "\n".join(read(os.path.join(SRC, n)) for n in names)
SM = java("ScaleMode")
CFG = java("ConfigContract")
BD = java("BackdropLayer")
SA = java("SettingsActivity")
RC = java("RenderController")
GE = java("GlassEntry")
STUBS = read(os.path.join(ROOT, "probe", "compile_check.py"))
PROP = read(os.path.join(META, "module.prop"))
SCOPE = read(os.path.join(META, "scope.list"))
BUILD = re.search(r'BUILD\s*=\s*"([^"]+)"', GE).group(1) if re.search(
    r'BUILD\s*=\s*"([^"]+)"', GE) else ""
PROP_VER = dict(l.strip().split("=", 1) for l in PROP.splitlines() if "=" in l)

# ---- A. repository / version / red lines ---------------------------------
ck("A01 ScaleMode.java is a new source", os.path.isfile(os.path.join(SRC, "ScaleMode.java")))
# app/src/main/java was 17 files at p66; compile_check.py reports 19 sources
# because it also compiles the single probe/src/main/java stub source.
ck("A02 app source count is 18 (17 + ScaleMode)", len(names) == 18)
ck("A03 GlassEntry.BUILD = 0.5.0-p67", BUILD == "0.5.0-p67")
ck("A04 module.prop version = 0.5.0-p67", PROP_VER.get("version") == "0.5.0-p67")
ck("A05 module.prop versionCode = 95", PROP_VER.get("versionCode") == "95")
ck("A06 BUILD / module.prop agree", BUILD == PROP_VER.get("version"))
ck("A07 scope.list has 9 entries",
   len([l for l in SCOPE.splitlines() if l.strip()]) == 9)
ck("A08 manifest parses",
   ET.parse(os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml")) is not None)
ck("A09 build.sh present", os.path.isfile(os.path.join(ROOT, "build.sh")))
code_only = re.sub(r'/\*.*?\*/', '', ALL, flags=re.S)
code_only = re.sub(r'//[^\n]*', '', code_only)
ck("A10 zero MIUI setMi calls", "setMi" not in code_only)
ck("A11 zero com.ciallo references", "com.ciallo" not in code_only)
ck("A12 mg-build startup signature kept", 'say("mg-build " + BUILD' in ALL)
ck("A13 PERF_DEBUG_LOG defaults false", 'PERF_DEBUG_LOG("perf.debugLog", "false")' in CFG)
ck("A14 RECEIVER_EXPORTED kept", "Context.RECEIVER_EXPORTED" in RC)
ck("A15 WINDOW_BASE_COLOR kept", "WINDOW_BASE_COLOR" in CFG)
ck("A16 windowColorOf kept", "windowColorOf" in CFG)
ck("A17 GLASS toggle live log kept", "GLASS toggle=" in RC)
ck("A18 clearOverrides kept", "clearOverrides" in SA)

# ---- B. ScaleMode: one truth, six REAL modes -----------------------------
MODES = ["centerCrop", "fitCenter", "centerInside", "fill", "center", "custom"]
for i, m in enumerate(MODES):
    ck("B%02d mode value %s present" % (i + 1, m), '"%s"' % m in SM)
vals = re.search(r'VALUES\s*=\s*\{([^}]*)\}', SM, re.S)
ck("B07 VALUES has exactly 6 entries",
   vals is not None and len(re.findall(r'[A-Z_]+', vals.group(1))) == 6)
ck("B08 LABELS has exactly 6 entries",
   len(re.findall(r'"[^"]+"', re.search(r'LABELS\s*=\s*\{([^}]*)\}', SM, re.S).group(1))) == 6)
ck("B09 legacy fitXY is NOT a mode value",
   vals is not None and "fitXY" not in vals.group(1))
ck("B10 normalize falls back to centerCrop", "return CENTER_CROP;" in SM)
ck("B11 normalize loop over VALUES", "for (String v : VALUES)" in SM)
ck("B12 scaleTypeOf maps FIT_CENTER", "ImageView.ScaleType.FIT_CENTER" in SM)
ck("B13 scaleTypeOf maps CENTER_INSIDE", "ImageView.ScaleType.CENTER_INSIDE" in SM)
ck("B14 scaleTypeOf maps FIT_XY", "ImageView.ScaleType.FIT_XY" in SM)
ck("B15 scaleTypeOf maps CENTER", "ImageView.ScaleType.CENTER" in SM)
ck("B16 scaleTypeOf maps MATRIX", "ImageView.ScaleType.MATRIX" in SM)
ck("B17 default scaleType is CENTER_CROP", "ImageView.ScaleType.CENTER_CROP" in SM)
ck("B18 matrix handles non-uniform FILL", "m.setScale(sx, sy)" in SM)
ck("B19 matrix handles FIT_CENTER", "s = mn;" in SM)
ck("B20 matrix handles CENTER_INSIDE", "Math.min(1f, mn)" in SM)
ck("B21 matrix handles CENTER", "s = 1f;" in SM)
ck("B22 matrix handles CUSTOM zoom", "s = mx * z;" in SM)
ck("B23 matrix uses centerCrop max()", "Math.max(sx, sy)" in SM)
ck("B24 matrix applies focus translate", "m.postTranslate(" in SM)
ck("B25 matrix rejects zero dimensions", "if (sw <= 0 || sh <= 0" in SM)
ck("B26 styleKey exists", "styleKey(String mode, int zoom, int fx, int fy)" in SM)
ck("B27 zoomOf clamps 50..400", "BG_SCALE_ZOOM), 50, 400" in SM)
ck("B28 focusX clamps 0..100", "BG_SCALE_FOCUS_X), 0, 100" in SM)
ck("B29 focusY clamps 0..100", "BG_SCALE_FOCUS_Y), 0, 100" in SM)
ck("B30 description() exists", "description(String mode)" in SM)

# ---- C. ConfigContract: new params cannot touch the glass pool ----------
ck("C01 BG_SCALE_ZOOM defined", 'BG_SCALE_ZOOM("bg.scaleZoom", "150")' in CFG)
ck("C02 BG_SCALE_FOCUS_X defined", 'BG_SCALE_FOCUS_X("bg.scaleFocusX", "50")' in CFG)
ck("C03 BG_SCALE_FOCUS_Y defined", 'BG_SCALE_FOCUS_Y("bg.scaleFocusY", "50")' in CFG)
ck("C04 default BG_SCALE still centerCrop", 'BG_SCALE("bg.scale", "centerCrop")' in CFG)
ck("C05 glassFingerprint filters glass.", 'startsWith("glass.")' in CFG)
ck("C06 glassFingerprint filters geo.", 'startsWith("geo.")' in CFG)
ck("C07 bgFingerprint filters bg.", 'startsWith("bg.")' in CFG)
ck("C08 bg.* params do not enter glassFingerprint",
   'p.key.startsWith("glass.") || p.key.startsWith("geo.")' in CFG)
param_block = re.search(r'public enum Param \{(.*?)\n\s*public final String key;',
                        CFG, re.S).group(1)
ck("C09 Param count is 47 (44 + 3)",
   len(re.findall(r'^\s{8}[A-Z][A-Z0-9_]*\("', param_block, re.M)) == 47)
ck("C10 nextVersion is monotonic", "if (now <= sLastVersion)" in CFG)

# ---- D. BackdropLayer: image + video + snapshot invalidation ------------
ck("D01 applyImageStyle reads normalized mode", "ScaleMode.normalize(" in BD)
ck("D02 preset modes use ImageView ScaleType", "ScaleMode.scaleTypeOf(mode)" in BD)
ck("D03 custom image uses MATRIX", "ImageView.ScaleType.MATRIX" in BD)
ck("D04 custom image sets image matrix", "image.setImageMatrix(m)" in BD)
ck("D05 shared matrix used for custom image", "ScaleMode.matrix(scaleMode, scaleZoom" in BD)
ck("D06 video uses setTransform", "video.setTransform(m)" in BD)
ck("D07 video frame size read on prepared", "mp.getVideoWidth()" in BD)
ck("D08 video frame height read on prepared", "mp.getVideoHeight()" in BD)
ck("D09 applyVideoTransform defined", "private void applyVideoTransform()" in BD)
ck("D10 surface size change reapplies transform", "onSurfaceTextureSizeChanged" in BD)
ck("D11 snapshot cache compares styleKey", "styleKey.equals(snapshotStyleKey)" in BD)
ck("D12 snapshot records baked styleKey", "snapshotStyleKey = styleKey;" in BD)
ck("D13 dropSnapshot defined", "private void dropSnapshot()" in BD)
ck("D14 dropSnapshot preserves source bitmap", "s != current" in BD)
ck("D15 style change drops snapshot", "if (styleChanged) {" in BD)
ck("D16 style change repaints window backdrop", "refreshWindowBg();" in BD)
ck("D17 style change reapplies video transform", "applyVideoTransform();" in BD)
ck("D18 layout change rebuilds custom matrix", "addOnLayoutChangeListener" in BD)
ck("D19 new decode rebuilds custom matrix", "reapplyCustomMatrix(false);" in BD)
ck("D20 applyImageStyle runs BEFORE media branch",
   BD.index("applyImageStyle(cfg);") < BD.index('if ("video".equals(kind))'))
ck("D21 video branch applies transform",
   BD.index("showVideo(sourceName());") < BD.index("applyVideoTransform();\n            return;"))
ck("D22 fill mode is logged for device verification", "backdrop fill mode=" in BD)
ck("D23 default constructor ScaleType untouched",
   "image.setScaleType(ImageView.ScaleType.CENTER_CROP);" in BD)
ck("D24 p20 edge clamp kept", "srcRect.set(x0, y0, x1, y1);" in BD)
ck("D25 p66 WindowBackdropDrawable kept", "class WindowBackdropDrawable" in BD)
ck("D26 p66 hideMedia kept (no OEM white restore)", "private void hideMedia()" in BD)
ck("D27 release(true) still restores OEM", "if (restoreOem) {" in BD)
ck("D28 release(boolean) signature kept", "public void release(boolean restoreOem)" in BD)
ck("D29 drawRegion kept", "public boolean drawRegion(" in BD)
ck("D30 opacity / blur still applied", "image.setAlpha(opacity / 100f)" in BD)

# ---- E. SettingsActivity: preview card -----------------------------------
ck("E01 fillCard added to screen", "body.addView(fillCard());" in SA)
ck("E02 fillCard defined", "private View fillCard()" in SA)
ck("E03 preview ImageView field", "private ImageView preview;" in SA)
ck("E04 preview uses ScaleMode.LABELS", "android.R.layout.simple_spinner_item, ScaleMode.LABELS" in SA)
ck("E05 preview uses shared scaleTypeOf", "preview.setScaleType(ScaleMode.scaleTypeOf(mode))" in SA)
ck("E06 preview uses shared matrix", "ScaleMode.matrix(mode, zoom, fx, fy," in SA)
ck("E07 preview sets image matrix", "preview.setImageMatrix(m)" in SA)
ck("E08 preview declared MATRIX for custom", "preview.setScaleType(ImageView.ScaleType.MATRIX)" in SA)
ck("E09 current image is decoded for preview", "decodePreview(f)" in SA)
ck("E10 video first frame is used", "getFrameAtTime(0L)" in SA)
ck("E11 MediaMetadataRetriever used", "MediaMetadataRetriever" in SA)
ck("E12 demo grid fallback exists", "demoBitmap()" in SA)
ck("E13 preview bitmap recycled on rebuild", "previewBmp.recycle();" in SA)
ck("E14 preview source is described", 'previewSource = "演示网格' in SA)
ck("E15 custom sliders: zoom", "sliderRow(ConfigContract.Param.BG_SCALE_ZOOM)" in SA)
ck("E16 custom sliders: focusX", "sliderRow(ConfigContract.Param.BG_SCALE_FOCUS_X)" in SA)
ck("E17 custom sliders: focusY", "sliderRow(ConfigContract.Param.BG_SCALE_FOCUS_Y)" in SA)
ck("E18 custom box collapses", "customBox.setVisibility(ScaleMode.isCustom(mode)" in SA)
ck("E19 each mode has a one-line explanation", "ScaleMode.description(mode)" in SA)
ck("E20 spinner init does not write", "if (building) return;" in SA)
ck("E21 seekbar init does not write", "if (building || !fromUser) return;" in SA)
ck("E22 writeLive commits + debounced broadcast", 'ui.postDelayed(push, 120L)' in SA)
ck("E23 writeLive defined", "private void writeLive(" in SA)
bg_group = re.search(r'\{"背景细节"(.*?)\}\}', SA, re.S)
ck("E24 BG_SCALE removed from advanced group",
   bg_group is not None and "ConfigContract.Param.BG_SCALE," not in bg_group.group(1))
ck("E25 optionsOf now uses ScaleMode truth",
   "new String[][]{ScaleMode.LABELS, ScaleMode.VALUES}" in SA)
ck("E26 rangeOf zoom 50..400", "case BG_SCALE_ZOOM: return new int[]{50, 400};" in SA)
ck("E27 rangeOf focus 0..100", "case BG_SCALE_FOCUS_Y: return new int[]{0, 100};" in SA)
ck("E28 labels for custom params", "case BG_SCALE_ZOOM: return \"自定义放大\";" in SA)
ck("E29 descriptions for custom params", "case BG_SCALE_FOCUS_X: return \"仅「自定义」有效" in SA)
ck("E30 diagnostics reports filling", 'b.append("填充方式: ")' in SA)
ck("E31 HIDDEN still 2 items",
   len(re.findall(r'ConfigContract\.Param\.', re.search(
       r'HIDDEN\s*=\s*Arrays\.asList\((.*?)\);', SA, re.S).group(1))) == 2)
ck("E32 CORE still 6 items",
   len(re.findall(r'ConfigContract\.Param\.', re.search(
       r'CORE\s*=\s*Arrays\.asList\((.*?)\);', SA, re.S).group(1))) == 6)
ck("E33 VISIBLE still lists BG_SCALE", "ConfigContract.Param.BG_SCALE," in SA)
ck("E34 no page selector (slot fixed GLOBAL)",
   "slot = ConfigContract.Slot.GLOBAL;" in SA)
ck("E35 no save button control", 'flatButton("保存"' not in SA)

# ---- F. zero-regression: byte comparison with p66 rollback snapshots ----
snap = lambda n: read(os.path.join(ARCHIVE, n + "-p66-current.java"))
ck("F01 RenderController.java byte-identical to p66",
   RC == snap("RenderController"))
ck("F02 ConfigStore.java byte-identical to p66",
   java("ConfigStore") == snap("ConfigStore"))
ck("F03 BackdropLayer p66 snapshot archived", os.path.isfile(
    os.path.join(ARCHIVE, "BackdropLayer-p66-current.java")))
# The "-p66-current" snapshot was taken BEFORE p66's version bump, so its
# BUILD line still says p65. Normalise the BUILD line on both sides.
norm_build = lambda s: re.sub(r'BUILD\s*=\s*"[^"]+"', 'BUILD = "X"', s)
ck("F04 GlassEntry differs from p66 only by BUILD",
   norm_build(GE) == norm_build(snap("GlassEntry")))
ck("F05 p30 rebuildEffect still present somewhere", "rebuildEffect" in ALL)
ck("F06 p64 CHROME branch kept", 'log("CHROME ' in RC)
ck("F07 p64 chrome keeps toolbar protected", 'toolbar' in RC.lower())
ck("F08 p56 structural row strip kept", "private void latchListRowsNow()" in RC)
ck("F09 p41 latched re-strip kept", "restripLatched();" in RC)
ck("F10 p66 ext white hard gate kept", "private boolean extWhiteGate()" in RC)
ck("F11 p66 ext child strip kept", "stripExtCardChildren(stripAll)" in RC)
ck("F12 L1 hard gate kept", "slot == ConfigContract.Slot.L1" in ALL)
ck("F13 p35 static family split kept", "isStatic" in ALL)
ck("F14 p28 stable container binding kept", "glassFingerprint()" in ALL)

# ---- G. local javac stubs -------------------------------------------------
ck("G01 Matrix.setScale stub", "public void setScale(float sx, float sy)" in STUBS)
ck("G02 Matrix.postTranslate stub", "public void postTranslate(float dx, float dy)" in STUBS)
ck("G03 ScaleType FIT_XY/CENTER/MATRIX stub", "FIT_XY, CENTER, MATRIX" in STUBS)
ck("G04 ImageView.setImageMatrix stub", "public void setImageMatrix(" in STUBS)
ck("G05 TextureView.setTransform stub", "public void setTransform(" in STUBS)
ck("G06 MediaPlayer.getVideoWidth stub", "public int getVideoWidth()" in STUBS)
ck("G07 MediaPlayer.getVideoHeight stub", "public int getVideoHeight()" in STUBS)
ck("G08 OnLayoutChangeListener stub", "interface OnLayoutChangeListener" in STUBS)
ck("G09 addOnLayoutChangeListener stub", "addOnLayoutChangeListener(" in STUBS)
ck("G10 DisplayMetrics.widthPixels stub", "widthPixels" in STUBS)
ck("G11 MediaMetadataRetriever stub", '"android.media.MediaMetadataRetriever"' in STUBS)

passed = sum(1 for _n, ok in CHECKS if ok)
failed = [(n, ok) for n, ok in CHECKS if not ok]
for n, _ok in failed:
    print("FAIL " + n)
print("ASSERTIONS %d/%d" % (passed, len(CHECKS)))
sys.exit(0 if not failed else 1)
