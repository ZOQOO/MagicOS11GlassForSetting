#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""assert_p77.py -- deterministic (no device) invariants for 0.5.0-p77.

User (three items, verbatim):
  "第一次打开设置还是明显的加载效果也就是白色转变成选择的背景，看一下
   可否进行优化，在整体优化一下功耗。还有就是在账号中心L2级菜单最上面
   有矩形白卡删掉它，但是不要删除上面那个渐隐白色效果，还有就是在账号
   中心L2级菜单背景对比其他L2是有变化的，查找一下原因，改成和其他L2一样。"

Code-level root causes (device offline, so NO visual claim is made):

  1) FIRST-FRAME COLOUR JUMP. Every un-composited frame -- SystemUI's
     starting window (drawn BEFORE our process exists, p66 boundary), the
     decor window backdrop, and the opaque plate -- is painted with the
     FIXED near-white WINDOW_BASE_COLOR=0xFFEDEBF2, regardless of the
     user's dark / coloured wallpaper. p76 shortened the LATENCY but could
     not remove the visible "白 -> 背景" transition. p77 derives ONE average
     colour from the media in the MODULE process at pick time, persists it
     as BG_WINDOW_COLOR, and windowColorOf() reads that String. Zero
     per-frame work, zero extra IPC, zero bitmap in the starting window.

  2) 账号中心 TOP WHITE CARD. findings §2130 real-device log
     "slot=ext.id edges=[49..1223 49..1223] (2 of 3 white cards found)" and
     §999 "com.hihonor.id ... HOST_UIKIT": 账号中心 is the EXTERNAL process
     com.hihonor.id => slot EXT_ID. p64's CHROME strip hard gate is
     "slot == L2 || slot == L3", so it NEVER applied there and the
     HwToolbar #fff2f3f6 bar survived. p77 extends that gate by exactly
     ONE slot and adds an explicit keep for the translucent fade layer
     ("不要删除上面那个渐隐白色效果"), reusing p56/p64's measured predicate.

  3) 账号中心 BACKGROUND DIFFERS FROM OTHER L2. ConfigContract.resolve()
     reads a per-slot "inherit" flag for bg.* parameters. p63-p65 shipped a
     "作用页面" selector; p66 removed the UI but only offered a MANUAL
     "统一全部页面" button, so a stale ext.id tint/dim/opacity override can
     win forever. p77 enforces bg.* inheritance in the READ path for every
     non-L1 slot: impossible for a stale key to win, no device write, no
     broadcast, one fewer Map lookup. L1 takes the byte-identical old branch.

  4) POWER. ensurePlateSnapshot() called plateKey() (2 concatenations +
     Integer.toHexString) on EVERY draw frame while claiming "no allocation".
     p77 compares size/colour/style by scalar + reference, and calls
     plateKey() only when the plate is really re-baked. snapshot(),
     recycle(), drawRegion(), drawLiveVideoRegion(), applyImageStyle() stay
     byte-identical to p76.

