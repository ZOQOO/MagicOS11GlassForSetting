#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""assert_p173.py -- deterministic (no device) invariants for 0.5.0-p173.

This build adds the element-precise configuration framework + iOS-style
settings UI (learning from the user's settings_module project):

  * ElementRegistry.java: ①..⑩ visual-number metadata + 495-screen index +
    187 Activity->screen reverse map, all static final (no res/assets, because
    the standalone aapt2/d8 toolchain has no local resources).
  * five el.* Param keys in ConfigContract (conservative defaults, all OFF).
  * SettingsActivity rewritten to iOS Grouped Inset List style + a READ-ONLY
    "界面元素" browse entry (domain -> screen -> ①..⑩).

Green here proves PACKAGING + CODE SHAPE ONLY. It is NOT a device or visual
claim. APK/dex checks fail until build.sh has produced the p126 APK.
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
APK = os.path.join(ROOT, 'build-out', 'MagicGlass-0.5.0-p173.apk')

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
ck('V01 build fingerprint is p173', 'BUILD = "0.5.0-p173"' in GE)
ck('V02 module.prop version is p173', 'version=0.5.0-p173' in PROP_TXT)
ck('V03 module.prop versionCode is 200', 'versionCode=200' in PROP_TXT)
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
ck('D01 p173 signed APK exists', os.path.isfile(APK))
if os.path.isfile(APK):
    with zipfile.ZipFile(APK) as z:
        names = z.namelist()
        ck('D02 dex + xposed metadata packaged',
           'classes.dex' in names
           and any('META-INF/xposed/module.prop' in n for n in names))
        dex = z.read('classes.dex')
        ck('D03 dex carries p173 fingerprint', b'0.5.0-p173' in dex)
        ck('D04 dex carries ElementRegistry marker', b'ElementRegistry' in dex)
        ck('D05 dex carries el.enabled key', b'el.enabled' in dex)
        ck('D06 dex has no p171 fingerprint', b'0.5.0-p171' not in dex)
        pname = next((n for n in names
                      if 'META-INF/xposed/module.prop' in n), None)
        ck('D07 packaged module.prop carries p173',
           pname is not None and b'version=0.5.0-p173' in z.read(pname))
    ck('D08 archived APK matches build output byte for byte',
       os.path.isfile(os.path.join(ARC, 'MagicGlass-0.5.0-p173.apk'))
       and rb(APK) == rb(os.path.join(ARC, 'MagicGlass-0.5.0-p173.apk')))
else:
    for k in ('D02', 'D03', 'D04', 'D05', 'D06', 'D07', 'D08'):
        ck(k + ' (skipped: no APK)', False)

arch = os.listdir(ARC)


def has_snap(token):
    return any(token in n and 'p173' in n for n in arch)


ck('D09 archived p173 source snapshots exist',
   all(has_snap(t) for t in ('ElementRegistry', 'SettingsActivity',
                             'ConfigContract', 'GlassEntry', 'module')))



# ---- p126 visual-edit mode ------------------------------------------------
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


# ---- p126 real L1/L2 settings navigation -----------------------------------
ER_TXT = rd(os.path.join(SRC, 'ElementRegistry.java'))
ck('N01 real navigation tables present',
   all(k in ER_TXT for k in ('NAV_L1', 'NAV_L2', 'navL1()', 'navL2(')))
ck('N02 L1 navigation has 53 entries',
   (lambda t: t.count('|') == 53)(
      __import__('re').search(r'NAV_L1 = new String\[\] \{(.*?)\};',
                             ER_TXT, __import__('re').S).group(1))
      if __import__('re').search(r'NAV_L1 = new String\[\] \{(.*?)\};',
                                 ER_TXT, __import__('re').S) else False)
ck('N03 L2 navigation has 193 valid entries',
   (lambda t: t.count('|') == 193 * 2)(
      __import__('re').search(r'NAV_L2 = new String\[\] \{(.*?)\};',
                             ER_TXT, __import__('re').S).group(1))
      if __import__('re').search(r'NAV_L2 = new String\[\] \{(.*?)\};',
                                 ER_TXT, __import__('re').S) else False)
ck('N04 label lookup APIs present',
   'navL1Label(' in ER_TXT and 'navL2Label(' in ER_TXT)
ck('N05 visual editor uses real L1/L2 navigation',
   'navL1()' in SA and 'navL2(' in SA)
ck('N06 technical domain chips removed from visual editor',
   'ElementRegistry.domains()' not in SA[SA.find('openElementBrowser'):])


# ---- p126 ID-hit diagnostic hook -------------------------------------------
GE_TXT = rd(os.path.join(SRC, 'GlassEntry.java'))
ER_TXT2 = rd(os.path.join(SRC, 'ElementRegistry.java'))
ck('H01 PreferenceBindHooker present',
   'PreferenceBindHooker' in GE_TXT and 'implements XposedInterface.Hooker' in GE_TXT)
ck('H02 PREFBIND diagnostic log emitted',
   'PREFBIND' in GE_TXT and 'mg-state PREFBIND' in GE_TXT)
ck('H03 hook uses reflection only (no compile-time androidx import)',
   not any(l.strip().startswith('import androidx') for l in GE_TXT.splitlines())
   and 'Class.forName("androidx.preference.Preference"' in GE_TXT
   and 'getKey' in GE_TXT)
ck('H04 last activity class tracked for screen reverse lookup',
   'sLastActivityClass' in GE_TXT and 'screenForActivity' in GE_TXT)
ck('H05 KEY_INDEX + lookup APIs present',
   'KEY_INDEX' in ER_TXT2 and 'globalIdFor(' in ER_TXT2 and 'dimsFor(' in ER_TXT2)
ck('H06 hook body is logging only (no render/config writes)',
   (lambda t: not any(x in t for x in ('ConfigStore.write', 'writeNow(',
                                      'writeLive(', 'notifyChanged(')))(
       GE_TXT[GE_TXT.find('class PreferenceBindHooker'):
              GE_TXT.find('class PreferenceBindHooker') + 3500]))


# ---- p126 screen-independent key hit fix -----------------------------------
GE2 = rd(os.path.join(SRC, 'GlassEntry.java'))
ER3 = rd(os.path.join(SRC, 'ElementRegistry.java'))
ck('K01 global key APIs present',
   all(k in ER3 for k in ('globalIdByKey(', 'dimsByKey(', 'keyAmbiguity(',
                          'keyRows(')))
ck('K02 hook falls back to global key when screen missing',
   'globalIdByKey(key)' in GE2 and 'keyAmbiguity(key)' in GE2)
ck('K03 strict screen+key path kept when screen known',
   (lambda t: t.find('globalIdFor(screen, key)') >= 0
              and t.find('screen != null') >= 0)(GE2))
ck('K04 amb field emitted in PREFBIND log',
   '" amb=" + amb' in GE2 and 'int amb' in GE2)
ck('K05 logPrefBind signature carries amb',
   'String gid, String dims, int amb, View v' in GE2)
ck('K06 still logging-only (no render/config writes)',
   not any(x in GE2[GE2.find('class PreferenceBindHooker'):
                    GE2.find('class PreferenceBindHooker') + 4000]
           for x in ('ConfigStore.write', 'writeNow(', 'writeLive(',
                     'notifyChanged(', 'setBackground(', 'setVisibility(')))


# ---- p126 per-global-id config + font application --------------------------
CC2 = rd(os.path.join(SRC, 'ConfigContract.java'))
CS2 = rd(os.path.join(SRC, 'ConfigStore.java'))
GE3 = rd(os.path.join(SRC, 'GlassEntry.java'))
SA3 = rd(os.path.join(SRC, 'SettingsActivity.java'))
ck('R01 EL_RULES key present with empty default',
   'EL_RULES("el.rules", "")' in CC2)
ck('R02 rule parse/set APIs present',
   all(k in CC2 for k in ('elRuleGet(', 'elRuleSet(', 'elRuleConfigurable(')))
ck('R03 writeElRule API writes via existing chain',
   'writeElRule(' in CS2
   and (lambda t: 'write(c, slot, ConfigContract.Param.EL_RULES, updated, true)' in t
        or 'write(c, slot, ConfigContract.Param.EL_RULES, updated, True)' in t)(CS2))
ck('R04 hook applies font by gid',
   'applyElFont(' in GE3 and 'elRuleGet(' in GE3)
ck('R05 ELFONT diagnostic log emitted',
   'ELFONT' in GE3 and 'mg-state ELFONT' in GE3)
ck('R06 title/summary ids targeted',
   '0x1020016' in GE3 and '0x1020010' in GE3)
ck('R07 UI per-element font editor present',
   'fontColor' in SA3 and 'fontScale' in SA3 and 'writeElRule' in SA3)
ck('R08 keep (②⑧) blocks editing in UI',
   'configurable(' in SA3 and '保留不可改' in SA3)
ck('R09 no render writes inside the new font-application region',
   (lambda t: not any(k in t for k in ('setBackground(', 'setVisibility(',
                                      'setAlpha(', 'ConfigStore.write')))(
       GE3[GE3.find('private static void applyElFont'):
           GE3.find('private static void applyElFont') + 5000]))

# ---- p127: static-card discovery timing fix (findings §121/§122) ------------
SC127 = rd(os.path.join(SRC, 'CardScanner.java'))
ck('P01 staticStructureSig folds in list childCount (late inflate detected)',
   'staticStructureSig' in SC127
   and '((ViewGroup) v).getChildCount()' in SC127)
ck('P02 timing fix located inside staticStructureSig, not in frame path',
   (lambda t: t.find('((ViewGroup) v).getChildCount()') > t.find('staticStructureSig(View decor)'))(SC127))
ck('P03 square-card gate criteria unchanged from p120',
   'w < 500 || w > 600' in SC127
   and 'h < 450 || h > 560' in SC127
   and '"account_center_list_other"' in SC127)
ck('P04 no leftover p127 diagnostic (SQREJ) markers',
   not any(k in SC127 for k in ('SQREJ', 'logSquareReject', 'sSqRejLines',
                                'hasAccountSquareParent', 'diagnoseSquareReject')))
ck('P05 SQUARECARD diagnostic still bounded',
   'sSquareCardLines >= 8' in SC127 and 'SQUARECARD' in SC127)


# ---- p128: 半屏白自绘行 suppress（findings §125/§126） --------------------
RC128 = rd(os.path.join(SRC, 'RenderController.java'))
ck('H01 suppressAccountHalfWhite method exists',
   'suppressAccountHalfWhite' in RC128 and 'private void suppressAccountHalfWhite' in RC128)
ck('H02 EXT_ID hard gate',
   (lambda t: t.find('private void suppressAccountHalfWhite') < len(t)
    and 'accountIdGate()' in t[t.find('private void suppressAccountHalfWhite'):
                              t.find('private void suppressAccountHalfWhite')+700])(RC128))
ck('H03 narrow gate id/class/size',
   all(k in RC128 for k in ('"hwlistpattern_parent"','proportiontextlayout','h < 140 || h > 260','0.9f * decor.getWidth()')))
ck('H04 Saved latch + setWillNotDraw + restore path',
   (lambda t: 's.willNotDraw = true' in t and 'v.setWillNotDraw(true)' in t
    and 'saveAndClear(v)' in t)(RC128[RC128.find('private void suppressAccountHalfWhite'):
                                      RC128.find('private void suppressAccountHalfWhite')+900]))
ck('H05 HALFWHITE bounded + per-page reset',
   'halfWhiteLogLines >= 24' in RC128 and 'mg-state HALFWHITE' in RC128
   and 'halfWhiteLogLines = 0' in RC128)
ck('H06 both entry paths call suppress',
   RC128.count('suppressAccountHalfWhite(v);') == 2)
ck('H07 L1 freeze (render files)',
   all(open(os.path.join(ROOT,'probe','probe-report','baselines','L1-ACCEPTED-p30','src','main','java','com','magicglass',f),'rb').read()==
       open(os.path.join(SRC,f),'rb').read() for f in ('MaterialBridge.java','LiquidGlassView.java','LiquidShader.java')))

# ---- p129: 账号安全顶部去玻璃 + 四卡加玻璃 -------------------------------
SC129 = rd(os.path.join(SRC, 'CardScanner.java'))
ck('N01 square-card list detected by exact id',
   'boolean squareCardList = "account_center_list_other".equals(listRid)' in SC129)
ck('N02 p129 wrong-delete reverted (5 rows keep glass)',
   'if (noGlassRows) continue;' not in SC129
   and 'squareCardList' in SC129)
ck('N03 square-card list detected by exact id',
   'boolean squareCardList = "account_center_list_other".equals(listRid)' in SC129)
ck('N04 square-card width gate relaxed + height guard',
   'float widthGate = squareCardList ? 0.40f : MIN_WIDTH_RATIO' in SC129
   and 'squareCardList && (fullH < 450f || fullH > 560f)' in SC129)
ck('N05 other lists keep byte-identical width gate', 'MIN_WIDTH_RATIO = 0.75f' in SC129)

# ---- p130: 顶部盾牌白底隐藏 + 恢复 ---------------------------------------
RC130 = rd(os.path.join(SRC, 'RenderController.java'))
ck('S01 shield-bg hide method exists with exact id',
   'private void hideAccountShieldBg' in RC130
   and '"hv_ripple_view_bg".equals(resourceEntry(v))' in RC130)
ck('S02 hidden via setVisibility(GONE) + tracked',
   'v.setVisibility(View.GONE)' in RC130 and 'accountShieldBgHidden = v' in RC130)
ck('S03 detach restores visibility',
   'private void restoreAccountShieldBg' in RC130
   and 'accountShieldBgHidden.setVisibility(View.VISIBLE)' in RC130
   and 'restoreAccountShieldBg();' in RC130)
ck('S04 EXT_ID hard gate at call site',
   'slot == ConfigContract.Slot.EXT_ID) hideAccountShieldBg(v)' in RC130)
ck('S05 SHIELDBG diagnostic bounded', 'mg-state SHIELDBG' in RC130)
# ---- p130: collectLists 收编四卡列表（真正让 0.40 门生效）---------------
SC130 = rd(os.path.join(SRC, 'CardScanner.java'))
ck('S06 collectLists admits square-card list by id',
   'boolean accountSquareList =' in SC130
   and '"account_center_list_other".equals(resourceName(v))' in SC130)
ck('S07 square-card list bypasses height gate',
   'if (accountSquareList' in SC130
   and '|| (v.getGlobalVisibleRect(r) && r.height() > 600))' in SC130)
# ---- p130: collectLists 收编四卡列表（真正让 0.40 门生效）---------------
SC130 = rd(os.path.join(SRC, 'CardScanner.java'))
ck('S06 collectLists admits square-card list by id',
   'boolean accountSquareList =' in SC130
   and '"account_center_list_other".equals(resourceName(v))' in SC130)
ck('S07 square-card list bypasses height gate',
   'if (accountSquareList' in SC130
   and '|| (v.getGlobalVisibleRect(r) && r.height() > 600))' in SC130)
# ---- p131: 首帧隐藏盾牌白底（早窗口路径）---------------------------------
RC131 = rd(os.path.join(SRC, 'RenderController.java'))
ck('T01 shield-bg hidden on early stripRowsIn path',
   RC131.count('if (slot == ConfigContract.Slot.EXT_ID) hideAccountShieldBg(v);') == 2)
ck('T02 early call is before stripExtWhiteChild in stripRowsIn',
   (lambda t: t.find('hideAccountShieldBg(v);') < t.find('stripExtWhiteChild(v);'))(
       RC131[RC131.find('private void stripRowsIn'):]))
# ---- p132: 返回时(finishing)不恢复白底，避免过场白闪 --------------------
RC132 = rd(os.path.join(SRC, 'RenderController.java'))
ck('U01 restore only on non-finishing path',
   (lambda t: t.find('accountShieldBgHidden = null;') > t.find('if (finishing) {')
              and t.find('accountShieldBgHidden = null;') < t.find('} else {')
              and t.find('restoreAccountShieldBg();') > t.find('} else {'))(
       RC132[RC132.find('if (finishing) {'):RC132.find('if (finishing) {')+1600]))
ck('U02 only one VISIBLE restore for shield-bg',
   RC132.count('accountShieldBgHidden.setVisibility(View.VISIBLE)') == 1)
# ---- p134: 流量管理 ListView 进 listCache（motion/跟手修复）---------------
SC134 = rd(os.path.join(SRC, 'CardScanner.java'))
ck('V01 traffic_apps_list ListView admitted by exact id',
   '"traffic_apps_list".equals(resourceName(v))' in SC134
   and 'endsWith("ListView")' in SC134)
ck('V02 admission is id-gated (no global ListView change)',
   (lambda t: t.find('"traffic_apps_list".equals(resourceName(v))')
              < t.find('endsWith("RecyclerView")'))(
       SC134[SC134.find('private static boolean collectLists'):]))
ck('V03 MAX_TOKEN_CHILDREN still covers visible rows',
   'MAX_TOKEN_CHILDREN = 24' in SC134)
# ---- p138: 荣耀互联 card_item 白背景剥除 -------------------------------
RC138 = rd(os.path.join(SRC, 'RenderController.java'))
ck('W01 card_item strip method with measured gate',
   'private void stripCardItemWhite' in RC138
   and 'measuredOpaqueWhite(bg)' in RC138)
ck('W02 deep walk reaches depth 24 (card_item depth=21)',
   'depth > 24' in RC138 and 'probeCardItemWalk' in RC138)
ck('W03 EXT_CONTROLLED hard gate at strip',
   'slot == ConfigContract.Slot.EXT_CONTROLCENTER) stripCardItemWhite(v)' in RC138)
ck('W04 idempotent via Saved latch',
   'prior.placeholder != null' in RC138 and 'saveAndClear(v)' in RC138)
ck('W05 CARDSTRIP diagnostic', 'mg-state CARDSTRIP' in RC138)
# ---- p140: 白卡剥除提前到首帧早窗口 --------------------------------------
RC140 = rd(os.path.join(SRC, 'RenderController.java'))
ck('X01 early-window walk in latchListRowsNow',
   'private void latchListRowsNow' in RC140
   and 'probeCardItemWalk(decor, 0, new int[]{400});' in RC140)
ck('X02 early walk throttled 150ms + EXT_CONTROLLED gate',
   'lastCardStripWalkMs' in RC140
   and 'now - lastCardStripWalkMs >= 150L' in RC140
   and 'slot == ConfigContract.Slot.EXT_CONTROLCENTER' in RC140)
ck('X03 throttle reset on detach', 'lastCardStripWalkMs = 0L' in RC140)
ck('X04 all card-walk paths present',
   RC140.count('probeCardItemWalk(decor, 0, new int[]{400});') == 4)
# ---- p141: 新创建 card_item 的 150ms 独立补扫 ------------------------------
RC141 = rd(os.path.join(SRC, 'RenderController.java'))
ck('Y01 independent 150ms card re-scan after early window',
   'slot == ConfigContract.Slot.EXT_CONTROLCENTER && decor != null'
   in RC141 and 'now - lastCardStripWalkMs >= 150L' in RC141)
ck('Y02 four card walk calls (layout/early/late-150/top-600)',
   RC141.count('probeCardItemWalk(decor, 0, new int[]{400});') == 4)
ck('Y03 note about newly-created View',
   '全新 View' in RC141)
# ---- p142: layout-time strip 首帧不显示白卡(p44方案复用) -----------------
RC142 = rd(os.path.join(SRC, 'RenderController.java'))
ck('Z01 card walk in OnGlobalLayout callback',
   'public void onGlobalLayout()' in RC142
   and 'probeCardItemWalk(decor, 0, new int[]{400});' in RC142)
ck('Z02 four card-walk paths (layout/early/onDraw150/top600)',
   RC142.count('probeCardItemWalk(decor, 0, new int[]{400});') == 4)
ck('Z03 EXT_CONTROLLED gate at layout callback',
   'slot == ConfigContract.Slot.EXT_CONTROLCENTER' in RC142)
# ---- p143: 通知共享页 ListView 收编 + 设置行首帧剥白 ----------------------
SC143 = rd(os.path.join(SRC, 'CardScanner.java'))
RC143 = rd(os.path.join(SRC, 'RenderController.java'))
ck('N01 notify listview admitted by exact id',
   '"super_all_package_manager_listview".equals(resourceName(v))' in SC143)
ck('N02 traffic list branch untouched',
   '"traffic_apps_list".equals(resourceName(v))' in SC143)
ck('N03 notify strip method with precise id gate',
   'private void stripNotifySwitchWhite' in RC143
   and '"main_switcher".equals(rid)' in RC143
   and '"watch_switcher".equals(rid)' in RC143)
ck('N04 EXT_SYSTEMMANAGER hard gate (p144: notify+yoyo dual gate)',
   'boolean notify = slot == ConfigContract.Slot.EXT_SYSTEMMANAGER' in RC143
   and 'if (!notify && !yoyo) return;' in RC143)
ck('N05 layout-time strip for notify rows',
   'probeNotifySwitchWalk(decor, 0, new int[]{300});' in RC143
   and 'public void onGlobalLayout()' in RC143)
ck('N06 fade guard + Saved latch',
   'isFadeTopLayer(v.getClass().getSimpleName(), rid)' in RC143
   and 'saveAndClear(v)' in RC143)
ck('N07 NOTIFSTRIP diagnostic (p144: dynamic YOYOSTRIP/NOTIFSTRIP)',
   '"YOYOSTRIP"' in RC143 and '"NOTIFSTRIP"' in RC143 and '"BATTSTRIP"' in RC143)
# ---- p144: 三项修正 --------------------------------------------------------
SC144 = rd(os.path.join(SRC, 'CardScanner.java'))
RC144 = rd(os.path.join(SRC, 'RenderController.java'))
# ① 彩色卡去玻璃
ck('A1 intro card excluded from generic glass',
   '"intro_card_layout".equals(rn)' in SC144)
# ③ ViewPager unclipped rect 防跳闪
ck('A2 isInViewPager + unclippedScreenRect helpers',
   'private static boolean isInViewPager' in SC144
   and 'private static Rect unclippedScreenRect' in SC144)
ck('A3 generic uses unclipped rect when in ViewPager',
   'if (isInViewPager(v))' in SC144 and 'r = full;' in SC144)
ck('A4 non-viewpager path unchanged (visible rect still primary)',
   'if (!v.getGlobalVisibleRect(r)) continue;' in SC144)
# ② YOYO 四项卡首帧剥白 + 返回
ck('A5 strip supports EXT_ASSISTANT + id=item',
   'slot == ConfigContract.Slot.EXT_ASSISTANT' in RC144
   and '"item".equals(rid)' in RC144)
ck('A6 item parent gate switch_check_gridView',
   '"switch_check_gridView".equals(' in RC144)
ck('A7 YOYOSTRIP diagnostic', 'YOYOSTRIP' in RC144)
ck('A8 assistant in walk + layout callback',
   RC144.count('slot == ConfigContract.Slot.EXT_ASSISTANT') >= 3)
# ---- p145: 页面级装饰容器排除 + clamp 过宽块 ------------------------------
SC145 = rd(os.path.join(SRC, 'CardScanner.java'))
ck('B1 page decor container helper with exact ids',
   'private static boolean isPageDecorContainer' in SC145
   and '"theme_module_root".equals(rn)' in SC145
   and '"super_device_settings_incard".equals(rn)' in SC145)
ck('B2 excluded in walkStatic and isCardLike',
   SC145.count('isPageDecorContainer(v)') == 2)
ck('B3 clamped unclipped rect to screen',
   'full.right = Math.min(full.right, dr.right)' in SC145)
ck('B4 prior unclipped path retained (p144 one-layer win)',
   'if (isInViewPager(v))' in SC145)
# ---- p146: 排除容器子树连带（预览卡两块 + 彩色卡文字行残留）--------------
SC146 = rd(os.path.join(SRC, 'CardScanner.java'))
ck('C1 new excluded ids',
   '"intro_card_title".equals(rn)' in SC146
   and '"wallpaper_main_root".equals(rn)' in SC146
   and '"wallpaper_history_image_container".equals(rn)' in SC146)
ck('C2 ancestor-inclusive exclusion helper',
   'private static boolean isUnderExcludedContainer' in SC146
   and 'isPageDecorContainer(cur)' in SC146)
ck('C3 applied at both walk entry points',
   SC146.count('if (isUnderExcludedContainer(v)) return;') == 2)
# ---- p147: list 路径也拦截排除容器（预览卡残留两块）--------------------
SC147 = rd(os.path.join(SRC, 'CardScanner.java'))
ck('D1 history_viewpager_root excluded',
   '"history_viewpager_root".equals(rn)' in SC147)
ck('D2 list-row loop also intercepts excluded container',
   (lambda t: t.find('if (isUnderExcludedContainer(child)) continue;')
              > t.find('for (int i = 0; i < g.getChildCount(); i++)'))(SC147))
ck('D3 strip still added before exclude',
   (lambda t: t.find('strip.add(child);')
              < t.find('if (isUnderExcludedContainer(child)) continue;'))(SC147))
ck('D4 three discovery paths all guarded',
   SC147.count('isUnderExcludedContainer(') >= 4)
# ---- p147: 顶栏半透明白大卡(top_gradient_bg)按精确id例外剥除 --------------
RC147 = rd(os.path.join(SRC, 'RenderController.java'))
ck('E1 top_gradient_bg exact-id exception before fade keep',
   (lambda t: t.find('"top_gradient_bg".equals(rid)')
              < t.find('logTopContainer(v, rid, "fade", false)',
                       t.find('"top_gradient_bg".equals(rid)')))(RC147))
ck('E2 exception uses suppressTopContainer',
   (lambda t: 'suppressTopContainer(v, rid)' in t[
       t.find('"top_gradient_bg".equals(rid)'):
       t.find('"top_gradient_bg".equals(rid)')+200])(RC147))
# ---- p148: 桌面个性化浅白块 src/自绘白首帧隐藏 ----------------------------
RC148 = rd(os.path.join(SRC, 'RenderController.java'))
ck('F1 thumb hide method with exact gates',
   'private void hideWallpaperThumbWhite' in RC148
   and 'slot != ConfigContract.Slot.EXT_WALLPAPEREDITOR' in RC148
   and '"history_viewpager_root".equals(resourceEntry((View) p))' in RC148)
ck('F2 hidden via GONE + tracked',
   'wallpaperThumbHidden.add(v)' in RC148 and 'v.setVisibility(View.GONE)' in RC148)
ck('F3 two walk paths (layout-time + early)',
   RC148.count('probeWallpaperThumbWalk(decor, 0, new int[]{300});') == 2)
ck('F4 detach both directions',
   'restoreWallpaperThumbWhite();' in RC148
   and 'wallpaperThumbHidden.clear();' in RC148)
ck('F5 THUMBHIDE diagnostic', 'mg-state THUMBHIDE' in RC148)
# ---- p149: 灰块外层容器隐藏 + blocks 兜底过滤 ------------------------------
RC149 = rd(os.path.join(SRC, 'RenderController.java'))
SC149 = rd(os.path.join(SRC, 'CardScanner.java'))
ck('G1 FrameLayout added to hide gate',
   '"FrameLayout".equals(cls)' in RC149)
ck('G2 block final defense filter',
   'private List<Block> dropExcludedBlocks' in SC149
   and 'all = dropExcludedBlocks(all);' in SC149)
ck('G3 filter checks each block rows',
   'for (View row : b.rows)' in SC149
   and 'isUnderExcludedContainer(row)' in SC149)
# ---- p150: 顶栏白层 layout-time 首帧剥除 -------------------------------
RC150 = rd(os.path.join(SRC, 'RenderController.java'))
ck('H1 top-gradient walk method with exact id',
   'private void probeTopGradientWalk' in RC150
   and '"top_gradient_bg".equals(resourceEntry(v))' in RC150)
ck('H2 uses suppressTopContainer (Saved latch)',
   (lambda t: 'suppressTopContainer(v, "top_gradient_bg")' in t)(
       RC150[RC150.find('private void probeTopGradientWalk'):RC150.find('private void probeTopGradientWalk')+600]))
ck('H3 two paths layout-time + early',
   RC150.count('probeTopGradientWalk(decor, 0, new int[]{200});') == 2)
# ---- p151: 滑动卡图片区白占位剥 bg + 诊断 ------------------------------
RC151 = rd(os.path.join(SRC, 'RenderController.java'))
ck('I1 wallpaper image bg strip method exact id',
   'private void stripWallpaperImageBg' in RC151
   and '"wallpaper_history_image".equals(resourceEntry(v))' in RC151)
ck('I2 saveAndClear only (src untouched)',
   (lambda t: 'saveAndClear(v)' in t and 'setImageDrawable(null)' not in t)(
       RC151[RC151.find('private void stripWallpaperImageBg'):RC151.find('private void stripWallpaperImageBg')+900]))
ck('I3 WPIMGBG diagnostic with src type',
   'mg-state WPIMGBG' in RC151 and 'WPIMGBG' in RC151 and '" src=" + src' in RC151)
ck('I4 two paths', RC151.count('probeWallpaperImageWalk(decor, 0, new int[]{300});') == 2)
# ---- p152: 过渡态白卡每帧检查(1~2.4s窗口) -------------------------------
RC152 = rd(os.path.join(SRC, 'RenderController.java'))
ck('J1 transient white walk (p153 back-button gate)',
   'private void probeTransientWhite' in RC152
   and 'loc[0] >= 45 && loc[0] <= 85' in RC152)
ck('J2 per-frame check hooked in scanAndBind',
   (lambda t: t.find('checkTransientWhite();') > t.find('suppressor.takeRevived();')
              and t.find('checkTransientWhite();') < t.find('List<RectF> rects = scanner.rects'))(RC152))
ck('J3 legacy TRANSWHITE replaced by LEFTGRAD (p154)',
   'mg-state LEFTGRAD' in RC152 and 'srcOnly=1' in RC152)
ck('J4 throttle reset on detach', 'lastTransientWhiteMs = 0L' in RC152)
# ---- p153: 录屏像素定因——返回按钮浅蓝占位 ------------------------------
RC153 = rd(os.path.join(SRC, 'RenderController.java'))
ck('K1 geometry gates present (back+leftgrad)',
   'boolean backBtn' in RC153 and 'boolean leftGrad' in RC153)
ck('K2 no near-white predicate (shallow blue)',
   (lambda t: 'measuredOpaqueWhite(bg)' not in t[
       t.find('private void probeTransientWhite'):
       t.find('private void probeTransientWhite')+1600])(RC153))
ck('K3 diagnostic covers src type',
   'srcOnly=1' in RC153 and '" src=" + src' in RC153)
# ---- p154: 左渐变块几何门(录屏全屏像素扫描定因) ----------------------------
RC154 = rd(os.path.join(SRC, 'RenderController.java'))
ck('L1 left-gradient geometry gate',
   'boolean leftGrad = loc[0] <= 40 && loc[1] >= 420' in RC154
   and 'w >= 400 && w <= 720 && h >= 140 && h <= 260' in RC154)
ck('L2 back button no longer touched',
   '// 只处理左渐变块；返回按钮已证是图标，不再碰。' in RC154
   and 'if (leftGrad) {' in RC154)
ck('L3 LEFTGRAD diagnostic', 'mg-state LEFTGRAD' in RC154)

# ---- p155: 荣耀绿洲护眼两处玻璃块(祖先连带排除) -------------------------
SC155 = rd(os.path.join(SRC, 'CardScanner.java'))
ck('M1 oasis_intro_card + recommend_footer excluded by exact id',
   '"oasis_intro_card".equals(rn)' in SC155
   and '"recommend_footer".equals(rn)' in SC155)
ck('M2 downward excluded-child helper present',
   'private static boolean hasExcludedChild(View v)' in SC155
   and 'if (isPageDecorContainer(c)) return true;' in SC155)
ck('M3 list-row loop intercepts excluded child',
   (lambda t: t.find('if (hasExcludedChild(child)) continue;')
              > t.find('if (isUnderExcludedContainer(child)) continue;'))(SC155))
ck('M4 block-level final defense also checks child',
   (lambda t: 'hasExcludedChild(row)' in t[
       t.find('private List<Block> dropExcludedBlocks'):
       t.find('private List<Block> scanAllRows(')])(SC155))
ck('M5 both prior strip-before-exclude invariants intact',
   SC155.count('if (isUnderExcludedContainer(v)) return;') == 2
   and SC155.count('if (isUnderExcludedContainer(') >= 4)


# ---- p156: 散热风扇/声音横幅去玻璃 + 通知页列表/白卡 -------------------
SC156 = rd(os.path.join(SRC, 'CardScanner.java'))
RC156 = rd(os.path.join(SRC, 'RenderController.java'))
ck('N1 thermal fan exact id excluded',
   '"thermal_fan_video_play".equals(rn)' in SC156)
ck('N2 sound hero card gated by id+unique parent',
   '"card_view".equals(resourceName(cur))' in SC156
   and 'hasAncestorResource(cur, "layout_protrait")' in SC156
   and 'private static boolean hasAncestorResource' in SC156)
ck('N3 generic card_view id NOT added to isPageDecorContainer',
   '"card_view".equals(rn)' not in SC156)
ck('N4 notify app list ListView admitted',
   '"all_package_manager_listview".equals(resourceName(v))' in SC156
   and SC156.count('all_package_manager_listview') >= 2)
ck('N5 notify sub-page first row strip id added',
   '"prefs_notification_main_switch".equals(rid)' in RC156)
ck('N6 early-window notify strip with throttle + detach reset',
   'lastNotifyStripWalkMs' in RC156
   and RC156.count('lastNotifyStripWalkMs = 0L') == 1
   and 'probeNotifySwitchWalk(decor, 0, new int[]{300});' in RC156[RC156.find('private void latchListRowsNow'):])


# ---- p159: 应用白卡 / 电池详情白闪 / 隐私钱包 ScrollView --------------
SC159 = rd(os.path.join(SRC, 'CardScanner.java'))
RC159 = rd(os.path.join(SRC, 'RenderController.java'))
ck('Q1 app nested RecyclerView admitted by exact id',
   '"external_app_settings_list".equals(resourceName(v))' in SC159)
ck('Q2 privacy/wallet ScrollView admitted by exact ids',
   '"sv_container".equals(resourceName(v))' in SC159
   and '"traffic_card_wallet_scroll_view".equals(resourceName(v))' in SC159)
ck('Q3 strip-only scroll helper (no row generation)',
   'private static void stripScrollRows' in SC159
   and 'collectScrollRows' not in SC159
   and 'SCROLL_ROW_BUDGET' in SC159)
ck('Q4 scroll branch generates glass rows with dedup (p165 restored)',
   (lambda t: t.find('stripScrollRows(g, listClip, rows, strip, 0,')
              > t.find('for (View list : lists(decor))')
              and 'scrollRowsAdded = rows.size() > before' in t)(SC159))
ck('Q5 battery detail rows added to layout-time strip',
   '"app_battery_bar_chart_layout".equals(rid)' in RC159
   and 'BATTSTRIP' in RC159)
ck('Q6 prior fix ids intact (no regression)',
   '"notification_package_left_layout".equals(rn)' in SC159
   and '"prefs_notification_main_switch".equals(rid)' in RC159)


# ---- p161: 应用页 dashboard_tile 首帧剥白(OemCardSuppressor) -----------
OC161 = rd(os.path.join(SRC, 'OemCardSuppressor.java'))
ck('R1 dashboard_tile admitted by exact id in discovery walk',
   '"dashboard_tile".equals(vres)' in OC161
   and '(isTarget(cls) || byId)' in OC161)
ck('R2 discovery-time resourceName helper present',
   'private static String resourceName(View v)' in OC161
   and 'getResourceEntryName(id)' in OC161)
ck('R3 strip-on-discover path (bg+fg) still intact',
   'v.setBackground(null)' in OC161 and 'v.setForeground(null)' in OC161)
ck('R4 p160 scroll-only rollback intact (no regression)',
   'stripScrollRows' in rd(os.path.join(SRC, 'CardScanner.java')))


# ---- p162: 延迟白卡 150ms 补扫(四项统一时机修复) ----------------------
RC162 = rd(os.path.join(SRC, 'RenderController.java'))
OC162 = rd(os.path.join(SRC, 'OemCardSuppressor.java'))
ck('S1 late-white page gate with 4 exact activity names',
   'AppAndNotificationDashboardActivity'.join(['"','"']) in RC162
   and '"DetailOfSoftConsumptionActivity".equals(actSimple)' in RC162
   and '"PrivacySecDashboardActivity".equals(actSimple)' in RC162
   and '"WalletActivity".equals(actSimple)' in RC162)
ck('S2 150ms throttle with 1500ms bound',
   'lastLateWhiteWalkMs' in RC162
   and 'frameNow - lastLateWhiteWalkMs >= 150L' in RC162
   and 'sinceAttach < lateWindow' in RC162
   and 'walletPage ? 3000L : 1500L' in RC162)
ck('S3 suppress discovery + static invalidate on late burst',
   'if (lateBurst) early = true;' in RC162
   and 'scanner.invalidateStatic();' in RC162)
ck('S4 late walk reset on detach',
   RC162.count('lastLateWhiteWalkMs = 0L') == 1)
ck('S5 resourceName unified with verified helper',
   'getResourceEntryName(id).toLowerCase()' in OC162
   and 'suppress DIAG' in OC162)
ck('S6 p161 byId still present (no regression)',
   '"dashboard_tile".equals(vres)' in OC162)


# ---- p163: 电池主页补扫遗漏 + 钱包补扫诊断 ----------------------------
RC163 = rd(os.path.join(SRC, 'RenderController.java'))
ck('T1 battery HOME page added to late-white gate',
   '"HwPowerManagerActivity".equals(actSimple)' in RC163)
ck('T2 wallet longer 3000ms window',
   'boolean walletPage = "WalletActivity".equals(actSimple)' in RC163
   and 'walletPage ? 3000L : 1500L' in RC163)
ck('T3 LATEBLK bounded diagnostic with block count',
   'LATEBLK slot=' in RC163
   and 'lateDiagLines < 20' in RC163
   and 'scanner.blocks().size()' in RC163)
ck('T4 diagnostic reset on detach',
   RC163.count('lateDiagLines = 0;') == 1)
ck('T5 prior gates intact (no regression)',
   '"DetailOfSoftConsumptionActivity".equals(actSimple)' in RC163
   and '"PrivacySecDashboardActivity".equals(actSimple)' in RC163
   and '"AppAndNotificationDashboardActivity".equals(actSimple)' in RC163)


# ---- p164: 应用页/电池主页列表行 layout-time strip --------------------
RC164 = rd(os.path.join(SRC, 'RenderController.java'))
ck('U1 entry row strip: app Activity gate + battery slot/id gate (p165)',
   'private void stripEntryRowWhite(View v)' in RC164
   and '"AppAndNotificationDashboardActivity".equals(act)' in RC164
   and '"dashboard_tile".equals(rid)' in RC164
   and '"linearLayout_software".equals(rid)' in RC164
   and 'ConfigContract.Slot.EXT_SYSTEMMANAGER == slot' in RC164)
ck('U2 ancestor resource helper bounded',
   'private static boolean hasAncestorResource(View v, String id, int maxDepth)' in RC164
   and '"external_app_settings_list"' in RC164
   and '"recycler_view"' in RC164)
ck('U3 layout-time walk wired in onGlobalLayout (unbounded early window)',
   'probeEntryRowWalk(decor, 0, new int[]{400});' in RC164
   and 'private void probeEntryRowWalk' in RC164)
ck('U4 ENTRYSTRIP diagnostic with bg+parentBg',
   'ENTRYSTRIP slot=' in RC164 and 'parentBg=' in RC164)
ck('U5 diag budget reset on detach',
   RC164.count('entryStripLogLines = 0;') == 1)
ck('U6 prior p161-163 gates intact (no regression)',
   '"dashboard_tile".equals(vres)' in rd(os.path.join(SRC, 'OemCardSuppressor.java'))
   and '"HwPowerManagerActivity".equals(actSimple)' in RC164)


# ---- p165: 隐私/钱包 ScrollView 行玻璃 + 电池主页 Activity 门控修复 ----
SC165 = rd(os.path.join(SRC, 'CardScanner.java'))
RC165 = rd(os.path.join(SRC, 'RenderController.java'))
ck('V1 stripScrollRows generates glass rows with listClip',
   'private static void stripScrollRows(View v, Rect listClip,' in SC165
   and 'rows.add(new Row(r, v, viewHeight(v), listClip));' in SC165)
ck('V2 item_with_summary_card recognized (privacy 11 rows)',
   '"item_with_summary_card".equals(rid)' in SC165)
ck('V3 static dedup by overlapsRows when scroll rows added',
   'scrollRowsAdded && overlapsRows(rows, new RectF(r))' in SC165
   and SC165.count('boolean scrollRowsAdded = false;') == 1)
ck('V4 call site passes listClip+rows',
   'stripScrollRows(g, listClip, rows, strip, 0,' in SC165)
ck('V5 battery page gate drops unreliable Activity name',
   'ConfigContract.Slot.EXT_SYSTEMMANAGER == slot' in RC165
   and '"linearLayout_software".equals(rid)' in RC165
   and ('&& "HwPowerManagerActivity".equals(act)' not in RC165[
       RC165.find('boolean battPage'):RC165.find('boolean battPage')+260]))
ck('V6 app page gate intact (was hitting, no regression)',
   '"AppAndNotificationDashboardActivity".equals(act)' in RC165
   and '"dashboard_tile".equals(rid)' in RC165)


# ---- p166: 分组滞回消除滑动摇晃 ---------------------------------------
SC166 = rd(os.path.join(SRC, 'CardScanner.java'))
ck('W1 hysteresis band constant present',
   'GROUP_HYST_PX = 18f' in SC166 and 'GROUP_GAP_PX = 30f' in SC166)
ck('W2 per-row split state map keyed by View (weak)',
   'WeakHashMap<View, Boolean> prevSplitRows' in SC166)
ck('W3 band logic: beyond->decide, inside->reuse last, family->always split',
   'gap > GROUP_GAP_PX + GROUP_HYST_PX' in SC166
   and 'gap < GROUP_GAP_PX - GROUP_HYST_PX' in SC166
   and 'split = lastSplit' in SC166
   and 'if (familyBoundary)' in SC166)
ck('W4 frame-to-frame state write-back',
   'prevSplitRows.clear();' in SC166 and 'prevSplitRows.putAll(nextSplit);' in SC166)
ck('W5 old duplicate split predicate removed (single decision)',
   SC166.count('|| row.trueTop - prev.trueBottom > GROUP_GAP_PX') == 0)
ck('W6 three frozen render files untouched (L1 visual core)',
   all('GROUP_HYST' not in rd(os.path.join(SRC,f)) for f in
       ('MaterialBridge.java','LiquidGlassView.java','LiquidShader.java')))


# ---- p167: 槽位 rank 回退匹配消除 reappears ---------------------------
RC167 = rd(os.path.join(SRC, 'RenderController.java'))
ck('X1 slotRank field present',
   'private final int[] slotRank = new int[MAX_POOL_SIZE];' in RC167)
ck('X2 rank-first matching present (p169 gate = lastRectCount)',
   'boolean useRank = (nr == lastRectCount);' in RC167
   and 'if (useRank) {' in RC167)
ck('X3 rank-first matching with distance fallback',
   'slotRank[s] == i' in RC167
   and (lambda t: t.find('if (useRank) {') < t.find('if (bestS < 0) {'))(RC167))
ck('X4 rank recorded each frame at slot assignment',
   'slotRank[s] = ri;' in RC167)
ck('X5 prior p166 hysteresis intact (no regression)',
   'GROUP_HYST_PX = 18f' in rd(os.path.join(SRC, 'CardScanner.java')))
ck('X6 three frozen render files untouched',
   all('slotRank' not in rd(os.path.join(SRC,f)) for f in
       ('MaterialBridge.java','LiquidGlassView.java','LiquidShader.java')))


# ---- p168: REAPPEAR 诊断 ---------------------------------------------
RC168 = rd(os.path.join(SRC, 'RenderController.java'))
ck('Y1 REAPPEAR diagnostic with slot/rect/last geometry',
   'REAPPEAR slot=' in RC168 and 'lastTop=' in RC168 and 'lastRank=' in RC168)
ck('Y2 bounded + detach reset',
   'reappearDiagLines < 12' in RC168
   and RC168.count('reappearDiagLines = 0;') == 1)
ck('Y3 p167 rank-first matching intact (candidate fix kept)',
   'if (useRank) {' in RC168 and 'slotRank[s] = ri;' in RC168)


# ---- p169: rank 门控改用 lastRectCount --------------------------------
RC169 = rd(os.path.join(SRC, 'RenderController.java'))
ck('Z1 lastRectCount field + detach reset + frame update',
   'private int lastRectCount = -1;' in RC169
   and RC169.count('lastRectCount = -1;') == 2
   and 'lastRectCount = nr;' in RC169)
ck('Z2 rank gate uses lastRectCount not liveSlots',
   'boolean useRank = (nr == lastRectCount);' in RC169
   and 'nr == liveSlots' not in RC169)
ck('Z3 rank-first + diagnostic intact (no regression)',
   'if (useRank) {' in RC169 and 'slotRank[s] == i' in RC169
   and 'REAPPEAR slot=' in RC169)


# ---- p170: 块保留高度门滞回 8f→0f -------------------------------------
SC170 = rd(os.path.join(SRC, 'CardScanner.java'))
ck('AA1 height gate lowered to 0 with width gate kept',
   'r.width() > 8f && r.height() > 0f' in SC170)
ck('AA2 offscreen generic guard intact (p38 no regression)',
   'allowGeneric && haveDecorRect' in SC170
   and 'r.bottom <= vt || r.top >= vb' in SC170)
ck('AA3 EdgeFade fade still applied (no small glass strip)',
   'fades.add(EdgeFade.alpha(visH, b.fullH, fadePx));' in SC170)
ck('AA4 prior rank/hysteresis intact',
   'GROUP_HYST_PX = 18f' in SC170
   and 'slotRank' in rd(os.path.join(SRC, 'RenderController.java')))


# ---- p171: 后台设置界面整理(隐藏无效项+状态卡+排版) ------------------
SA171 = rd(os.path.join(SRC, 'SettingsActivity.java'))
ck('AB1 elementCard hidden from rebuild (framework kept)',
   'body.addView(elementCard());' not in SA171
   and 'private View elementCard()' in SA171)
ck('AB2 read-only status card added with real consumed keys',
   'body.addView(statusCard());' in SA171
   and 'private View statusCard()' in SA171
   and 'storedBool(ConfigContract.Param.GLASS_ENABLED)' in SA171
   and 'bgKindLabel()' in SA171)
ck('AB3 iOS disclosure indicator on more header',
   '更多设置      ›' in SA171 and '更多设置      ‹' in SA171)
ck('AB4 footer wording aligns toggle vs locked texture',
   '液态玻璃为总开关；高级参数全局同步' in SA171)
ck('AB5 render chain / config untouched (UI-only round)',
   'r.height() > 0f' in rd(os.path.join(SRC, 'CardScanner.java'))
   and 'GROUP_HYST_PX = 18f' in rd(os.path.join(SRC, 'CardScanner.java')))


# ---- p173: 8 项玻璃参数对所有页面统一(撤销 L1 硬门) -----------------------
CC173 = rd(os.path.join(SRC, 'ConfigContract.java'))
CS173 = rd(os.path.join(SRC, 'ConfigStore.java'))
SA173 = rd(os.path.join(SRC, 'SettingsActivity.java'))
TUNE = ('GEO_RADIUS_DP','GEO_H_INSET_DP','GEO_V_INSET_DP',
        'GLASS_TRANSPARENCY','GLASS_BLUR_RADIUS','GLASS_REFRACTION',
        'GLASS_DISPERSION','GLASS_THICKNESS')
ck('AC1 isUserTunableGlass lists exactly 8 keys',
   'public static boolean isUserTunableGlass(Param p)' in CC173
   and all(('Param.'+k) in CC173[CC173.find('isUserTunableGlass'):
           CC173.find('isUserTunableGlass')+700] for k in TUNE))
ck('AC2 p172 L1 hard-gate removed (8 keys unified on ALL slots)',
   'slot == Slot.L1 && isUserTunableGlass(p)' not in CC173
   and 'boolean glassUnified = isUserTunableGlass(p);' in CC173
   and '|| glassUnified || rawBool(src, inheritKey(slot, p), true)' in CC173)
ck('AC3 8 keys removed from FROZEN_GLASS',
   (lambda seg: all(('FROZEN_GLASS' not in seg) or True for _ in [0]) and
       all(k not in seg for k in TUNE))(
           CS173[CS173.find('FROZEN_GLASS'):CS173.find('restoreGlassDefaults')]))
ck('AC4 settings UI advanced section under glass toggle',
   'advancedGlassSection()' in SA173
   and 'tunableSlider(' in SA173
   and 'writeLive(p, "-1")' in SA173)
ck('AC5 8 tunable keys have concrete parameter definitions',
   all(('Param.'+k in CC173) for k in TUNE)
   and 'geo.radiusDp' in CC173 and 'glass.transparency' in CC173
   and 'glass.refraction' in CC173 and 'glass.blurRadius' in CC173)
ck('AC6 three frozen render files untouched',
   all('isUserTunableGlass' not in rd(os.path.join(SRC,f)) for f in
       ('MaterialBridge.java','LiquidGlassView.java','LiquidShader.java')))
ck('AC7 UI footer text reflects global sync of advanced params',
   '液态玻璃为总开关；高级参数全局同步' in SA173)
ck('AC8 no stale L1-only wording in glass config comment',
   '这些改动只影响 L2/L3/外进程' not in SA173
   and 'L1 永远读 p30 默认值' not in CC173)

print('\n%d checks, %d failures' % (CHECKS[0], len(FAILS)))
for f in FAILS:
    print('  FAILED: ' + f)
sys.exit(1 if FAILS else 0)
