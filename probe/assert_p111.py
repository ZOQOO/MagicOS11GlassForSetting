#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""assert_p111.py -- deterministic (no device) invariants for 0.5.0-p111.

User pointed at the numbered map: block #3 (账号中心 top white card) is
the ONLY block to remove; #4..#6 are already invisible and must be left
alone; #1/#2/#8 fading layers are a HARD KEEP.

Root cause found in the archive: findings §2130 real-device log
`slot=ext.id edges=[49..1223 49..1223]` = 0.923 of the 1272 px screen,
while p55/p64/p79/p80/p105/p107 all required isFullWidthOem() >= 0.94.
The inset card was never a candidate. p108 relaxes the width bound to
0.90 for EXT_ID ONLY, keeps the 0.94 predicate everywhere else, adds the
AppbarBlurBg fade family, and records width ratio + out-of-band cards.
This script asserts the CODE PLAN only; green != pixels.
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
APK = os.path.join(ROOT, 'build-out', 'MagicGlass-0.5.0-p111.apk')

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
ck('A01 BUILD == 0.5.0-p111',
   'public static final String BUILD = "0.5.0-p111";' in GE)
ck('A02 module.prop version == 0.5.0-p111', 'version=0.5.0-p111' in PROP_TXT)
ck('A03 module.prop versionCode == 139', 'versionCode=139' in PROP_TXT)
ck('A04 APK SHA256 is stable and recorded',
   hashlib.sha256(open(APK, 'rb').read()).hexdigest()
   == 'f93723974085949ee8ad61c53c14d5c60032734bba893946c497d625c3ebed3b')
ck('A05 dex carries the p108 fingerprint', b'0.5.0-p111' in DEX)
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
ck('D01 p55 width rule is the ONLY width rule (no EXT_ID relaxation left)',
   'vw >= dw * 0.94f' in RC
   and 'if (dh <= 0 || h <= 0 || !extTopWidthOk(v)) return;' in RC
   and '0.90f' not in RC)
ck('D02 top band is a RATIO, not a binary screen-Y threshold',
   'topBarLoc[1] > dh / 16' in RC)
ck('D03 upper bound screen/3 includes the 766 px app-bar container',
   'h > dh / 3' in RC)
ck('D04 full-screen OEM root is evidence only, never suppressed',
   'logTopContainer(v, rid, "full", false);' in RC
   and 'if (isTopHeaderBand(rid, topBarLoc[1], dh, h))' not in
       RC[RC.find('if (h > dh / 3)'):RC.find('if (h > dh / 3)') + 200])
ck('D05 location buffer is reused (no allocation in the walk)',
   'private final int[] topBarLoc = new int[2];' in RC
   and 'v.getLocationOnScreen(topBarLoc);' in RC)
# Scope the check to the NEW p107 block: p105's own candidate still names
# top_gradient_bg as a fade exclusion, and the Javadoc lists the survey ids.
P107 = code_only(RC[RC.find('private static boolean isFadeTopLayer'):
                    RC.find('private void latchListRowsNow()')])
ck('D06 the NEW rule names only the TOP BAND container, not per-page cards',
   'r.equals("hn_blur_top_container")' in RC
   and 'r.equals("content_header_container")' in RC
   and all(k not in P107 for k in
       ('blur_wallpaper_bg', 'top_gradient_bg', 'hnblurappbar_background_layout')))
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

ck('G01 archived p111 APK matches the built APK byte for byte',
   open(APK, 'rb').read() == open(os.path.join(
       ARC, 'MagicGlass-0.5.0-p111.apk'), 'rb').read())
ck('G02 changed sources have p111 snapshots',
   all(os.path.isfile(os.path.join(ARC, n + '-p111-current.java'))
       for n in ('RenderController', 'GlassEntry'))
   and os.path.isfile(os.path.join(ARC, 'module-p111.prop')))

# ---- I. p108: block #3 inset-card width gap (EXT_ID only) ----------------
# ---- I. p109: block #3 = avatar_card, id-matched, no Y gate ------------
ck('I01 the WRONG 0.90 relaxation is fully removed (0.94 restored)',
   '0.90f' not in RC and 'vw >= dw * 0.94f' in RC)
ck('I02 extTopWidthOk is now the plain accepted predicate',
   'private boolean extTopWidthOk(View v) {\n        return isFullWidthOem(v);' in RC)
ck('I03 avatar_card is now EXCLUDED (p109/p110 wrong target removed)',
   'if ("avatar_card".equals(rid)) {' in RC
   and 'private boolean isAccountHeaderCard' not in RC)
ck('I03b the wrong subtree stripper is gone',
   'stripAccountCardSubtree' not in RC and 'CARDNODE' not in RC)
ck('I04 the top band is a structural predicate, not an EXT_ID-only card',
   'private static boolean isTopHeaderBand' in RC
   and 'r.contains("header_container")' in RC)
