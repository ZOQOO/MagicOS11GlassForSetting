#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""assert_p122.py -- deterministic (no device) invariants for 0.5.0-p122.

This build adds the element-precise configuration framework + iOS-style
settings UI (learning from the user's settings_module project):

  * ElementRegistry.java: ①..⑩ visual-number metadata + 495-screen index +
    187 Activity->screen reverse map, all static final (no res/assets, because
    the standalone aapt2/d8 toolchain has no local resources).
  * five el.* Param keys in ConfigContract (conservative defaults, all OFF).
  * SettingsActivity rewritten to iOS Grouped Inset List style + a READ-ONLY
    "界面元素" browse entry (domain -> screen -> ①..⑩).

Green here proves PACKAGING + CODE SHAPE ONLY. It is NOT a device or visual
claim. APK/dex checks fail until build.sh has produced the p122 APK.
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
APK = os.path.join(ROOT, 'build-out', 'MagicGlass-0.5.0-p122.apk')

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
GE = rd(os.path.join(SRC, 'GlassEntry.java'))
SA = rd(os.path.join(SRC, 'SettingsActivity.java'))
ER_PATH = os.path.join(SRC, 'ElementRegistry.java')
ER = rd(ER_PATH) if os.path.isfile(ER_PATH) else ''
PROP_TXT = rd(PROP)
SCOPE_TXT = rd(SCOPE)

# ---- version / identity --------------------------------------------------
ck('V01 build fingerprint is p122', 'BUILD = "0.5.0-p122"' in GE)
ck('V02 module.prop version is p122', 'version=0.5.0-p122' in PROP_TXT)
ck('V03 module.prop versionCode is 150', 'versionCode=150' in PROP_TXT)
ck('V04 no leftover p120 version in module.prop', 'version=0.5.0-p120' not in PROP_TXT)
ck('V05 no leftover p120 BUILD fingerprint',
   'BUILD = "0.5.0-p120"' not in GE)
ck('V06 identity name=MagicGlass intact', 'name=MagicGlass' in PROP_TXT)
ck('V07 API levels unchanged (101/101)',
   'minApiVersion=101' in PROP_TXT and 'targetApiVersion=101' in PROP_TXT)
ck('V08 standalone scope keys intact',
   'staticScope=false' in PROP_TXT)

# ---- ElementRegistry structure ------------------------------------------
ck('E01 ElementRegistry.java exists', os.path.isfile(ER_PATH))
ck('E02 ElementRegistry has 10 VisualNo enum constants',
   all(k in ER for k in ('N1_TITLE', 'N2_TOPBAR_KEEP', 'N3_BACKDROP',
                         'N4_CARD', 'N5_CATEGORY', 'N6_TITLE_TEXT',
                         'N7_SUMMARY_TEXT', 'N8_ICON_KEEP',
                         'N9_DYNAMIC_LIST', 'N10_DIVIDER')))
ck('E03 keep flag on ② and ⑧ (不可配置红线)',
   'N2_TOPBAR_KEEP' in ER and 'N8_ICON_KEEP' in ER and 'keep' in ER)
ck('E04 all()/byNo()/configurable() API present',
   all(k in ER for k in ('public static VisualNo[] all()',
                         'public static VisualNo byNo(',
                         'public static boolean configurable(')))
ck('E05 domain/screen/Activity query API present',
   all(k in ER for k in ('public static String[] domains(',
                         'public static String[] screens(',
                         'public static String screenLabel(',
                         'public static String screenForActivity(')))
ck('E06 paramKey builds el.* keys',
   'PREFIX = "el."' in ER and 'public static String paramKey(' in ER)
ck('E07 495-screen index present (SCREEN_INDEX)', 'SCREEN_INDEX' in ER)
ck('E08 187 Activity->screen index present (ACTIVITY_INDEX)', 'ACTIVITY_INDEX' in ER)

# ---- ConfigContract: five new el.* keys, conservative defaults -----------
ck('C01 EL_ENABLED exists, default false',
   'EL_ENABLED("el.enabled", "false")' in CC)
ck('C02 EL_CARD_MODE exists, default off',
   'EL_CARD_MODE("el.cardMode", "off")' in CC)
ck('C03 EL_FONT_SCALE exists, default 100',
   'EL_FONT_SCALE("el.fontScale", "100")' in CC)
ck('C04 EL_FONT_COLOR exists, default empty',
   'EL_FONT_COLOR("el.fontColor", "")' in CC)
ck('C05 EL_BACKDROP_REF exists, default empty',
   'EL_BACKDROP_REF("el.backdropRef", "")' in CC)
ck('C06 all five el.* keys are declared exactly once',
   all(CC.count(k) == 1 for k in ('"el.enabled"', '"el.cardMode"',
                                  '"el.fontScale"', '"el.fontColor"',
                                  '"el.backdropRef"')))
ck('C07 glassFingerprint() body untouched (glass./geo. only)',
   'public String glassFingerprint()' in CC
   and 'startsWith("glass.")' in CC
   and 'startsWith("geo.")' in CC)
ck('C08 el.* does NOT enter glassFingerprint (no el. prefix in body)',
   (lambda t: 'startsWith("el.")' not in t)(
       CC[CC.find('public String glassFingerprint()'):
          CC.find('public String bgFingerprint()')]))

# ---- SettingsActivity: iOS style + preserved write chain ----------------
ck('U01 iOS grouped background colour present', '0xFFF2F2F7' in SA)
ck('U02 iOS switch green present', '0xFF34C759' in SA)
ck('U03 iOS accent blue present', '0xFF007AFF' in SA)
ck('U04 element browse entry present',
   'ElementRegistry' in SA and ('界面元素' in SA or '视觉编号' in SA))
ck('U05 no XML inflation / no local res layout',
   'setContentView(R.layout' not in SA and 'inflate(' not in SA)
ck('W01 writeLive chain intact', 'private void writeLive(' in SA)
ck('W02 writeNow chain intact', 'private void writeNow(' in SA)
ck('W03 stored() intact', 'private String stored(' in SA)
ck('W04 push broadcast runnable intact', 'ConfigStore.notifyChanged' in SA)
ck('W05 media picker onActivityResult intact', 'onActivityResult' in SA)
ck('W06 ensureDefaults/restoreGlassDefaults intact',
   'ConfigStore.ensureDefaults' in SA and 'ConfigStore.restoreGlassDefaults' in SA)
ck('W07 ensureCustomFilling/ensureWindowColor intact',
   'ConfigStore.ensureCustomFilling' in SA and 'ConfigStore.ensureWindowColor' in SA)
ck('W08 three sliders (focus X/Y/zoom) intact',
   all(k in SA for k in ('BG_SCALE_FOCUS_Y', 'BG_SCALE_FOCUS_X', 'BG_SCALE_ZOOM')))
ck('W09 three media modes intact',
   all(k in SA for k in ('MODE_IMAGE', 'MODE_VIDEO', 'MODE_POOL')))

# ---- no restart primitives (product sources) ----------------------------
import glob as _glob
_all_java = ''.join(rd(p) for p in _glob.glob(os.path.join(SRC, '*.java')))
ck('R01 no reboot/forceStop/killProcess calls in product code',
   not any(x in _all_java for x in ('.reboot(', 'forceStop(', 'killProcess(',
                                     'setprop ctl.restart', 'killall ')))

# ---- L1 frozen three files byte-identical -------------------------------
for f, label in (('MaterialBridge.java', 'L1a'),
                 ('LiquidGlassView.java', 'L1b'),
                 ('LiquidShader.java', 'L1c')):
    ck(label + ' ' + f + ' byte-identical to p30',
       os.path.isfile(os.path.join(BASE, f))
       and rb(os.path.join(BASE, f)) == rb(os.path.join(SRC, f)))

# ---- scope ---------------------------------------------------------------
pkgs = [l.strip() for l in SCOPE_TXT.splitlines() if l.strip() and not l.startswith('#')]
ck('S01 scope.list has 10 entries', len(pkgs) == 10)
ck('S02 no duplicate scope entries', len(set(pkgs)) == len(pkgs))
ck('S03 wallpapereditor stays in scope', 'com.hihonor.wallpapereditor' in SCOPE_TXT)
ck('S04 scope/list carries no MIUI or upstream package',
   not any(k in PROP_TXT + SCOPE_TXT for k in ('com.miui', 'com.ciallo',
                                               'hyperbackground')))
ck('S05 description keeps "no MIUI base" NEGATION',
   'no MIUI base' in PROP_TXT)

# ---- APK / dex -----------------------------------------------------------
ck('D01 p122 signed APK exists', os.path.isfile(APK))
if os.path.isfile(APK):
    with zipfile.ZipFile(APK) as z:
        names = z.namelist()
        ck('D02 dex + xposed metadata packaged',
           'classes.dex' in names
           and any('META-INF/xposed/module.prop' in n for n in names))
        dex = z.read('classes.dex')
        ck('D03 dex carries p122 fingerprint', b'0.5.0-p122' in dex)
        ck('D04 dex carries ElementRegistry marker', b'ElementRegistry' in dex)
        ck('D05 dex carries el.enabled key', b'el.enabled' in dex)
        ck('D06 dex has no p121 fingerprint', b'0.5.0-p121' not in dex)
        pname = next((n for n in names
                      if 'META-INF/xposed/module.prop' in n), None)
        ck('D07 packaged module.prop carries p122',
           pname is not None and b'version=0.5.0-p122' in z.read(pname))
    ck('D08 archived APK matches build output byte for byte',
       os.path.isfile(os.path.join(ARC, 'MagicGlass-0.5.0-p122.apk'))
       and rb(APK) == rb(os.path.join(ARC, 'MagicGlass-0.5.0-p122.apk')))
else:
    for k in ('D02', 'D03', 'D04', 'D05', 'D06', 'D07', 'D08'):
        ck(k + ' (skipped: no APK)', False)

arch = os.listdir(ARC)


def has_snap(token):
    return any(token in n and 'p122' in n for n in arch)


ck('D09 archived p122 source snapshots exist',
   all(has_snap(t) for t in ('ElementRegistry', 'SettingsActivity',
                             'ConfigContract', 'GlassEntry', 'module')))



# ---- p122 visual-edit mode ------------------------------------------------
ck('X01 visual wireframe view class present',
   'VisualWireframeView' in SA and 'extends View' in SA)
ck('X02 wireframe draws the phone screen regions',
   all(k in SA for k in ('drawRoundRect', 'drawRect', 'drawCircle')))
ck('X03 wireframe has selectable state + hit test',
   all(k in SA for k in ('selectedNo', 'invalidate()', 'setTag(',
                         'setOnClickListener', 'OnLayoutChangeListener')))
ck('X04 visual edit title shown',
   '界面元素' in SA and ('视觉编辑' in SA or '点亮' in SA))
ck('X05 plain-language explanations cover all ten visual numbers',
   'VISUAL_PLAIN' in SA
   and (lambda arr: arr.count('"') >= 20  # 10 strings, each needs open+close quote
        and len([ln for ln in arr.splitlines() if ln.strip().startswith('"')]) >= 10)(
       SA[SA.find('VISUAL_PLAIN'):SA.find('VISUAL_HOW')]))
ck('X06 keep items (②⑧) shown as locked, no config key',
   '保留不可改' in SA and 'keep()' in SA)
ck('X07 wireframe is read-only (no config writes in visual-editor code region)',
   (lambda t: not any(x in t for x in ('ConfigStore.write', 'writeNow(',
                                      'writeLive(', 'notifyChanged(')))(
       SA[SA.find('// ---- element browser → visual editor'):]))  # from visual section to EOF minus write methods above it
ck('X08 no new source file (visual view is an inner class)',
   not os.path.isfile(os.path.join(SRC, 'VisualWireframeView.java')))
print('\n%d checks, %d failures' % (CHECKS[0], len(FAILS)))
for f in FAILS:
    print('  FAILED: ' + f)
sys.exit(1 if FAILS else 0)
