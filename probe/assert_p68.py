#!/usr/bin/env python3
"""Deterministic invariant gate for MagicGlass 0.5.0-p68.

Pure static checks: no device, no build, no network. Every assertion is a
string/byte fact re-verifiable from this repository.

The central question of this release was "were the LiquidTab glass recipe
parameters changed?". Section B answers it with byte-level comparisons against
the L1-ACCEPTED-p30 baseline; section C proves the restore is real (config
keys are rewritten to defaults), not just a UI hiding trick.
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


def glass_defs(text):
    """All GLASS_*/GEO_* enum declarations with their default values."""
    return re.findall(
        r'^\s*((?:GLASS|GEO)_[A-Z0-9_]+)\("([^"]+)",\s*"([^"]+)"\)',
        text, re.M)


names = sorted(f for f in os.listdir(SRC) if f.endswith(".java"))
ALL = "\n".join(read(os.path.join(SRC, n)) for n in names)


def strip_comments(s):
    s = re.sub(r'/\*.*?\*/', '', s, flags=re.S)
    return re.sub(r'//[^\n]*', '', s)

CFG = java("ConfigContract")
CS = java("ConfigStore")
SA = java("SettingsActivity")
GE = java("GlassEntry")
RC = java("RenderController")
MB = java("MaterialBridge")
BD = java("BackdropLayer")
SM = java("ScaleMode")
STUBS = read(os.path.join(ROOT, "probe", "compile_check.py"))
PROP = read(os.path.join(META, "module.prop"))
SCOPE = read(os.path.join(META, "scope.list"))
m = re.search(r'BUILD\s*=\s*"([^"]+)"', GE)
BUILD = m.group(1) if m else ""
PROP_VER = dict(l.strip().split("=", 1) for l in PROP.splitlines() if "=" in l)
SA_CODE = strip_comments(SA)

# ---- A. repository / version / red lines ---------------------------------
ck("A01 app source count is 18 (unchanged since p67)", len(names) == 18)
ck("A02 ScaleMode.java still present", os.path.isfile(os.path.join(SRC, "ScaleMode.java")))
ck("A03 GlassEntry.BUILD = 0.5.0-p68", BUILD == "0.5.0-p68")
ck("A04 module.prop version = 0.5.0-p68", PROP_VER.get("version") == "0.5.0-p68")
ck("A05 module.prop versionCode = 96", PROP_VER.get("versionCode") == "96")
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
code_only = re.sub(r'/\*.*?\*/', '', ALL, flags=re.S)
code_only = re.sub(r'//[^\n]*', '', code_only)
ck("A10 zero MIUI setMi calls", "setMi" not in code_only)
ck("A11 zero com.ciallo references", "com.ciallo" not in code_only)
ck("A12 mg-build startup signature kept", 'say("mg-build " + BUILD' in ALL)
ck("A13 PERF_DEBUG_LOG defaults false", 'PERF_DEBUG_LOG("perf.debugLog", "false")' in CFG)
ck("A14 RECEIVER_EXPORTED kept", "Context.RECEIVER_EXPORTED" in RC)
ck("A15 WINDOW_BASE_COLOR kept", "WINDOW_BASE_COLOR" in CFG)
ck("A16 stub has Gravity.CENTER for capsule/segment text",
   "public static final int CENTER = 17;" in STUBS)
ck("A17 Param enum size unchanged from p67 (no key added/removed)",
   len(re.findall(r'^\s+[A-Z][A-Z0-9_]*\("', CFG, re.M))
   == len(re.findall(r'^\s+[A-Z][A-Z0-9_]*\("',
                     snap("ConfigContract-p67-current"), re.M)))

# ---- B. LiquidTab recipe was NEVER changed in code -----------------------
# These three files own the reference constants (tint 0.40, refraction 10dp,
# thickness -26dp, dispersion 0.42, blur 2f) and the AGSL shader.
for f in ("MaterialBridge", "LiquidGlassView", "LiquidShader"):
    ck("B01 %s.java byte-identical to p30 baseline" % f,
       raw(os.path.join(SRC, f + ".java")) == raw(os.path.join(BASE30, f + ".java")))

ck("B02 MaterialBridge still uses the LiquidTab tint 0.40",
   "if (v < 0f) return 0.40f;" in MB)
ck("B03 MaterialBridge still uses refraction 10f / thickness -26f",
   "GLASS_REFRACTION, 10f, 0.40f" in MB and "GLASS_THICKNESS, -26f, -0.60f" in MB)
ck("B04 MaterialBridge still uses dispersion 0.42f / blur 2f",
   "GLASS_DISPERSION, 0.42f, 0.01f" in MB and "GLASS_BLUR_RADIUS, 2f, 0.16f" in MB)
ck("B05 param() still falls back when config is -1", "return v < 0f ? fallback : v * scale;" in MB)
ck("B06 glass/geo DEFAULT values identical to p30 baseline",
   glass_defs(CFG) == glass_defs(base("ConfigContract")))
ck("B07 the five recipe params still default to -1 (follow LiquidTab)",
   all(any(n == p and v == "-1" for n, _k, v in glass_defs(CFG)) for p in (
       "GLASS_TRANSPARENCY", "GLASS_REFRACTION", "GLASS_DISPERSION",
       "GLASS_THICKNESS", "GLASS_BLUR_RADIUS")))
ck("B08 ConfigContract diff vs p30 contains no glass/geo default change",
   len([1 for l in CFG.splitlines()
        if re.match(r'^\s*(?:GLASS|GEO)_[A-Z0-9_]+\("', l)
        and l not in base("ConfigContract").splitlines()]) == 0)

# ---- C. the restore is REAL (config keys rewritten, not just hidden) -----
ck("C01 ConfigStore.restoreGlassDefaults exists",
   "public static boolean restoreGlassDefaults(Context c)" in CS)
ck("C02 ConfigStore.ensureCustomFilling exists",
   "public static boolean ensureCustomFilling(Context c)" in CS)
ck("C03 FROZEN_GLASS list declared", "FROZEN_GLASS" in CS)
frozen = re.search(r'FROZEN_GLASS\s*=\s*\{(.*?)\};', CS, re.S)
frozen_body = frozen.group(1) if frozen else ""
for p in ("GLASS_TRANSPARENCY", "GLASS_REFRACTION", "GLASS_DISPERSION",
          "GLASS_THICKNESS", "GLASS_BLUR_RADIUS", "GEO_RADIUS_DP",
          "GEO_H_INSET_DP", "GEO_V_INSET_DP", "GEO_UNIFORM_CARD_WIDTH",
          "GLASS_GROUP_CARDS", "GLASS_STANDALONE_CARDS",
          "GLASS_GENERIC_CARDS", "GLASS_FORCE_BACKING", "PERF_TRACK_TAIL_MS"):
    ck("C04 FROZEN_GLASS covers %s" % p, p in frozen_body)
ck("C05 FROZEN_GLASS deliberately EXCLUDES the GLASS_ENABLED on/off switch",
   "GLASS_ENABLED" not in frozen_body)
ck("C06 restore writes p.def for every slot",
   "ConfigContract.put(e, slot, p, p.def)" in CS)
ck("C07 restore clears per-slot overrides (inherit=true)",
   'e.putString(ik, "true")' in CS)
ck("C08 restore is idempotent (only differing keys are written)",
   "if (cur == null || !p.def.equals(cur))" in CS)
ck("C09 restore broadcasts after a real change",
   "if (changed) {" in CS and "notifyChanged(c);" in CS)
ck("C10 ensureCustomFilling pins ScaleMode.CUSTOM",
   "ScaleMode.CUSTOM" in CS and "ConfigContract.Param.BG_SCALE" in CS)
ck("C11 ensureCustomFilling pins horizontal focus to 50",
   'ConfigContract.Param.BG_SCALE_FOCUS_X, "50"' in CS)
ck("C12 ensureCustomFilling leaves zoom / vertical focus alone",
   "BG_SCALE_ZOOM" not in frozen_body and "BG_SCALE_FOCUS_Y" not in frozen_body)
ck("C13 SettingsActivity calls restore on entry",
   "ConfigStore.restoreGlassDefaults(this)" in SA)
ck("C14 SettingsActivity calls ensureCustomFilling on entry",
   "ConfigStore.ensureCustomFilling(this)" in SA)
ck("C15 统一全部页面 re-applies the restore after clearing overrides",
   SA.count("ConfigStore.restoreGlassDefaults(SettingsActivity.this)") >= 1)

# ---- D. custom background is the ONLY filling model ----------------------
ck("D01 BG_SCALE default is custom", 'BG_SCALE("bg.scale", "custom")' in CFG)
ck("D02 BG_SCALE_ZOOM default is 100 (== frozen centerCrop)",
   'BG_SCALE_ZOOM("bg.scaleZoom", "100")' in CFG)
ck("D03 100% custom is algebraically centerCrop in ScaleMode",
   "s = mx * z;" in SM and ("float z = zoom / 100f;" in SM))
ck("D04 SettingsActivity has 上下位置 slider",
   '"上下位置"' in SA and "BG_SCALE_FOCUS_Y" in SA)
ck("D05 SettingsActivity has 拉伸大小 slider",
   '"拉伸大小"' in SA and "BG_SCALE_ZOOM" in SA)
ck("D06 range 上下位置 is 0..100", "BG_SCALE_FOCUS_Y, 0, 100" in SA)
ck("D07 range 拉伸大小 is 50..400", "BG_SCALE_ZOOM, 50, 400" in SA)
ck("D08 horizontal focus is NOT exposed in the UI",
   "ConfigContract.Param.BG_SCALE_FOCUS_X" not in SA_CODE)
ck("D09 preset spinner / BG_SCALE row is gone from the UI",
   "ConfigContract.Param.BG_SCALE)" not in SA and "ScaleMode.LABELS" not in SA)
ck("D10 scale mode still has a single source of truth",
   "public static String normalize(String raw)" in SM)
ck("D11 styleKey still invalidates the baked screen snapshot",
   "public static String styleKey(" in SM and "styleKey" in BD)

# ---- E. glass settings hidden, preview deleted ---------------------------
for sym in ("MediaMetadataRetriever", "previewBmp", "loadPreviewBitmap",
            "decodePreview", "videoFrame", "demoBitmap", "refreshFillPreview",
            "fillCard", "advancedCard", "hiddenCard", "glassCard",
            "groupTitle", "rowFor", "ArrayAdapter", "Spinner",
            "CheckBox", "EditText", "TextWatcher"):
    ck("E01 old UI symbol %s is GONE (comments excluded)" % sym,
       sym not in SA_CODE)
ck("E02 no VISIBLE whitelist / GROUPS wall / HIDDEN list",
   "private static final List<ConfigContract.Param> VISIBLE" not in SA
   and "Object[][] GROUPS" not in SA
   and "private static final List<ConfigContract.Param> HIDDEN" not in SA)
for p in ("GLASS_TRANSPARENCY", "GLASS_REFRACTION", "GLASS_DISPERSION",
          "GLASS_THICKNESS", "GLASS_BLUR_RADIUS", "GEO_RADIUS_DP",
          "GEO_H_INSET_DP", "GEO_V_INSET_DP"):
    ck("E03 recipe param %s not rendered in the UI" % p, p not in SA)
# Slice by method signature, stopping at the next javadoc: a regex ending at
# "private View moreCard()" would otherwise swallow moreCard's own javadoc,
# which mentions GLASS_ENABLED / 液态玻璃 and would produce a false positive.
i_bg = SA.find("private View backgroundCard()")
j_bg = SA.find("/**", i_bg)
bg_body = SA[i_bg:j_bg] if i_bg >= 0 and j_bg > i_bg else ""
i_more = SA.find("private View moreCard()")
j_more = SA.find("private View footerNote()", i_more)
more_body = SA[i_more:j_more] if i_more >= 0 and j_more > i_more else ""
ck("E04 GLASS_ENABLED only inside the COLLAPSED 更多设置 block",
   "GLASS_ENABLED" in more_body and "GLASS_ENABLED" not in bg_body)
ck("E05 更多设置 block is collapsed by default",
   "more.setVisibility(View.GONE)" in more_body)
ck("E06 main card has no glass settings at all",
   "液态玻璃" not in bg_body)
ck("E07 status pill reports the restore", "液态玻璃参数已恢复原始值" in SA)
ck("E08 no save button", "保存" not in SA)
ck("E09 diagnostics states the recipe was restored",
   "已恢复 LiquidTab 原始参数" in SA)

# ---- F. modern visual layer ---------------------------------------------
for sym in ("roundRect(", "paintSwitch(", "paintSegment(", "segment(",
            "sectionHeader(", "capsule(", "sliderBlock(", "switchRow("):
    ck("F01 modern control %s present" % sym, sym in SA)
ck("F02 C_ACCENT is the indigo accent", "0xFF4F46E5" in SA)
ck("F03 cards have 22dp radius + hairline border",
   "roundRect(C_CARD, 22, dp(1), C_LINE)" in SA)
ck("F04 divider is a hairline", "Math.max(1, dp(1))" in SA)
ck("F05 live writes still use the 120 ms coalesced broadcast",
   "ui.postDelayed(push, 120L)" in SA and "ConfigStore.notifyChanged" in SA)
ck("F06 media import still writes source/kind/enabled",
   "ConfigContract.Param.BG_SOURCE" in SA and "ConfigContract.Param.BG_ENABLED" in SA)
ck("F07 random pool still supported", "MODE_POOL" in SA)
ck("F08 onDestroy still flushes a pending edit", "if (pending) ConfigStore.notifyChanged" in SA)

# ---- G. negative protection: accepted render path untouched -------------
ck("G01 RenderController byte-identical to p66 archive",
   raw(os.path.join(SRC, "RenderController.java"))
   == raw(os.path.join(ARCHIVE, "RenderController-p66-current.java")))
ck("G02 BackdropLayer byte-identical to p67 archive",
   raw(os.path.join(SRC, "BackdropLayer.java"))
   == raw(os.path.join(ARCHIVE, "BackdropLayer-p67-current.java")))
ck("G03 ScaleMode byte-identical to p67 archive",
   raw(os.path.join(SRC, "ScaleMode.java"))
   == raw(os.path.join(ARCHIVE, "ScaleMode-p67-current.java")))
ck("G04 GlassEntry differs from p67 archive ONLY by the BUILD line",
   GE.replace('"0.5.0-p68"', '"0.5.0-p67"') == snap("GlassEntry-p67-current"))
for token in ("extWhiteGate", "stripExtCardChildren",
              "latchListRowsNow", "restripLatched",
              "RECEIVER_EXPORTED", "setWillNotDraw", "keepBackground"):
    ck("G05 accepted branch %s survives in RenderController" % token,
       token in RC)
# These two live in other files -- p67's gate checked the whole tree, here we
# name the owning file so a deletion cannot hide behind the wrong source.
ck("G05b p30 shader rebuildEffect survives in LiquidGlassView",
   "rebuildEffect" in java("LiquidGlassView"))
ck("G05c release(true) survives in BackdropLayer",
   "release(true)" in BD)
ck("G06 p67 APK archived before build.sh clears build-out",
   os.path.isfile(os.path.join(ARCHIVE, "MagicGlass-0.5.0-p67.apk")))
ck("G07 p68 source snapshots archived",
   all(os.path.isfile(os.path.join(ARCHIVE, f + "-p68-current.java"))
       for f in ("ConfigContract", "ConfigStore", "SettingsActivity", "GlassEntry")))

bad = [n for n, ok in CHECKS if not ok]
print("assert_p68: %d/%d checks passed" % (len(CHECKS) - len(bad), len(CHECKS)))
for n in bad:
    print("  FAIL:", n)
sys.exit(1 if bad else 0)