ck('I05 the top band is checked BEFORE the fade hard-keep',
   (lambda i, j: i >= 0 and j > i)(
       RC.find('if (isTopHeaderBand('), RC.find('if (isFadeTopLayer(')))
ck('I06 log carries an integer width ratio for device confirmation',
   '+ " ratio=" + widthPct(v)' in RC
   and 'return v.getWidth() * 100 / decor.getWidth();' in RC)
ck('I06 AppbarBlurBg (044:1498) is a HARD KEEP fade word',
   's.contains("hnblurappbar")' in RC and 's.contains("appbarblur")' in RC)
ck('I07 out-of-band inset cards (#4..#6) are recorded, NEVER suppressed',
   'logTopContainer(v, rid, "below", false);' in RC
   and 'slot == ConfigContract.Slot.EXT_ID' in RC)
ck('I08 the top band bound is unchanged (dh/16), so #4..#6 stay out',
   'if (topBarLoc[1] > dh / 16) {' in RC)
ck('I09 suppression still uses setWillNotDraw, not p106 alpha hiding',
   'v.setWillNotDraw(true);' in RC and 'setAlpha(0f)' not in RC)
ck('I10 full-screen OEM root is still evidence-only',
   'logTopContainer(v, rid, "full", false);' in RC)
ck('I11 no per-page id whitelist was introduced for block #3',
   (lambda i: i >= 0 and all(k not in RC[i:i + 2400] for k in
       ('CenterActivity', 'account_center', 'blk3')))(
       RC.find('private boolean extTopWidthOk')))
ck('I12 dex carries the new ratio marker', b' ratio=' in DEX)
ck('I13 archived p111 APK matches the built APK byte for byte',
   open(APK, 'rb').read() == open(os.path.join(
       ARC, 'MagicGlass-0.5.0-p111.apk'), 'rb').read())
ck('I14 changed sources have p111 snapshots',
   all(os.path.isfile(os.path.join(ARC, n + '-p111-current.java'))
       for n in ('RenderController', 'GlassEntry'))
   and os.path.isfile(os.path.join(ARC, 'module-p111.prop')))

print('\n%d checks, %d failures' % (CHECKS[0], len(FAILS)))
if FAILS:
    print('FAILED:')
    for x in FAILS:
        print('  - ' + x)
    sys.exit(1)
print('ALL_GREEN')
sys.exit(0)
# ---- K. p111: restore avatar_card, strip TOP full-width band only -------
ck('K01 the WRONG avatar-card subtree stripper is fully removed',
   all(k not in RC for k in ('stripAccountCardSubtree', 'isAccountHeaderCard',
                             'CARDNODE', 'diagCardLayers')))
ck('K02 avatar_card is explicitly protected (never stripped)',
   (lambda i: i >= 0 and 'return;' in RC[i:i + 120])(
       RC.find('if ("avatar_card".equals(rid))')))
ck('K03 the TOP full-width band predicate exists with measured structure',
   'private static boolean isTopHeaderBand(String rid, int top, int dh, int h)' in RC
   and 'r.equals("hn_blur_top_container")' in RC
   and 'r.equals("content_header_container")' in RC)
ck('K04 the band is y=0 + full width + height<=screen/3',
   'if (top > dh / 16) return false;' in RC and 'if (h > dh / 3) return false;' in RC)
ck('K05 gradient/fade children and avatar/toolbar are excluded from the band',
   all(k in RC for k in ('r.contains("gradient")', 'r.contains("fade")',
                         'r.contains("avatar")', 'r.contains("toolbar")')))
ck('K06 the top band is stripped by OWN drawing only, children keep drawing',
   (lambda i: i >= 0 and 'suppressTopContainer(v, rid);' in RC[i:i + 200]
    and 'setVisibility' not in RC[i:i + 200])(
       RC.find('if (isTopHeaderBand(rid, topBarLoc[1], dh, h))')))
ck('K07 the band check runs BEFORE the fade hard-keep',
   (lambda i, j: i >= 0 and j > i)(
       RC.find('if (isTopHeaderBand('), RC.find('if (isFadeTopLayer(')))
ck('K08 the accepted 0.94 width rule is unchanged',
   'vw >= dw * 0.94f' in RC and '0.90f' not in RC)
ck('K09 L1 gate and fade-layer protection intact',
   'slot != ConfigContract.Slot.L1' in RC and 's.contains("collapsing")' in RC)
ck('K10 dex carries the p111 fingerprint, no CARDNODE marker',
   b'0.5.0-p111' in DEX and b'CARDNODE' not in DEX)
ck('K11 archived p111 APK matches the built APK byte for byte',
   open(APK, 'rb').read() == open(os.path.join(
       ARC, 'MagicGlass-0.5.0-p111.apk'), 'rb').read())
ck('K12 changed sources have p111 snapshots',
   all(os.path.isfile(os.path.join(ARC, n + '-p111-current.java'))
       for n in ('RenderController', 'GlassEntry'))
   and os.path.isfile(os.path.join(ARC, 'module-p111.prop')))