Checks: A version truth, B colour source, C one-shot sampling, D L1 freeze,
E account card strip, F fade preservation, G background unification,
H power / no new frame work, I no dead-end regressions, J objective logs.
"""

import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'magicglass')
ARC = os.path.join(ROOT, 'probe', 'probe-report', 'archive')
BASE = os.path.join(ROOT, 'probe', 'probe-report', 'baselines',
                    'L1-ACCEPTED-p30', 'src', 'main', 'java', 'com', 'magicglass')

FAILS = []
CHECKS = [0]


def ck(name, ok):
    CHECKS[0] += 1
    print(('PASS  ' if ok else 'FAIL  ') + name)
    if not ok:
        FAILS.append(name)


def read(path):
    with open(path, encoding='utf-8') as f:
        return f.read()


def method_body(text, sig):
    i = text.find(sig)
    if i < 0:
        return ''
    j = text.find('{', i)
    if j < 0:
        return ''
    d = 0
    for k in range(j, len(text)):
        if text[k] == '{':
            d += 1
        elif text[k] == '}':
            d -= 1
            if d == 0:
                return text[j:k + 1]
    return ''


def code_only(text):
    text = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
    return re.sub(r'//[^\n]*', '', text)


CC = read(os.path.join(SRC, 'ConfigContract.java'))
CS = read(os.path.join(SRC, 'ConfigStore.java'))
SA = read(os.path.join(SRC, 'SettingsActivity.java'))
CB = read(os.path.join(SRC, 'ConfigBus.java'))
GE = read(os.path.join(SRC, 'GlassEntry.java'))
BD = read(os.path.join(SRC, 'BackdropLayer.java'))
RC = read(os.path.join(SRC, 'RenderController.java'))
EF = read(os.path.join(SRC, 'EdgeFade.java'))
GE76 = read(os.path.join(ARC, 'GlassEntry-p76-current.java'))
BD76 = read(os.path.join(ARC, 'BackdropLayer-p76-current.java'))
RC76 = read(os.path.join(ARC, 'RenderController-p76-current.java'))
ALL = ''.join(read(os.path.join(SRC, f)) for f in os.listdir(SRC)
              if f.endswith('.java'))
ALL_CODE = code_only(ALL)

prop = read(os.path.join(ROOT, 'app', 'src', 'main', 'resources',
                         'META-INF', 'xposed', 'module.prop'))

resolve = method_body(CC, 'public static Snapshot resolve(Source src, Slot slot)')
wco = method_body(CC, 'public static int windowColorOf(Snapshot cfg)')
derive = method_body(CS, 'public static String deriveWindowColor(')
write_wc = method_body(CS, 'public static boolean writeWindowColor(')
ensure_wc = method_body(CS, 'public static boolean ensureWindowColor(')
on_pick = method_body(CS, 'public static void onMediaPicked(')
plate = method_body(BD, 'private void ensurePlateSnapshot()')
plate_key = method_body(BD, 'private String plateKey()')
fade = method_body(RC, 'private static boolean isFadeWhiteLayer(')
ext_child = method_body(RC, 'private void stripExtWhiteChild(')
strip_rows = method_body(RC, 'private void stripRowsIn(')

# ---- A. version truth ----------------------------------------------------
ck('A01 BUILD is 0.5.0-p77', 'public static final String BUILD = "0.5.0-p77";' in GE)
ck('A02 module.prop version/versionCode match',
   'version=0.5.0-p77' in prop and 'versionCode=105' in prop)
ck('A03 BUILD constant is p77 (p76 may survive only in history comments)',
   'public static final String BUILD = "0.5.0-p76"' not in code_only(GE))
ck('A04 p77 source snapshots archived (18 files)',
   len([f for f in os.listdir(ARC) if f.endswith('-p77-current.java')]) == 18)

# ---- B. first-frame colour: ONE source of truth --------------------------
ck('B01 BG_WINDOW_COLOR exists and is a bg.* parameter',
   'BG_WINDOW_COLOR("bg.windowColor", "")' in CC)
ck('B02 windowColorOf() prefers BG_WINDOW_COLOR over tint',
   wco.find('Param.BG_WINDOW_COLOR') < wco.find('Param.BG_TINT_COLOR'))
ck('B03 windowColorOf() forces OPAQUE (alpha FF)',
   '0xFF000000' in wco)
ck('B04 parse failure falls back instead of throwing',
   'catch (Throwable ignored)' in wco and 'WINDOW_BASE_COLOR' in wco)
ck('B05 SystemUI starting window reads the SAME source',
   'ConfigContract.windowColorOf(' in CB
   and 'Slot.GLOBAL' in method_body(CB, 'public static int windowColor()'))
ck('B06 host window colour reads the SAME source (video + image)',
   BD.count('ConfigContract.windowColorOf(cfg)') == 2)
ck('B07 plate paints windowBgColor (so it inherits the sampled colour)',
   'c.drawColor(windowBgColor)' in plate)
ck('B08 BG_WINDOW_COLOR is NOT in glassFingerprint (container pool stable)',
   'BG_WINDOW_COLOR' not in method_body(CC, 'public String glassFingerprint()'))
ck('B09 BG_WINDOW_COLOR IS in bgFingerprint via bg. prefix',
   'p.key.startsWith("bg.")' in method_body(CC, 'public String bgFingerprint()')
   and 'bg.windowColor' in CC)

# ---- C. one-shot sampling, never in a frame path -------------------------
ck('C01 deriveWindowColor() is a module-process static helper',
   derive.startswith('{') and 'static' in CS[CS.find('public static String deriveWindowColor'):CS.find('public static String deriveWindowColor') + 40])
ck('C02 image path decodes a SMALL sample (inSampleSize), not full size',
   'inSampleSize' in derive and 'inJustDecodeBounds' in derive)
ck('C03 video path takes ONE frame only',
   'getFrameAtTime(0)' in derive and derive.count('getFrameAtTime(') == 1)
ck('C04 average colour via 1x1 scaled bitmap + getPixel',
   'createScaledBitmap(frame, 1, 1, true)' in derive
   and 'getPixel(0, 0)' in derive)
ck('C05 pure-black video frame is lifted (no p42 black window)',
   'r + g + b < 0x30' in derive and 'Math.max(r, 0x18)' in derive)
ck('C06 temp bitmaps are recycled (no leak)',
   'one.recycle()' in derive and 'frame.recycle()' in derive
   and 'mmr.release()' in derive)
ck('C07 writeWindowColor() writes GLOBAL only',
   'ConfigContract.Slot.GLOBAL' in write_wc
   and 'ConfigContract.Param.BG_WINDOW_COLOR' in write_wc)
ck('C08 idempotent: skips when key already present (no re-decode on open)',
   'if (!force && all.get(k) != null' in write_wc)
ck('C09 migration runs on settings open',
   'ConfigStore.ensureWindowColor(this)' in method_body(SA, 'protected void onCreate('))
ck('C10 fresh pick samples immediately',
   'ConfigStore.onMediaPicked(this, name, pickedKind)' in SA)
ck('C11 fresh pick coalesces into the EXISTING debounced broadcast',
   'true, false);' in on_pick and 'ui.postDelayed(push, 60L)' in ALL)
ck('C12 upgrade migration broadcasts (so hosts refresh without re-pick)',
   'false, true);' in ensure_wc)
ck('C13 sampling is NOT referenced from any frame/render file',
   'deriveWindowColor' not in BD and 'deriveWindowColor' not in RC)

# ---- D. L1 frozen: byte-identical render path ----------------------------
for name, sig in [('drawRegion()', 'public boolean drawRegion(Canvas c,'),
                  ('snapshot()', 'private Bitmap snapshot()'),
                  ('recycle()', 'private void recycle()'),
                  ('drawLiveVideoRegion()', 'private boolean drawLiveVideoRegion('),
                  ('applyImageStyle()', 'private void applyImageStyle(')]:
    a, b = method_body(BD, sig), method_body(BD76, sig)
    ck('D ' + name + ' byte-identical to p76 (non-empty)', bool(a and b) and a == b)
for f in ('MaterialBridge.java', 'LiquidGlassView.java', 'LiquidShader.java'):
    ck('D ' + f + ' byte-identical to L1 p30 baseline',
       open(os.path.join(SRC, f), 'rb').read() == open(os.path.join(BASE, f), 'rb').read())
ck('D EdgeFade.java untouched (fade band FADE_DP=60 preserved)',
   EF == read(os.path.join(ARC, 'EdgeFade-p76-current.java')))
ck('D isOpaqueSolidFill() byte-identical to p76',
   method_body(RC, 'private static boolean isOpaqueSolidFill(')
   == method_body(RC76, 'private static boolean isOpaqueSolidFill('))
ck('D keepBackground() byte-identical to p76',
   method_body(RC, 'private static boolean keepBackground(')
   == method_body(RC76, 'private static boolean keepBackground('))
ck('D isChromeClass() byte-identical to p76',
   method_body(RC, 'private static boolean isChromeClass(')
   == method_body(RC76, 'private static boolean isChromeClass('))

# ---- E. 账号中心 (EXT_ID) white card strip -------------------------------
ck('E01 accountIdGate() is EXACTLY one slot + genericEnabled',
   'private boolean accountIdGate()' in RC
   and 'return genericEnabled && slot == ConfigContract.Slot.EXT_ID;'
   in method_body(RC, 'private boolean accountIdGate()'))
ck('E02 CHROME gate now includes accountIdGate()',
   'accountChrome' in strip_rows and 'accountIdGate()' in strip_rows)
ck('E03 CHROME gate still includes L2/L3 (no regression)',
   'slot == ConfigContract.Slot.L2' in strip_rows
   and 'slot == ConfigContract.Slot.L3' in strip_rows)
ck('E04 CHROME strip still requires chrome class + measured opaque fill',
   'isChromeClass(cls)' in strip_rows and 'isOpaqueSolidFill(chromeBg)' in strip_rows)
ck('E05 stripExtWhiteChild() now covers 账号中心',
   'accountIdGate()' in ext_child and '!extWhiteGate() && !account' in ext_child)
ck('E06 extWhiteGate() itself UNCHANGED (controlcenter only)',
   'return genericEnabled && slot == ConfigContract.Slot.EXT_CONTROLCENTER;'
   in method_body(RC, 'private boolean extWhiteGate()'))
ck('E07 stripExtCardChildren() still gated on extWhiteGate() only',
   'if (!extWhiteGate() || stripAll == null) return;'
   in method_body(RC, 'private void stripExtCardChildren('))
ck('E08 bounded walk reused (no new tree traversal method)',
   RC.count('stripRowsIn(View v, int depth, int[] budget)') == 1)
acct_gate = method_body(RC, 'private boolean accountIdGate()')
ck('E09 account gate contains no L1 reference at all',
   'Slot.L1' not in acct_gate and 'Slot.GLOBAL' not in acct_gate)
ck('E10 account gate is a single exact slot comparison',
   acct_gate.count('ConfigContract.Slot.') == 1
   and 'slot == ConfigContract.Slot.EXT_ID' in acct_gate)

# ---- F. preserve the fading white effect ---------------------------------
ck('F01 isFadeWhiteLayer() exists', bool(fade))
ck('F02 blur / gradient / fade classes are protected',
   'n.contains("blur")' in fade and 'n.contains("gradient")' in fade
   and 'n.contains("fade")' in fade)
ck('F03 TRANSLUCENT fills are protected',
   'bg.getOpacity() != -1' in fade and 'fg.getOpacity() != -1' in fade)
ck('F04 OPAQUE near-white card is NOT misclassified as fade',
   'if (bg != null && bg.getOpacity() != -1) return true;' in fade
   and 'isOpaqueSolidFill(bg)' in ext_child)
ck('F05 "transparent" is NOT a keep word (HnTransparentPattern is opaque white)',
   'n.contains("transparent")' not in code_only(fade))
ck('F06 fade guard applied BEFORE the CHROME strip on account pages',
   'if (!accountChrome || !isFadeWhiteLayer(v))' in strip_rows)
ck('F07 fade guard applied in stripExtWhiteChild on account pages',
   'if (account && isFadeWhiteLayer(v)) return;' in ext_child)
ck('F08 EdgeFade FADE_DP still 60 (user accepted)',
   'public static final float FADE_DP = 60f;' in EF)

# ---- G. background unification (read path, no device write) -------------
ck('G01 resolve() forces inheritance for bg.* parameters',
   'backgroundOnly' in resolve and 'p.key.startsWith("bg.")' in resolve)
ck('G02 L1 is EXCLUDED from the enforcement (frozen p30)',
   'slot != Slot.L1' in resolve)
ck('G03 glass parameters keep per-slot ability',
   'boolean inherited = slot == Slot.GLOBAL || backgroundOnly\n                    || rawBool(src, inheritKey(slot, p), true);' in resolve)
ck('G04 enforcement is READ-only (no editor / write / notify)',
   'SharedPreferences.Editor' not in resolve
   and 'notifyChanged' not in resolve and 'e.apply' not in resolve)
ck('G05 BGUNIFY objective log present for device comparison',
   'BGUNIFY slot=' in RC and 'windowColorOf(cfg)' in RC)
ck('G06 BGUNIFY only on change + verbose (no frame-path string)',
   'GlassEntry.verbose() && (slotChanged || force)' in RC)

# ---- H. power / no new per-frame work ------------------------------------
ck('H01 plateKey() is NO LONGER called on the hot path',
   plate.find('String key = plateKey();') > plate.find('if (cached != null'))
ck('H02 hot path uses scalar + reference comparisons only',
   'windowBgColor == plateBakedColor' in plate
   and 'styleKey == plateBakedStyle' in plate
   and 'snapshotStyleKey == plateBakedKey' in plate)
# Comments describe the OLD behaviour ("plateKey() built a new String on
# every frame"), so strip comments before counting real call sites.
plate_code = code_only(plate)
plate_hot = plate_code[:plate_code.find('try {')]
ck('H03 plateKey() is called EXACTLY once, at bake time only',
   'String key = plateKey();' in plate_code
   and plate_code.count('plateKey()') == 1
   and 'plateKey()' not in plate_hot)
ck('H04 no String concat / toHexString in hot-path code',
   'Integer.toHexString' not in plate_hot
   and '"plate|"' not in plate_hot)
ck('H05 no new timer / post / invalidate loop in p77 code',
   'postDelayed' not in code_only(CS) and 'new Timer' not in ALL_CODE)
ck('H06 p76 power design intact (event-driven cache + bitmap cache)',
   'sCached' in CB and 'sImageCache' in BD and 'sWarmUpDone' in CB)
ck('H07 ensurePlateSnapshot still allocates ONLY on a real change',
   'Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)' in plate
   and plate.count('createBitmap') == 1)

# ---- I. no dead-end regressions -----------------------------------------
# p72 killed the CONTINUOUS CPU capture chain (p69-p71). The ONE-SHOT
# transition frame of p74/p75 (video.getBitmap inside captureTransitionFrame)
# is a verified, separate exception and must remain.
ck('I01 no continuous CPU video capture symbols (p72 revert intact)',
   'captureVideoFrame' not in ALL_CODE
   and 'VIDEO_CAPTURE_INTERVAL_MS' not in ALL_CODE
   and 'bakeVideoFrameIntoPlate' not in ALL_CODE
   and 'sVideoCapOk' not in ALL_CODE)
ck('I01b exactly ONE getBitmap, and only in the one-shot transition frame',
   ALL_CODE.count('video.getBitmap(') == 1
   and 'video.getBitmap(' in code_only(method_body(BD, 'private void captureTransitionFrame()')))
ck('I02 GPU live layer intact (p73 verified method unchanged)',
   'drawLiveVideoRegion' in BD and 'mRenderNode' in BD)
ck('I03 one-shot transition frame intact (p74/p75)',
   'captureTransitionFrame' in BD and 'getTransform(null)' in BD)
ck('I04 no MIUI inheritance (setMi / com.ciallo)',
   'setMi' not in ALL_CODE and 'com.ciallo' not in ALL_CODE)
ck('I05 all 18 app sources tracked',
   len([f for f in os.listdir(SRC) if f.endswith('.java')]) == 18)

# ---- J. objective / falsifiable device logs ------------------------------
ck('J01 ACCOUNTCHROME / ACCOUNTWHITE log markers exist',
   'ACCOUNTCHROME ' in RC and 'ACCOUNTWHITE ' in RC)
ck('J02 window colour sample log exists',
   'window colour sample=' in CS)
ck('J03 starting-window colour log exists (p66 path)',
   'starting-window colour=' in GE)
ck('J04 logs are verbose-gated where they could repeat',
   'GlassEntry.verbose()' in strip_rows and 'GlassEntry.verbose()' in ext_child)

print('\n%d checks, %d failures' % (CHECKS[0], len(FAILS)))
if FAILS:
    print('FAILED:')
    for f in FAILS:
        print('  - ' + f)
    sys.exit(1)
print('ALL_GREEN')
sys.exit(0)
