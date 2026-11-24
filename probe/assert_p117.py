#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""assert_p117.py -- deterministic (no device) invariants for 0.5.0-p117.

Two changes in this build:

A. LIVE CONFIG defect (device evidence 2026-11-16 21:22, findings 101):
   picking a VIDEO background while NO host Activity was attached wrote the
   new prefs and did send the coalesced broadcast, but the RECEIVER_EXPORTED
   receiver lives only between attach() and detach(). sCached is a
   process-wide, TTL-free snapshot, so every later page kept reading the
   OLD config (page ... bg=image while prefs said bg.kind=video).
   One manual broadcast to an attached host immediately produced
   cache=miss ver=1794835365614 + bg=video + LIVEGLASS mode=gpu video=true.
   Fix re-reads the provider on attach and on every debounced resume,
   WITHOUT touching the accepted starting-window generation.

B. CONTENT-BAND probe for com.hihonor.id sub-pages. Logs CONTENTNODE
   (measured Drawable / opacity / parent) and CONTENTRECTS (CardScanner
   geometry), so the three white-band / four-square-card symptoms can be
   attributed to an exact layer. LOG ONLY: never strips, never changes a
   pixel, never runs in a frame path.

Green here proves packaging only. It is NOT a device or visual claim.
"""

import io
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
RES = os.path.join(ROOT, 'app', 'src', 'main', 'resources')
PROP = os.path.join(RES, 'META-INF', 'xposed', 'module.prop')
SCOPE = os.path.join(RES, 'META-INF', 'xposed', 'scope.list')
APK = os.path.join(ROOT, 'build-out', 'MagicGlass-0.5.0-p117.apk')

FAILS = []
CHECKS = [0]


def ck(name, cond):
    CHECKS[0] += 1
    if not cond:
        FAILS.append(name)
    print(('OK   ' if cond else 'FAIL ') + '%02d %s' % (CHECKS[0], name))


def rd(p):
    return io.open(p, encoding='utf-8').read()


def rb(p):
    return open(p, 'rb').read()


RC = rd(os.path.join(SRC, 'RenderController.java'))
CB = rd(os.path.join(SRC, 'ConfigBus.java'))
GE = rd(os.path.join(SRC, 'GlassEntry.java'))
SC = rd(os.path.join(SRC, 'Scope.java'))
PROP_TXT = rd(PROP)
SCOPE_TXT = rd(SCOPE)

i_probe = RC.find('private void probeContentArea(')
i_rects = RC.find('private void probeContentRects(')
i_latch = RC.find('private void latchListRowsNow(')
PROBE = RC[i_probe:i_rects] if i_probe >= 0 and i_rects > i_probe else ''
RECTS = RC[i_rects:i_latch] if i_rects >= 0 and i_latch > i_rects else ''

# ---- version / identity --------------------------------------------------
ck('V01 build fingerprint is p117',
   'BUILD = "0.5.0-p117";' in GE)
ck('V02 module.prop version + code',
   'version=0.5.0-p117' in PROP_TXT and 'versionCode=145' in PROP_TXT)
ck('V03 no leftover p115/p116 BUILD fingerprint (comments may cite history)',
   all('BUILD = "0.5.0-p115"' not in rd(os.path.join(SRC, f))
       and 'BUILD = "0.5.0-p116"' not in rd(os.path.join(SRC, f))
       for f in os.listdir(SRC) if f.endswith('.java'))
   and 'version=0.5.0-p115' not in PROP_TXT
   and 'version=0.5.0-p116' not in PROP_TXT)

# ---- L1 frozen baseline --------------------------------------------------
for f in ('MaterialBridge.java', 'LiquidGlassView.java', 'LiquidShader.java'):
    ck('L1 %s byte-identical to p30' % f,
       rb(os.path.join(BASE, f)) == rb(os.path.join(SRC, f)))

# ---- A. live config fix --------------------------------------------------
ck('A01 cache-only invalidation exists',
   'public static void invalidateConfigCache()' in CB)
i_inv = CB.find('public static void invalidateConfigCache()')
INV_BODY = CB[i_inv:i_inv + 300]
ck('A02 invalidation clears ONLY sCached (no preview generation bump)',
   'sCached = null;' in INV_BODY
   and 'invalidateStartingPreview' not in INV_BODY)
ck('A03 attach() re-reads BEFORE refresh(true)',
   (lambda i, j: i >= 0 and j > i)(
       RC.find('ConfigBus.invalidateConfigCache();'),
       RC.find('refresh(true);')))
i_life = RC.find('public void onLifecycle()')
LIFE = RC[i_life:i_life + 1400]
ck('A04 onLifecycle() re-reads BEFORE refresh(false)',
   'ConfigBus.invalidateConfigCache();' in LIFE
   and LIFE.find('ConfigBus.invalidateConfigCache();')
   < LIFE.find('refresh(false);'))
ck('A05 debounce still guards onLifecycle',
   'LIFECYCLE_DEBOUNCE_MS' in LIFE)
ck('A06 RECEIVER_EXPORTED live-config receiver intact',
   'Context.RECEIVER_EXPORTED' in RC
   and 'ConfigBus.ACTION_CONFIG_CHANGED' in RC)
ck('A07 refresh still applies backdrop on version/bg change',
   'if (force || slotChanged || versionChanged || bgChanged) {' in RC
   and 'backdrop.apply(slot, cfg);' in RC)

# ---- B. content probe ----------------------------------------------------
ck('B01 CONTENTNODE probe defined exactly once',
   RC.count('private void probeContentArea(') == 1)
ck('B02 probe does NOT skip decor (p116 bug fixed)',
   'if (v == overlay || v == glassHost) return;' in PROBE
   and 'v == decor' not in PROBE)
ck('B03 bounded walk 120 nodes / depth 12 / 20 children',
   'depth > 12' in PROBE and 'new int[]{120}' in RC
   and 'Math.min(g.getChildCount(), 20)' in PROBE)
ck('B04 width threshold covers the 542 px square cards',
   'w >= 300 && h >= 100 && h <= dh / 2' in PROBE)
ck('B05 measured Drawable evidence, not class-name guessing',
   all(k in PROBE for k in ('v.getBackground()', 'isOpaqueSolidFill',
                            'measuredOpaqueWhite', 'drawableDesc')))
ck('B06 probe is LOG ONLY (no strip / alpha / visibility writes)',
   all(k not in PROBE for k in ('saveAndClear', 'setWillNotDraw',
                                'setAlpha(0', 'setVisibility(',
                                'setBackground(')))
ck('B07 log is bounded and default-visible',
   'contentLogLines < 60' in PROBE and 'GlassEntry.say("mg-state CONTENTNODE' in PROBE)
ck('B08 CONTENTRECTS reads CardScanner geometry',
   'private void probeContentRects(' in RC
   and 'CardScanner.rects(decor, cfg, dens)' in RECTS
   and 'CONTENTRECTS slot=' in RECTS)
ck('B09 probe gated to EXT_ID + 600 ms late walk, never per frame',
   'slot == ConfigContract.Slot.EXT_ID' in RC
   and 'now - lastTopContainerScanMs > 600L' in RC
   and RC.count('probeContentArea(decor, 0, new int[]{120});') == 1)
ck('B10 log counter resets on detach',
   'contentLogLines = 0;' in RC[RC.find('public void detach()'):RC.find('public void detach()') + 1800])

# ---- scope / standalone --------------------------------------------------
pkgs = [x.strip() for x in SCOPE_TXT.splitlines()
        if x.strip() and not x.strip().startswith('#')]
ck('S01 scope.list has 10 entries', len(pkgs) == 10)
ck('S02 every scope entry is reflected in Scope.java',
   all(p in SC or p in ('com.android.settings', 'com.android.systemui',
                        'com.magicglass')
       for p in pkgs))
ck('S03 wallpapereditor stays TOPBAR_ONLY',
   'com.hihonor.wallpapereditor' in pkgs and 'TOPBAR_ONLY' in SC)
ck('S04 scope.list carries no MIUI / upstream package inheritance',
   all(k not in SCOPE_TXT for k in ('com.miui', 'com.ciallo',
                                    'hyperbackground'))
   and 'com.magicglass' in SCOPE_TXT
   and 'name=MagicGlass' in PROP_TXT
   and 'com.android.settings' in SCOPE_TXT)

# ---- APK / dex -----------------------------------------------------------
ck('D01 signed APK exists', os.path.isfile(APK))
if os.path.isfile(APK):
    with zipfile.ZipFile(APK) as z:
        names = z.namelist()
        ck('D02 dex + xposed metadata packaged',
           'classes.dex' in names
           and any('META-INF/xposed/module.prop' in n for n in names))
        dex = z.read('classes.dex')
        ck('D03 dex carries p117 fingerprint', b'0.5.0-p117' in dex)
        ck('D04 dex contains both new diagnostic markers',
           b'CONTENTNODE' in dex and b'CONTENTRECTS' in dex)
        ck('D05 dex has no p116 probe residue',
           b'0.5.0-p116' not in dex)
    ck('D06 archived APK matches build output byte for byte',
       os.path.isfile(os.path.join(ARC, 'MagicGlass-0.5.0-p117.apk'))
       and rb(APK) == rb(os.path.join(ARC, 'MagicGlass-0.5.0-p117.apk')))
else:
    for k in ('D02', 'D03', 'D04', 'D05', 'D06'):
        ck(k + ' (skipped: no APK)', False)

ck('D07 changed sources have p117 snapshots',
   all(os.path.isfile(os.path.join(ARC, n + '-p117-current.java'))
       for n in ('RenderController', 'ConfigBus', 'GlassEntry'))
   and os.path.isfile(os.path.join(ARC, 'module-p117.prop')))

print('\n%d checks, %d failures' % (CHECKS[0], len(FAILS)))
for f in FAILS:
    print('  FAILED: ' + f)
sys.exit(1 if FAILS else 0)
