#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""assert_p120.py -- deterministic (no device) invariants for 0.5.0-p120.

This build adds the unified-card transparent-fill work (design report
offline-unified-card-transparency-design-2026-11-18):

  * three new glass.* config keys (colorDiag / unifiedCards /
    unifiedAggressive). The glass.* prefix puts them in glassFingerprint(),
    so toggling any of them re-renders in the SAME process, no restart.
    Default posture is DIAGNOSE ONLY: diag ON, both strip switches OFF.
  * the COLORCAND diagnostic marker + the four-step gated unified-card
    stripping in RenderController (owned by core-dev).

Green here proves packaging only. It is NOT a device or visual claim.
NOTE: APK/dex checks fail until the build has produced the p119 APK; running
this script before the build is expected to fail on the D-series only.
"""

import io
import os
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
APK = os.path.join(ROOT, 'build-out', 'MagicGlass-0.5.0-p120.apk')

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


CC = rd(os.path.join(SRC, 'ConfigContract.java'))
CB = rd(os.path.join(SRC, 'ConfigBus.java'))
GE = rd(os.path.join(SRC, 'GlassEntry.java'))
SC = rd(os.path.join(SRC, 'Scope.java'))
CS = rd(os.path.join(SRC, 'CardScanner.java'))
PROP_TXT = rd(PROP)
SCOPE_TXT = rd(SCOPE)

# glassFingerprint() body, used to prove the new keys are auto-enumerated.
i_gf = CC.find('public String glassFingerprint()')
i_bg = CC.find('public String bgFingerprint()')
GF = CC[i_gf:i_bg] if i_gf >= 0 and i_bg > i_gf else ''

# ---- version / identity --------------------------------------------------
ck('V01 build fingerprint is p120',
   'BUILD = "0.5.0-p120";' in GE)
ck('V02 module.prop version is p120',
   'version=0.5.0-p120' in PROP_TXT)
ck('V03 module.prop versionCode is 146',
   'versionCode=148' in PROP_TXT)
ck('V04 no leftover p117 version in module.prop',
   'version=0.5.0-p117' not in PROP_TXT
   and 'versionCode=147' not in PROP_TXT)
ck('V05 no leftover p115/p116 BUILD fingerprint (comments may cite history)',
   all('BUILD = "0.5.0-p115"' not in rd(os.path.join(SRC, f))
       and 'BUILD = "0.5.0-p116"' not in rd(os.path.join(SRC, f))
       for f in os.listdir(SRC) if f.endswith('.java')))
ck('V06 identity name=MagicGlass intact',
   'name=MagicGlass' in PROP_TXT)

# ---- new config keys -----------------------------------------------------
ck('C01 three new Param symbols declared',
   all(s in CC for s in ('GLASS_COLOR_DIAG(', 'GLASS_UNIFIED_CARDS(',
                         'GLASS_UNIFIED_AGGRESSIVE(')))
ck('C02 three new key strings present',
   all(s in CC for s in ('"glass.colorDiag"', '"glass.unifiedCards"',
                         '"glass.unifiedAggressive"')))
ck('C03 GLASS_COLOR_DIAG defaults to true (exact)',
   'GLASS_COLOR_DIAG("glass.colorDiag", "true")' in CC)
ck('C04 GLASS_UNIFIED_CARDS defaults to false (exact)',
   'GLASS_UNIFIED_CARDS("glass.unifiedCards", "false")' in CC)
ck('C05 GLASS_UNIFIED_AGGRESSIVE defaults to false (exact)',
   'GLASS_UNIFIED_AGGRESSIVE("glass.unifiedAggressive", "false")' in CC)
ck('C06 all three new keys use the glass. prefix',
   all(('glass.' + k + '"') in CC
       for k in ('colorDiag', 'unifiedCards', 'unifiedAggressive')))
ck('C07 each new Param declared exactly once',
   all(CC.count(s) == 1 for s in ('GLASS_COLOR_DIAG(',
                                  'GLASS_UNIFIED_CARDS(',
                                  'GLASS_UNIFIED_AGGRESSIVE(')))
ck('C08 glassFingerprint() enumerates glass. prefix',
   'p.key.startsWith("glass.")' in GF)
ck('C09 new keys sit beside GLASS_STRIP_TOP_BAND in the glass section',
   (lambda a, b, c, d, e: a >= 0 and a < b < c < d < e)(
       CC.find('GLASS_STRIP_TOP_BAND('), CC.find('GLASS_COLOR_DIAG('),
       CC.find('GLASS_UNIFIED_CARDS('), CC.find('GLASS_UNIFIED_AGGRESSIVE('),
       CC.find('// ---- geometry')))

# ---- L1 frozen baseline --------------------------------------------------
for f in ('MaterialBridge.java', 'LiquidGlassView.java', 'LiquidShader.java'):
    ck('L1 %s byte-identical to p30' % f,
       rb(os.path.join(BASE, f)) == rb(os.path.join(SRC, f)))

# ---- scope / standalone --------------------------------------------------
pkgs = [x.strip() for x in SCOPE_TXT.splitlines()
        if x.strip() and not x.strip().startswith('#')]
ck('S01 scope.list has 10 entries', len(pkgs) == 10)
ck('S02 no duplicate scope entries', len(set(pkgs)) == len(pkgs))
ck('S03 wallpapereditor stays in scope and TOPBAR_ONLY',
   'com.hihonor.wallpapereditor' in pkgs and 'TOPBAR_ONLY' in SC)
ck('S04 scope.list carries no MIUI / upstream package inheritance',
   all(k not in SCOPE_TXT for k in ('com.miui', 'com.xiaomi',
                                    'com.ciallo', 'hyperbackground')))
ck('S05 standalone scope keys intact',
   'com.magicglass' in SCOPE_TXT
   and 'com.android.settings' in SCOPE_TXT)

# ---- module.prop semantics ----------------------------------------------
ck('P01 description keeps the "no MIUI base" NEGATION (not inheritance)',
   'no MIUI base' in PROP_TXT)
ck('P02 staticScope=false (standalone)',
   'staticScope=false' in PROP_TXT)
ck('P03 API levels unchanged (101/101)',
   'minApiVersion=101' in PROP_TXT
   and 'targetApiVersion=101' in PROP_TXT)

# ---- unified-card diagnostic marker (source) ----------------------------
ck('U01 COLORCAND diagnostic marker present in source',
   any('COLORCAND' in rd(os.path.join(SRC, f))
       for f in os.listdir(SRC) if f.endswith('.java')))

# ---- APK / dex -----------------------------------------------------------
ck('D01 p120 signed APK exists', os.path.isfile(APK))
if os.path.isfile(APK):
    with zipfile.ZipFile(APK) as z:
        names = z.namelist()
        ck('D02 dex + xposed metadata packaged',
           'classes.dex' in names
           and any('META-INF/xposed/module.prop' in n for n in names))
        dex = z.read('classes.dex')
        ck('D03 dex carries p120 fingerprint', b'0.5.0-p120' in dex)
        ck('D04 dex contains COLORCAND marker', b'COLORCAND' in dex)
        ck('D05 dex has no p119 fingerprint', b'0.5.0-p119' not in dex)
        pname = next((n for n in names
                      if 'META-INF/xposed/module.prop' in n), None)
        ck('D06 packaged module.prop carries p120',
           pname is not None
           and b'version=0.5.0-p120' in z.read(pname))
    ck('D07 archived APK matches build output byte for byte',
       os.path.isfile(os.path.join(ARC, 'MagicGlass-0.5.0-p120.apk'))
       and rb(APK) == rb(os.path.join(ARC, 'MagicGlass-0.5.0-p120.apk')))
else:
    for k in ('D02', 'D03', 'D04', 'D05', 'D06', 'D07'):
        ck(k + ' (skipped: no APK)', False)

arch = os.listdir(ARC)


def has_snap(token):
    return any(token in n and 'p120' in n for n in arch)


ck('D08 archived p120 source snapshots exist',
   all(has_snap(t) for t in ('CardScanner',
                             'GlassEntry', 'module')))

RC = io.open(os.path.join(SRC, 'RenderController.java'), encoding='utf-8').read()
ck('P01 content probe has its OWN line budget field',
   'private int contentNodeLines;' in RC and 'private int contentRectLines;' in RC)
ck('P02 content node budget independent from top-band budget',
   'contentNodeLines < 60' in RC)
ck('P03 content rects have a separate small budget',
   'contentRectLines < 8' in RC)
ck('P04 content node log dedupes by id|top|size',
   'contentSeen.add(logKey)' in RC)
ck('P05 all probe budgets reset on detach',
   all(k in RC[RC.find('public void detach()'):
               RC.find('public void detach()') + 2400]
       for k in ('contentLogLines = 0', 'contentNodeLines = 0',
                 'contentRectLines = 0', 'contentSeen.clear()')))

ck('Q01 square-card gate exists with measured parent id',
   'isAccountSquareCard' in CS and '"account_center_list_other"' in CS)
ck('Q02 gate is an independent entry, not inside insideRecyclerView',
   (lambda t: t.find('boolean squareCard = isAccountSquareCard(v)') >= 0
    and t.find('boolean squareCard = isAccountSquareCard(v)')
        < t.find('} else if (hasBg || isCardName(v))'))(CS))
ck('Q03 square-card size window matches measured cards',
   'w < 500 || w > 600' in CS and 'h < 450 || h > 560' in CS)
ck('Q04 square-card diagnostic is bounded',
   'sSquareCardLines >= 8' in CS and 'SQUARECARD' in CS)

print('\n%d checks, %d failures' % (CHECKS[0], len(FAILS)))
for f in FAILS:
    print('  FAILED: ' + f)
sys.exit(1 if FAILS else 0)
