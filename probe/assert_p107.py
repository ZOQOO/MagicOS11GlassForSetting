#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""assert_p107.py -- deterministic (no device) invariants for 0.5.0-p107.

User goal: "所有 L2 都要统一样式". Chosen route: ONE structural rule for the
top OEM container across all seven external L2 pages, instead of p106's
per-page id list that only ever covered 桌面和个性化.

Device survey (7 pages) showed the same structure with different names:
y 0..135, full screen width, height 196..766, plus a sibling gradient layer.
This script asserts the CODE PLAN only; a green gate proves packaging, not
pixels. The loaded-dex layer is checked separately on device.
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
APK = os.path.join(ROOT, 'build-out', 'MagicGlass-0.5.0-p107.apk')

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
GE = f('GlassEntry.java')
ML = f('MgLog.java')
CS = f('ConfigStore.java')
CB = f('ConfigBus.java')
MP = f('ModuleProvider.java')
BL = f('BackdropLayer.java')
SB = f('SplashBase.java')
SC = f('Scope.java')
PROP_TXT = read(PROP)
SCOPE_TXT = read(SCOPE)
z = zipfile.ZipFile(APK)
DEX = z.read('classes.dex')

# ---- A. version / packaging ---------------------------------------------
ck('A01 BUILD == 0.5.0-p107',
   'public static final String BUILD = "0.5.0-p107";' in GE)
ck('A02 module.prop version == 0.5.0-p107', 'version=0.5.0-p107' in PROP_TXT)
ck('A03 module.prop versionCode == 135', 'versionCode=135' in PROP_TXT)
ck('A04 APK SHA256 is stable and recorded',
   hashlib.sha256(open(APK, 'rb').read()).hexdigest()
   == '6a340fc21625a1415f14a3f0cce62cead211d79258a5afa9b8ad0a946dbc372c')
ck('A05 dex carries the p107 fingerprint', b'0.5.0-p107' in DEX)
ck('A06 dex carries the unified TOPCONTAINER marker',
   b'mg-state TOPCONTAINER' in DEX)
ck('A07 scope.list still has exactly 10 entries',
   len([l for l in SCOPE_TXT.splitlines() if l.strip()]) == 10)
ck('A08 zero MIUI / HonorBG in LIVE CODE',
   all('miui' not in code_only(s).lower() and 'HonorBG' not in code_only(s)
       for s in (GE, ML, CS, CB, MP, BL, SB, RC, SC)))

# ---- B. p104 regression --------------------------------------------------
ck('B01 MgLog stays type-safe in the module process',
   'XposedModule' not in code_only(ML)
   and 'private static volatile Object sModule;' in ML)
ck('B02 GlassEntry.say still delegates', 'MgLog.say(msg);' in GE)
ck('B03 captured base map validity survives', 'CAP_SUFFIX = "|cap"' in CS)
ck('B04 provider commit + signature check survives',
   'ConfigStore.installSplashBase(c, arg)' in MP)
ck('B05 host capture still reuses p75 getTransform()',
   'video.getTransform(null)' in BL)
ck('B06 p103 GPU blur survives', 'SPLASH_BLUR_RADIUS = 64f' in GE)

# ---- C. L1 / accepted pages are no-op -----------------------------------
for name in ('MaterialBridge.java', 'LiquidGlassView.java', 'LiquidShader.java'):
    ck('C01 %s == p30 baseline (bytes)' % name,
       read(os.path.join(SRC, name)) == read(os.path.join(BASE, name)))
ck('C02 extTopBarGate excludes L1/L2/L3/GLOBAL',
   all(x in RC for x in ('slot != ConfigContract.Slot.L1',
                         'slot != ConfigContract.Slot.L2',
                         'slot != ConfigContract.Slot.L3',
                         'slot != ConfigContract.Slot.GLOBAL')))
ck('C03 p64 chrome branch is unchanged',
   'boolean accountChrome = accountIdGate();' in RC
   and '(isChromeClass(cls) || accountTopCard)' in RC)
ck('C04 p105 measured predicate is still logged (regression evidence)',
   'mg-state TOPBAR slot=' in RC and 'probeExtTopBar(' in RC)
ck('C05 the new probe is inside the bounded first-layout walk',
   RC.find('probeExtTopContainer(v, rid);')
   < RC.find('private void stripRowsBeforeDraw()'))

# ---- D. UNIFIED structural candidate ------------------------------------
ck('D01 full-width OEM rule (p55) is the width criterion',
   'if (dh <= 0 || h <= 0 || !isFullWidthOem(v)) return;' in RC)
ck('D02 top band is a RATIO, not a binary screen-Y threshold',
   'topBarLoc[1] > dh / 16' in RC)
ck('D03 upper bound screen/3 includes the 766 px app-bar container',
   'h > dh / 3' in RC)
ck('D04 full-screen OEM root is evidence only, never suppressed',
   'logTopContainer(v, rid, "full", false);' in RC
   and 'return;' in RC[RC.find('if (h > dh / 3)'):RC.find('if (h > dh / 3)') + 340])
