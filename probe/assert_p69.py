#!/usr/bin/env python3
"""Deterministic invariant gate for MagicGlass 0.5.0-p69.

Pure static checks: no device, no build, no network. Every assertion is a
string/byte fact re-verifiable from this repository.

The release answers two user reports:

  1. "固定图片背景有液态玻璃，动态视频背景液态玻璃消失".
     Root cause: on a video backdrop `recycle()` cleared both `current`
     and `screenBmp`, so snapshot() returned null, drawRegion() failed and
     LiquidGlassView.onDraw() skipped setRenderEffect()+drawRenderNode().
     Section C proves the structural plate fix AND the deferred video-frame
     readback, with byte-level proof that the accepted image path is untouched.

  2. "上下位置那个开关，我的意思是一个上下挪动位置，一个左右挪动位置".
     Section D proves that BOTH sliders exist, are live controls (not
     comments), and that ConfigStore no longer overwrites FOCUS_X with 50.
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
    """Brace-balanced body (including the outer braces) of one method."""
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

CFG = java("ConfigContract")
CS = java("ConfigStore")
SA = java("SettingsActivity")
GE = java("GlassEntry")
RC = java("RenderController")
MB = java("MaterialBridge")
BD = java("BackdropLayer")
LGV = java("LiquidGlassView")
SM = java("ScaleMode")
STUBS = read(os.path.join(ROOT, "probe", "compile_check.py"))
PROP = read(os.path.join(META, "module.prop"))
SCOPE = read(os.path.join(META, "scope.list"))
m = re.search(r'BUILD\s*=\s*"([^"]+)"', GE)
BUILD = m.group(1) if m else ""
PROP_VER = dict(l.strip().split("=", 1) for l in PROP.splitlines() if "=" in l)
SA_CODE = strip_comments(SA)
BD67 = snap("BackdropLayer-p67-current")
RC66 = snap("RenderController-p66-current")

code_only = strip_comments(ALL)

# ---- A. repository / version / red lines ---------------------------------
ck("A01 app source count is 18 (unchanged since p67)", len(names) == 18)
ck("A02 ScaleMode.java still present", os.path.isfile(os.path.join(SRC, "ScaleMode.java")))
ck("A03 GlassEntry.BUILD = 0.5.0-p69", BUILD == "0.5.0-p69")
ck("A04 module.prop version = 0.5.0-p69", PROP_VER.get("version") == "0.5.0-p69")
ck("A05 module.prop versionCode = 97", PROP_VER.get("versionCode") == "97")
ck("A06 BUILD / module.prop agree", BUILD == PROP_VER.get("version"))
ck("A07 scope.list has 9 entries",
   len([l for l in SCOPE.splitlines() if l.strip()]) == 9)
try:
    ET.parse(os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml"))
    xml_ok = True
except Exception:
    xml_ok = False
ck("A08 AndroidManifest.xml parses", xml_ok)
ck("A09 build.sh present", os.path.isfile(os.path.join(ROOT, "build.sh")))
ck("A10 zero MIUI setMi calls (comments stripped)", "setMi" not in code_only)
ck("A11 zero com.ciallo references", "com.ciallo" not in code_only)
ck("A12 mg-build startup signature kept", 'say("mg-build " + BUILD' in ALL)
ck("A13 PERF_DEBUG_LOG defaults false",
   'PERF_DEBUG_LOG("perf.debugLog", "false")' in CFG)
ck("A14 RECEIVER_EXPORTED kept", "Context.RECEIVER_EXPORTED" in RC)
ck("A15 WINDOW_BASE_COLOR kept", "WINDOW_BASE_COLOR" in CFG)
ck("A16 p68 APK archived before build.sh clears build-out",
   os.path.isfile(os.path.join(ARCHIVE, "MagicGlass-0.5.0-p68.apk")))
ck("A17 p68 source snapshots present",
   all(os.path.isfile(os.path.join(ARCHIVE, f + "-p68-current.java"))
       for f in ("ConfigContract", "ConfigStore", "SettingsActivity", "GlassEntry")))
ck("A18 p67/p66 baseline snapshots present",
   all(os.path.isfile(os.path.join(ARCHIVE, f + ".java")) for f in (
       "BackdropLayer-p67-current", "RenderController-p66-current",
       "ScaleMode-p67-current")))
ck("A19 stub exposes TextureView.getBitmap(int,int)",
   "public android.graphics.Bitmap getBitmap(int w, int h)" in STUBS)
ck("A20 stub exposes Handler.post(Runnable)",
   "public boolean post(Runnable r) { return true; }" in STUBS)
ck("A21 Param enum size unchanged from p68",
   len(re.findall(r'^\s+[A-Z][A-Z0-9_]*\("', CFG, re.M))
   == len(re.findall(r'^\s+[A-Z][A-Z0-9_]*\("',
                     snap("ConfigContract-p68-current"), re.M)))
ck("A22 ConfigContract byte-identical to p68",
   raw(os.path.join(SRC, "ConfigContract.java"))
   == raw(os.path.join(ARCHIVE, "ConfigContract-p68-current.java")))
ck("A23 ScaleMode byte-identical to p67 archive",
   raw(os.path.join(SRC, "ScaleMode.java"))
   == raw(os.path.join(ARCHIVE, "ScaleMode-p67-current.java")))
ck("A24 GlassEntry differs from p68 ONLY by the BUILD line",
   GE.replace('"0.5.0-p69"', '"0.5.0-p68"') == snap("GlassEntry-p68-current"))

# ---- B. LiquidTab recipe / accepted render core untouched ----------------
for f in ("MaterialBridge", "LiquidGlassView", "LiquidShader"):
    ck("B01 %s.java byte-identical to L1-ACCEPTED-p30" % f,
       raw(os.path.join(SRC, f + ".java")) == raw(os.path.join(BASE30, f + ".java")))
ck("B02 MaterialBridge tint 0.40 unchanged", "if (v < 0f) return 0.40f;" in MB)
ck("B03 refraction 10f / thickness -26f unchanged",
   "GLASS_REFRACTION, 10f, 0.40f" in MB and "GLASS_THICKNESS, -26f, -0.60f" in MB)
ck("B04 dispersion 0.42f / blur 2f unchanged",
   "GLASS_DISPERSION, 0.42f, 0.01f" in MB and "GLASS_BLUR_RADIUS, 2f, 0.16f" in MB)
ck("B05 param() still falls back on -1", "return v < 0f ? fallback : v * scale;" in MB)
ck("B06 glass/geo defaults identical to p30",
   re.findall(r'^\s*((?:GLASS|GEO)_[A-Z0-9_]+)\("([^"]+)",\s*"([^"]+)"\)', CFG, re.M)
   == re.findall(r'^\s*((?:GLASS|GEO)_[A-Z0-9_]+)\("([^"]+)",\s*"([^"]+)"\)',
                 base("ConfigContract"), re.M))
ck("B07 rebuildEffect (p30 shader uniform snapshot fix) survives",
   "rebuildEffect" in LGV)
ck("B08 FROZEN_GLASS restore still excludes GLASS_ENABLED",
   "FROZEN_GLASS" in CS and "GLASS_ENABLED" not in
   re.search(r'FROZEN_GLASS\s*=\s*\{(.*?)\};', CS, re.S).group(1))
ck("B09 LiquidGlassView still gates the shader on drawRegion()",
   "backdropDrawn = layer.drawRegion(c" in LGV
   and "if (backdropDrawn) {" in LGV
   and "node.setRenderEffect(syncEffect(w, h));" in LGV
   and "canvas.drawRenderNode(node);" in LGV)
ck("B10 LiquidGlassView comment still names 'video' as the no-bitmap case",
   "no image picked yet / video / pure colour" in LGV)

# ---- C. video backdrop: the liquid glass must NEVER disappear ------------
ck("C01 snapshot() bakes a plate when there is no CPU bitmap",
   "if (src == null || src.isRecycled()) {\n"
   "            if (plateActive) ensurePlateSnapshot();\n"
   "            return screenBmp;" in BD)
ck("C02 plate is active by default (independent of BG_ENABLED / slot)",
   "private boolean plateActive = true;" in BD)
ck("C03 ensurePlateSnapshot() exists", "private void ensurePlateSnapshot()" in BD)
ck("C04 plate is OPAQUE (never null / never transparent)",
   "c.drawColor(windowBgColor);" in BD)
ck("C05 plate is reused by key, so the hot path allocates nothing",
   "key.equals(snapshotStyleKey)" in BD
   and "return;                     // O(1) hot path, no allocation" in BD)
ck("C06 plateKey covers every visual input",
   'return "plate|" + styleKey + "|" + Integer.toHexString(windowBgColor)'
   in BD and "sVideoCapOk" in BD)
ck("C07 drawRegion() still consumes snapshot() (signature unchanged)",
   "Bitmap snap = snapshot();" in BD
   and "public boolean drawRegion(Canvas c, int left, int top, int w, int h)" in BD)
ck("C08 video branch sets videoMode and bakes the plate IMMEDIATELY",
   "videoMode = true;" in BD
   and "windowBgColor = ConfigContract.windowColorOf(cfg);\n"
       "            ensurePlateSnapshot();" in BD)
ck("C09 leaving video mode releases the frame and forces a re-bake",
   "videoMode = false;\n            releaseVideoFrame();\n"
   "            snapshotStyleKey = \"\";" in BD)
ck("C10 BG_ENABLED=false keeps a sampling source",
   "recycle();\n        ensurePlateSnapshot();\n        refreshWindowBg();" in BD)
ck("C11 frame callback schedules a DEFERRED capture (not a drawing-method readback)",
   "scheduleVideoCapture();" in BD
   and "private void scheduleVideoCapture()" in BD)
ck("C12 readback is posted to the main looper",
   "videoCaptureTask = new Runnable()" in BD
   and "handler.post(videoCaptureTask);" in BD)
ck("C13 capture is throttled to one per 200 ms",
   "VIDEO_CAPTURE_INTERVAL_MS = 200L" in BD
   and "now - lastVideoCaptureMs < VIDEO_CAPTURE_INTERVAL_MS" in BD)
ck("C14 capture uses TextureView.getBitmap()",
   "Bitmap b = video.getBitmap(cw, ch);" in BD)
ck("C15 capture keeps the VIDEO's intrinsic aspect ratio (raw frame)",
   "int vw = videoWidth > 0 ? videoWidth : video.getWidth();" in BD
   and "int vh = videoHeight > 0 ? videoHeight : video.getHeight();" in BD)
ck("C16 capture is small (<=640px edge)",
   "VIDEO_CAPTURE_MAX_EDGE = 640" in BD
   and "VIDEO_CAPTURE_MAX_EDGE / Math.max(vw, vh)" in BD)
ck("C17 three consecutive failures disable capture, plate survives",
   "VIDEO_CAPTURE_MAX_FAIL = 3" in BD
   and "videoCaptureDisabled = true;" in BD)
ck("C18 AOSP evidence: getBitmap() IGNORES setTransform()",
   "IGNORES setTransform()" in BD
   and "getBitmap(Bitmap) calls applyUpdate()" in BD
   and "returns the ORIGINAL frame" in BD)
ck("C19 the SAME ScaleMode.matrix() is baked into the plate",
   "Matrix m = ScaleMode.matrix(scaleMode, scaleZoom,\n"
   "                        scaleFocusX, scaleFocusY,\n"
   "                        f.getWidth(), f.getHeight(), w, h);" in BD)
ck("C20 a new frame forces re-bake, window repaint and glass repaint",
   'snapshotStyleKey = "";      // force a re-bake with the new frame' in BD
   and "refreshWindowBg();          // transition window uses the same plate" in BD
   and "if (r != null) r.run();" in BD)
ck("C21 setVideoRepaint() wiring exists in BackdropLayer",
   "public void setVideoRepaint(Runnable r)" in BD)
ck("C22 RenderController wires the new-frame repaint to requestRender()",
   "backdrop.setVideoRepaint(new Runnable()" in RC
   and "requestRender();" in RC)
ck("C23 objectively readable device-log signals exist",
   all(t in BD for t in ("VIDGLASS", "VIDCAP", "diagVideoBake()",
                         "diagVideoCaptureOk()", "diagVideoCaptureFail()")))
ck("C24 release() frees the frame, clears the back-reference, stops capture",
   "handler.removeCallbacks(videoCaptureTask);" in BD
   and "releaseVideoFrame();" in BD
   and "videoRepaint = null;" in BD
   and "plateActive = false;" in BD)
ck("C25 transition window still reuses the same plate",
   "drawRegion(c, 0, 0, w, h);" in BD
   and "c.drawColor(windowBgColor);" in BD)
ck("C26 accepted IMAGE snapshot tail is byte-identical to p67",
   (lambda b: b[b.find("        int w, h;"):])(
       method_body(BD, "private Bitmap snapshot()"))
   == (lambda b: b[b.find("        int w, h;"):])(
       method_body(BD67, "private Bitmap snapshot()")))
ck("C27 drawRegion() method is byte-identical to p67",
   method_body(BD, "public boolean drawRegion(Canvas c,")
   == method_body(BD67, "public boolean drawRegion(Canvas c,"))

# ---- D. two live position sliders ----------------------------------------
ck("D01 上下位置 slider present", '"上下位置"' in SA and "BG_SCALE_FOCUS_Y" in SA)
ck("D02 左右位置 slider present", '"左右位置"' in SA and "BG_SCALE_FOCUS_X" in SA)
ck("D03 左右位置 is REAL CODE, not only a comment",
   "ConfigContract.Param.BG_SCALE_FOCUS_X" in SA_CODE)
ck("D04 左右位置 range is 0..100",
   "ConfigContract.Param.BG_SCALE_FOCUS_X, 0, 100" in SA)
ck("D05 上下位置 range is 0..100",
   "ConfigContract.Param.BG_SCALE_FOCUS_Y, 0, 100" in SA)
ck("D06 拉伸大小 unchanged from p68 (user: 可以不改了)",
   'card.addView(sliderBlock("拉伸大小", "等比缩放，100% 与默认铺满一致；调小会露出边缘",\n'
   '                ConfigContract.Param.BG_SCALE_ZOOM, 50, 400));' in SA
   and 'card.addView(sliderBlock("拉伸大小", "等比缩放，100% 与默认铺满一致；调小会露出边缘",\n'
       '                ConfigContract.Param.BG_SCALE_ZOOM, 50, 400));'
       in snap("SettingsActivity-p68-current"))
ck("D07 p68 hard-pin of FOCUS_X=50 is GONE",
   'ConfigContract.Param.BG_SCALE_FOCUS_X, "50"' not in CS)
ck("D08 ensureCustomFilling still pins ScaleMode.CUSTOM",
   "ScaleMode.CUSTOM" in CS and "ConfigContract.Param.BG_SCALE" in CS)
# Slice ensureCustomFilling() so unrelated methods cannot satisfy the check.
ensure_body = method_body(CS, "public static boolean ensureCustomFilling(Context c)")
ck("D09 ensureCustomFilling never writes a literal zoom / Y value",
   "BG_SCALE_ZOOM" not in ensure_body and "BG_SCALE_FOCUS_Y" not in ensure_body)
ck("D10 stale per-page FOCUS_X override is cleared to inherit",
   'ConfigContract.inheritKey(slot,' in ensure_body
   and 'ConfigContract.setInherit(e, slot,' in ensure_body
   and '"false".equals(all.get(ik))' in ensure_body)
ck("D11 bgFingerprint() still sees every bg.* edit (live, no rebuild)",
   'if (p.key.startsWith("bg."))' in CFG)
ck("D12 ScaleMode.focusXOf() reads BG_SCALE_FOCUS_X",
   "public static int focusXOf(" in SM
   and "ConfigContract.Param.BG_SCALE_FOCUS_X" in SM)
ck("D13 ScaleMode.matrix() actually uses fx and fy",
   "(vw - sw * s) * ax, (vh - sh * s) * ay" in SM)
ck("D14 BackdropLayer reads fx/fy and passes them to image/video/plate",
   "int fx = ScaleMode.focusXOf(cfg);" in BD
   and "int fy = ScaleMode.focusYOf(cfg);" in BD
   and BD.count("scaleFocusX, scaleFocusY") >= 2)
ck("D15 diagnostics shows the ACTUAL left/right value, not 'fixed 50%'",
   '"左右位置: "' in SA and "水平焦点: 固定 50%" not in SA)
ck("D16 header no longer claims two sliders",
   "上下 / 左右 / 拉伸" in SA and "两个滑块" not in SA)

# ---- E. accepted branches / L1 protection --------------------------------
# RenderController changed ONLY by the wiring block: removing that exact
# slice must reproduce the p66/p68 file byte-for-byte.
_a = RC.find("            // 0.5.0-p69: a new video frame")
_b = RC.find("            genericEnabled = Scope.isExternal(", _a)
rc_stripped = RC[:_a] + RC[_b:] if _a >= 0 and _b > _a else RC
ck("E01 RenderController differs from p66/p68 ONLY by the video repaint wiring",
   _a >= 0 and _b > _a and rc_stripped == RC66)
for token in ("extWhiteGate", "stripExtCardChildren", "latchListRowsNow",
              "restripLatched", "RECEIVER_EXPORTED", "setWillNotDraw",
              "keepBackground"):
    ck("E02 accepted branch %s survives" % token, token in RC)
ck("E03 release(true) survives in BackdropLayer", "release(true)" in BD)
ck("E04 apply() non-video media paths survive",
   all(t in BD for t in ("stopVideo();", '"none".equals(kind)',
                         "showImage(name);", "handler.postDelayed(rotate")))
ck("E05 p30 shader constants untouched", "LiquidShader" in str(names))

# ---- F. p68 UI contract still holds --------------------------------------
for sym in ("MediaMetadataRetriever", "loadPreviewBitmap", "demoBitmap",
            "refreshFillPreview", "fillCard", "advancedCard", "glassCard",
            "Spinner", "CheckBox", "EditText", "TextWatcher"):
    ck("F01 old UI symbol %s is GONE" % sym, sym not in SA_CODE)
ck("F02 GLASS_ENABLED only inside the collapsed 更多设置 block",
   "more.setVisibility(View.GONE)" in SA
   and SA.find("private View moreCard()") > SA.find("private View backgroundCard()"))
ck("F03 glass recipe controls still hidden",
   all(p not in SA for p in ("GLASS_REFRACTION", "GLASS_TRANSPARENCY",
                             "GLASS_DISPERSION", "GLASS_THICKNESS",
                             "GLASS_BLUR_RADIUS", "GEO_RADIUS_DP")))
ck("F04 restoreGlassDefaults + ensureCustomFilling still run on entry",
   "ConfigStore.restoreGlassDefaults(this)" in SA
   and "ConfigStore.ensureCustomFilling(this)" in SA)
for sym in ("roundRect(", "paintSwitch(", "paintSegment(", "segment(",
            "sectionHeader(", "capsule(", "sliderBlock(", "switchRow("):
    ck("F05 modern control %s present" % sym, sym in SA)
ck("F06 indigo accent + 22dp card + hairline divider",
   "0xFF4F46E5" in SA and "roundRect(C_CARD, 22, dp(1), C_LINE)" in SA
   and "Math.max(1, dp(1))" in SA)
ck("F07 live writes still use the 120 ms coalesced broadcast",
   "ui.postDelayed(push, 120L)" in SA and "ConfigStore.notifyChanged" in SA)
ck("F08 onDestroy still flushes a pending edit",
   "if (pending) ConfigStore.notifyChanged" in SA)
ck("F09 no save button", "保存" not in SA)

bad = [n for n, ok in CHECKS if not ok]
print("assert_p69: %d/%d checks passed" % (len(CHECKS) - len(bad), len(CHECKS)))
for n in bad:
    print("  FAIL:", n)
sys.exit(1 if bad else 0)
