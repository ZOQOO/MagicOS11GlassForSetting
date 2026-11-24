#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""assert_p106.py -- deterministic (no device) invariants for 0.5.0-p106.

User chose the route "按 id 拆两层": p105 measured blur_toolbar as
HnTransToolbar with bg=ColorDrawable#0, i.e. the white block is NOT in the
toolbar background. p106 splits the two top OEM layers by RESOURCE ID:/nblur_wallpaper_bg -> alpha 0, top_gradient_bg / gradient / fade -> HARD KEEP.

Device survey `l2_topbar_survey_p105.txt` established that the remaining
pages are external processes -> slot ext.*, and that their title bar is a
plain android.view.ViewGroup whose "toolbar" lives in the resource id. p64's
two gates (slot == L2/L3, isChromeClass() on the class name) miss both.

This script asserts the CODE PLAN only. It makes NO claim about the loaded
dex (four long-lived external processes were measured running 0.5.0-p82),
nor about visual acceptance. A green gate proves packaging only.
"""

import hashlib
import os
import re
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'magicglass')
ARC = os.path.join(ROOT, 'probe', 'probe-report', 'archive')
BASE = os.path.join(ROOT, 'probe', 'probe-report', 'baselines',
                    'L1-ACCEPTED-p30', 'src', 'main', 'java', 'com',
                    'magicglass')
PROP = os.path.join(ROOT, 'app', 'src', 'main', 'resources',
                    'META-INF', 'xposed', 'module.prop')
SCOPE = os.path.join(ROOT, 'app', 'src', 'main', 'resources',
                     'META-INF', 'xposed', 'scope.list')
APK = os.path.join(ROOT, 'build-out', 'MagicGlass-0.5.0-p106.apk')

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


def code_only(text):
    t = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
    return re.sub(r'//[^\n]*', '', t)


def f(name):
    return read(os.path.join(SRC, name))


RC = f('RenderController.java')
SC = f('Scope.java')
CC = f('ConfigContract.java')
GE = f('GlassEntry.java')
ML = f('MgLog.java')
CS = f('ConfigStore.java')
CB = f('ConfigBus.java')
MP = f('ModuleProvider.java')
BL = f('BackdropLayer.java')
SB = f('SplashBase.java')
PROP_TXT = read(PROP)
SCOPE_TXT = read(SCOPE)

# ---- A. version / packaging ---------------------------------------------
ck('A01 BUILD == 0.5.0-p106',
   'public static final String BUILD = "0.5.0-p106";' in GE)
ck('A02 module.prop version == 0.5.0-p106', 'version=0.5.0-p106' in PROP_TXT)
ck('A03 module.prop versionCode == 134', 'versionCode=134' in PROP_TXT)
ck('A04 BUILD and module.prop agree',
   '0.5.0-p106' in GE and '0.5.0-p106' in PROP_TXT)
ck('A05 mg-build fingerprint line survives', 'say("mg-build " + BUILD' in GE)
ck('A06 APK exists', os.path.isfile(APK))
ck('A07 APK SHA256 is stable and recorded',
   hashlib.sha256(open(APK, 'rb').read()).hexdigest()
   == '6e2f5055c25ab36c3374eabf1808438e1e52b87c36593083cd3cbca51d6a6e96')
z = zipfile.ZipFile(APK)
ck('A08 zip has exactly one classes.dex',
   z.namelist().count('classes.dex') == 1)
ck('A09 dex carries the p106 build fingerprint',
   b'0.5.0-p106' in z.read('classes.dex'))
ck('A10 dex carries the new default-visible TOPBAR marker',
   b'mg-state TOPBAR' in z.read('classes.dex'))
ck('A11 scope.list has exactly 10 entries',
   len([l for l in SCOPE_TXT.splitlines() if l.strip()]) == 10)
ck('A12 scope.list contains the wallpaper editor package',
   'com.hihonor.wallpapereditor' in SCOPE_TXT)
ck('A13 zero MIUI / HonorBG in LIVE CODE (comments state the ban)',
   all('miui' not in code_only(s).lower() and 'HonorBG' not in code_only(s)
       for s in (GE, ML, CS, CB, MP, BL, SB, RC, SC, CC)))

# ---- B. p104 regression (captured base map + crash fix kept) ------------
ck('B01 MgLog is still type-safe in the module process',
   'XposedModule' not in code_only(ML)
   and 'private static volatile Object sModule;' in ML)
ck('B02 GlassEntry.say still delegates', 'MgLog.say(msg);' in GE)
ck('B03 ConfigStore/ConfigBus/ModuleProvider have no GlassEntry code refs',
   all('GlassEntry' not in code_only(s) for s in (CS, CB, MP)))
ck('B04 captured-base-map |cap validity survives',
   'CAP_SUFFIX = "|cap"' in CS and 'installSplashBase' in CS)
ck('B05 provider commit + signature check survives',
   'ConfigStore.installSplashBase(c, arg)' in MP)
ck('B06 host capture still reuses p75 getTransform()',
   'video.getTransform(null)' in BL and 'captureVideoFrame(' in BL)
ck('B07 p103 GPU blur radius survives', 'SPLASH_BLUR_RADIUS = 64f' in GE)

# ---- C. L1 no-op / accepted pages untouched ----------------------------
for name in ('MaterialBridge.java', 'LiquidGlassView.java', 'LiquidShader.java'):
    ck('C01 %s == p30 baseline (bytes)' % name,
       read(os.path.join(SRC, name)) == read(os.path.join(BASE, name)))
ck('C02 extTopBarGate explicitly excludes L1, L2, L3, GLOBAL',
   all(x in RC for x in ('slot != ConfigContract.Slot.L1',
                         'slot != ConfigContract.Slot.L2',
                         'slot != ConfigContract.Slot.L3',
                         'slot != ConfigContract.Slot.GLOBAL')))
ck('C03 extTopBarGate requires genericEnabled (false in Settings)',
   'return genericEnabled && slot != null' in RC)
ck('C04 p64 chrome branch condition is unchanged',
   'boolean accountChrome = accountIdGate();' in RC
   and '(slot == ConfigContract.Slot.L2' in RC
   and '|| slot == ConfigContract.Slot.L3' in RC
   and '(isChromeClass(cls) || accountTopCard)' in RC)
ck('C05 isChromeClass / isOpaqueSolidFill / measuredOpaqueWhite unchanged',
   'return cls.contains("toolbar") || cls.contains("appbar");' in RC
   and 'd.getOpacity() == -1' in RC
   and 'return r >= 0xE0 && g >= 0xE0 && bl >= 0xE0;' in RC)
ck('C06 new strip is INSIDE the bounded stripRowsIn walk, not syncRows',
   RC.find('probeExtTopBar(v, cls);') < RC.find('private void stripRowsBeforeDraw()')
   and 'private void stripRowsIn(View v, int depth, int[] budget)' in RC)

# ---- D. structural candidate (no name-based keep word) ------------------
ck('D01 candidate requires the full-width OEM rule (p55)',
   'if (!isFullWidthOem(v)) return false;' in RC)
ck('D02 candidate uses RATIO top band, not a binary screen-Y threshold',
   'h > dh / 3' in RC and 'topBarLoc[1] > dh / 4' in RC)
ck('D03 candidate excludes fade / gradient ids so the fading layer survives',
   all(k in RC for k in ('low.contains("gradient")',
                         'low.contains("fade")',
                         'low.contains("top_gradient_bg")',
                         'low.contains("blur_wallpaper")')))
ck('D04 location buffer is reused (no allocation in the walk)',
   'private final int[] topBarLoc = new int[2];' in RC
   and 'v.getLocationOnScreen(topBarLoc);' in RC)
ck('D05 resource id is read for the device log only, with a guard',
   'id == View.NO_ID' in RC and 'getResourceEntryName(id)' in RC)

# ---- E. measured decision: strip opaque, keep translucent --------------
ck('E01 strip ONLY on a measured opaque near-white fill',
   'boolean opaque = solid || measured;' in RC
   and 'if (opaque) {' in RC)
ck('E02 p56 solid test + p80 1x1 pixel test are both used',
   'isOpaqueSolidFill(bg) || isOpaqueSolidFill(fg)' in RC
   and 'measuredOpaqueWhite(bg) || measuredOpaqueWhite(fg)' in RC)
ck('E03 a fade layer is evaluated ONLY when the fill is not opaque',
   'boolean fade = !opaque && isFadeWhiteLayer(v);' in RC)
ck('E04 no class-name keep word in the new decision path',
   (lambda i: i >= 0 and 'keepBackground' not in RC[i:i + 3000])(
       RC.find('private void probeExtTopBar')))
ck('E05 saveAndClear() latch is reused (restore on detach still works)',
   'saveAndClear(v);' in RC and 'diagTopBarStrips++' in RC)
ck('E06 default-visible TOPBAR log carries the measured fields',
   all(k in RC for k in ('mg-state TOPBAR slot=',
                         'bg=" + drawableDesc(bg)',
                         'solid=', 'measured=', 'fade=', 'strip=')))
ck('E07 TOPBAR log is bounded and throttled',
   'topBarLogLines < 14' in RC
   and 'now - lastTopBarLogMs > 400L' in RC)

# ---- F. scope addition is render-only -----------------------------------
ck('F01 wallpaper editor is in TOPBAR_ONLY, NOT in EXTERNAL',
   '"com.hihonor.wallpapereditor"' in SC
   and SC.find('TOPBAR_ONLY') < SC.find('isExternal(String pkg)')
   and 'private static final String[] TOPBAR_ONLY' in SC)
ck('F02 render targets include TOPBAR_ONLY',
   's.addAll(Arrays.asList(TOPBAR_ONLY));' in SC)
ck('F03 isExternal() still loops over EXTERNAL only (no starting-window hook)',
   (lambda i: 'TOPBAR_ONLY' not in SC[i:i + 320])(SC.find('public static boolean isExternal')))
ck('F04 genericEnabled accepts the render-only package too',
   'Scope.isTopBarOnly(activity.getPackageName());' in RC)
ck('F05 Slot enum has ext.wallpapereditor',
   'EXT_WALLPAPEREDITOR("ext.wallpapereditor")' in CC)
ck('F06 Scope.all() includes the extra package for the config broadcast',
   'EXTERNAL.length + TOPBAR_ONLY.length + 3' in SC
   and 'for (String p : TOPBAR_ONLY) out[i++] = p;' in SC)

# ---- G. archive ---------------------------------------------------------
ck('G01 archived p105 APK matches the built APK byte for byte',
   open(APK, 'rb').read() == open(os.path.join(
       ARC, 'MagicGlass-0.5.0-p106.apk'), 'rb').read())
ck('G02 every changed source has a p106 archive snapshot',
   all(os.path.isfile(os.path.join(ARC, n + '-p106-current.java'))
       for n in ('RenderController', 'GlassEntry'))
   and os.path.isfile(os.path.join(ARC, 'module-p106.prop')))
ck('G03 device survey artefact exists and is readable text',
   os.path.isfile(os.path.join(ARC, 'p105_log_review_2026-11-16.txt')))

# ---- H. p106: id-based two-layer split (user-chosen route) --------------
ck('H01 fade ids are a HARD keep, including collapsing app-bar containers',
   all(k in RC for k in ('r.contains("gradient")', 'r.contains("fade")',
                         'r.contains("top_gradient")',
                         'r.contains("appbarblur")', 'r.contains("appbar")',
                         'r.contains("collapsing")')))
ck('H02 base ids are NARROW (wallpaper family only, no generic hnblur)',
   all(k in RC for k in ('r.contains("blur_wallpaper")',
                         'r.contains("wallpaper_bg")',
                         'r.contains("blur_bg")'))
   and 'r.contains("hnblur")' not in RC)
ck('H03 fade branch RETURNS before any suppression (fading layer survives)',
   '''        if (isFadeLayerId(rid)) {
            logTopLayer(v, rid, "fade", false);
            return;
        }''' in RC)
ck('H04 base layer is suppressed by VIEW alpha, which also kills onDraw pixels',
   'v.setAlpha(0f);' in RC and 'suppressTopLayer(v, rid);' in RC)
ck('H05 original alpha is latched in Saved and restored on detach / glass-off',
   'Float alpha;' in RC and 's.alpha = Float.valueOf(v.getAlpha());' in RC
   and 'v.setAlpha(s.alpha);' in RC)
ck('H06 restripLatched() re-applies alpha=0 if the OEM re-binds the layer',
   's.alpha != null && v.getAlpha() != 0f' in RC)
ck('H07 the layer probe accepts plain View (the two layers are NOT ViewGroups)',
   'if (extTop) {\n                probeExtTopLayer(v, rid);' in RC
   and 'probeExtTopLayer(View v, String rid)' in RC)
ck('H08 only external pages reach the new probe (L1/L2/L3 no-op)',
   'if (extTop) {' in RC and 'return genericEnabled && slot != null' in RC)
ck('H09 decisions use the default-visible channel with an id and a kind',
   all(k in RC for k in ('mg-state TOPLAYER slot=', '+ " id=" + rid',
                         '+ " kind=" + kind', '+ " strip="')))
ck('H10 other OEM top layers are logged as kind=other and NOT touched',
   'logTopLayer(v, rid, "other", false);' in RC)
ck('H11 dex carries the new TOPLAYER marker',
   b'mg-state TOPLAYER' in z.read('classes.dex'))
ck('H12 p105 measured predicate is still present as a regression log',
   'mg-state TOPBAR slot=' in RC and 'probeExtTopBar(' in RC)
ck('H13 the p105 gate and L1 render trio are unchanged',
   'private boolean extTopBarGate()' in RC
   and 'slot != ConfigContract.Slot.L1' in RC)
ck('H14 archived p106 APK matches the built APK byte for byte',
   open(APK, 'rb').read() == open(os.path.join(
       ARC, 'MagicGlass-0.5.0-p106.apk'), 'rb').read())

print('\n%d checks, %d failures' % (CHECKS[0], len(FAILS)))
if FAILS:
    print('FAILED:')
    for x in FAILS:
        print('  - ' + x)
    sys.exit(1)
print('ALL_GREEN')
sys.exit(0)