ck('D05 location buffer is reused (no allocation in the walk)',
   'private final int[] topBarLoc = new int[2];' in RC
   and 'v.getLocationOnScreen(topBarLoc);' in RC)
# Scope the check to the NEW p107 block: p105's own candidate still names
# top_gradient_bg as a fade exclusion, and the Javadoc lists the survey ids.
P107 = code_only(RC[RC.find('private static boolean isFadeTopLayer'):
                    RC.find('private void latchListRowsNow()')])
ck('D06 the NEW rule contains NO per-page id whitelist',
   all(k not in P107 for k in
       ('blur_wallpaper_bg', 'top_gradient_bg', 'hn_blur_top_container',
        'hnblurappbar_background_layout', 'content_header_container')))
ck('D07 p106 id-split helpers are gone',
   all(k not in RC for k in ('isBaseLayerId', 'suppressTopLayer',
                             'probeExtTopLayer', 'logTopLayer')))

# ---- E. fading layer is a HARD keep -------------------------------------
ck('E01 fade keep covers gradient / fade / top_gradient / appbarblur / collapsing',
   all(k in RC for k in ('s.contains("gradient")', 's.contains("fade")',
                         's.contains("top_gradient")',
                         's.contains("appbarblur")',
                         's.contains("collapsing")')))
ck('E01b the CLASS name is measured too (HnGradientBlurTopContainer is a fade)',
   'private static boolean isFadeTopLayer(String cls, String rid)' in RC
   and 'isFadeTopLayer(v.getClass().getSimpleName(), rid)' in RC
   and 'HnGradientBlurTopContainer' in RC)
ck('E02 fade branch RETURNS before any suppression',
   '''        if (isFadeTopLayer(v.getClass().getSimpleName(), rid)) {
            logTopContainer(v, rid, "fade", false);
            return;
        }''' in RC)
SUPPRESS = RC[RC.find('private void suppressTopContainer'):
             RC.find('private void probeExtTopContainer')]
ck('E03 the NEW suppression never hides the View or its children',
   'v.setWillNotDraw(true);' in SUPPRESS
   and 'setVisibility' not in SUPPRESS
   and 'setAlpha' not in SUPPRESS)

# ---- F. suppression + restore -------------------------------------------
ck('F01 both the Drawable bottom and the container own onDraw are suppressed',
   'saveAndClear(v);' in RC and 'v.setWillNotDraw(true);' in RC)
ck('F02 Saved records the suppression, restoreRows gives it back',
   'boolean willNotDraw;' in RC and 'v.setWillNotDraw(false);' in RC)
ck('F03 restripLatched() re-applies it after an OEM re-bind',
   's.willNotDraw' in RC and RC.count('v.setWillNotDraw(true);') >= 2)
ck('F04 default-visible TOPCONTAINER log carries id / kind / strip',
   all(k in RC for k in ('mg-state TOPCONTAINER slot=', '+ " id=" + rid',
                         '+ " kind=" + kind', '+ " strip="')))
ck('F05 the log is bounded and throttled',
   'topContainerLogLines >= 14' in RC
   and 'now - lastTopContainerLogMs < 400L' in RC)

# ---- G. archive ----------------------------------------------------------
# ---- H. late re-check (measured 400 ms early-window gap) ---------------
ck('H01 early strip window itself is unchanged (p48/p56 path intact)',
   'if (now - attachTimeMs <= OemCardSuppressor.EARLY_WINDOW_MS) {' in RC
   and 'latchListRowsNow();' in RC)
ck('H02 external L2s get a late re-check at 600 ms throttle',
   'now - lastTopContainerScanMs > 600L' in RC
   and 'extTopBarGate() && now - lastTopContainerScanMs > 600L' in RC)
ck('H03 late walk is bounded (200 nodes / depth 12 / 24 children)',
   'new int[]{200}' in RC and 'depth > 12' in RC
   and 'Math.min(g.getChildCount(), 24)' in RC)
ck('H04 late walk only probes top containers, never strips list rows',
   (lambda i: i >= 0 and 'saveAndClear' not in RC[i:i + 900])(
       RC.find('private void probeTopContainerIn')))
ck('H05 late walk reuses the same structural predicate',
   'probeExtTopContainer(v, resourceEntry(v));' in RC)
ck('H06 late scan timestamp field exists',
   'private long lastTopContainerScanMs;' in RC)

ck('G01 archived p107 APK matches the built APK byte for byte',
   open(APK, 'rb').read() == open(os.path.join(
       ARC, 'MagicGlass-0.5.0-p107.apk'), 'rb').read())
ck('G02 changed sources have p107 snapshots',
   all(os.path.isfile(os.path.join(ARC, n + '-p107-current.java'))
       for n in ('RenderController', 'GlassEntry'))
   and os.path.isfile(os.path.join(ARC, 'module-p107.prop')))

print('\n%d checks, %d failures' % (CHECKS[0], len(FAILS)))
if FAILS:
    print('FAILED:')
    for x in FAILS:
        print('  - ' + x)
    sys.exit(1)
print('ALL_GREEN')
sys.exit(0)
