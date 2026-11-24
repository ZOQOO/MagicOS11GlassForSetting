#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""assert_p194.py -- deterministic (no device) invariants for 0.5.0-p194.

p194 REBUILDS the L1 row-icon replacement feature from scratch after the
p178~p185 attempts were proven wrong. Evidence (findings §186):

  * L1 main page is rendered by DashboardSummary + #dashboard_tile rows,
    NOT by androidx.preference.Preference. The old hook on
    Preference.onBindViewHolder therefore never fired on L1.
  * Decompiled g$g (ViewHolder) binds with the standard ids
       0x01020006 android.R.id.icon  -> ImageView
       0x01020016 android.R.id.title -> TextView
  * The icon is assigned in dashboard/g.S(g$g, drawer.m) before the title is
    set in the same method, so hooking TextView.setText(CharSequence) is the
    correct, obfuscation-independent landing point.

Green here proves PACKAGING + CODE SHAPE ONLY, not a device/visual claim.
"""

import io
import os
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
MANIFEST = os.path.join(ROOT, 'app', 'src', 'main', 'AndroidManifest.xml')
APK = os.path.join(ROOT, 'build-out', 'MagicGlass-0.5.0-p194.apk')

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
RC = rd(os.path.join(SRC, 'RenderController.java'))
CB = rd(os.path.join(SRC, 'ConfigBus.java'))
ER = rd(os.path.join(SRC, 'ElementRegistry.java'))
PROP_TXT = rd(PROP)
MF = rd(MANIFEST)
IS_PATH = os.path.join(SRC, 'IconSwap.java')
IS_TXT = rd(IS_PATH) if os.path.isfile(IS_PATH) else ''
L1_PATH = os.path.join(SRC, 'L1IconRows.java')
L1_TXT = rd(L1_PATH) if os.path.isfile(L1_PATH) else ''
IC_PATH = os.path.join(SRC, 'IconSwapCache.java')
IC_TXT = rd(IC_PATH) if os.path.isfile(IC_PATH) else ''

# ---- version / identity ---------------------------------------------------
ck('V01 build fingerprint is p194', 'BUILD = "0.5.0-p194"' in GE)
ck('V02 module.prop version is 0.5.0-p194', 'version=0.5.0-p194' in PROP_TXT)
ck('V03 module.prop versionCode is 221', 'versionCode=221' in PROP_TXT)
ck('V04 module renamed to MagicOS11GlassForSetting',
   'name=MagicOS11GlassForSetting' in PROP_TXT)
ck('V05 scope list present', os.path.isfile(SCOPE))

# ---- no restart primitives (project red line) -----------------------------
ck('R01 no soft/hard reboot primitives in module source',
   not any(s in GE + SA + CC + RC for s in
           ('Runtime.getRuntime().exec', 'setprop ctl.restart', 'killall ')))

# ---- frozen render file: LiquidShader byte-identical to p30 -----------------
ck('L01 LiquidShader byte-identical to p30 (never touched)',
   os.path.isfile(os.path.join(BASE, 'LiquidShader.java'))
   and rb(os.path.join(BASE, 'LiquidShader.java'))
       == rb(os.path.join(SRC, 'LiquidShader.java')))

# ---- p194 core: rebuilt icon swap ------------------------------------------
ck('AP01 IconSwap class exists', os.path.isfile(IS_PATH)
   and 'public final class IconSwap' in IS_TXT)
ck('AP02 standard android ids (icon 0x01020006 / title 0x01020016)',
   'ID_ICON = 0x01020006' in IS_TXT and 'ID_TITLE = 0x01020016' in IS_TXT)
ck('AP03 hooks TextView.setText(CharSequence)',
   'hook(target).intercept(new SetTextHooker())' in IS_TXT
   and '"setText".equals(m.getName())' in IS_TXT
   and 'CharSequence.class.equals' in IS_TXT)
ck('AP04 L1 gate + row-title match before replacing',
   'GlassEntry.isL1Now()' in IS_TXT and 'L1IconRows.contains(rowTitle)' in IS_TXT)
ck('AP05 finds icon by walking title -> row (bounded)',
   'findIconInRow' in IS_TXT and 'depth > 4' in IS_TXT
   and 'visited[0] >= 32' in IS_TXT)
ck('AP06 replaces drawable from bounded cache',
   'IconSwapCache.peek(media)' in IS_TXT
   and 'icon.setImageDrawable(new BitmapDrawable' in IS_TXT)
ck('AP07 restores original drawable when rule removed',
   'ORIGINALS.remove(icon)' in IS_TXT and 'icon.setImageDrawable(orig)' in IS_TXT)
ck('AP08 hook installed in settings host process',
   'IconSwap.install(a.getClass().getClassLoader(), sModule)' in GE)
ck('AP09 instant effect wired into config refresh',
   'IconSwap.refresh(decor)' in RC)
ck('AP10 bounded instant-refresh walk (depth/nodes)',
   'depth > 12' in IS_TXT and 'visited[0] >= 800' in IS_TXT)

# ---- old (wrong) implementation fully removed ------------------------------
ck('AQ01 old PREFBIND icon path gone',
   'private static void applyIconSwap' not in GE
   and 'public static void reapplyIconsOnScreen' not in GE
   and 'private static void findIconView' not in GE)
ck('AQ02 old grab broadcast gone',
   'ACTION_ICON_ROWS_REQUEST' not in CB and 'EXTRA_ICON_KEYS' not in CB)
ck('AQ03 old keyDirectory gone', 'keyDirectory' not in ER)
ck('AQ04 old UI grab/receiver gone',
   'requestIconRows' not in SA and 'registerIconRowsReceiver' not in SA
   and 'renderIconRows' not in SA and 'renderGrabStatus' not in SA)
ck('AQ05 no dangling live refs to removed APIs',
   all(s not in (GE + SA + RC + CB + ER) for s in
       ('GlassEntry.reapplyIconsOnScreen(',
        'ConfigBus.ACTION_ICON_ROWS_REQUEST',
        'ConfigBus.EXTRA_ICON_TITLES',
        'ElementRegistry.keyDirectory(')))

# ---- config + media plumbing kept (these were correct) ---------------------
ck('AR01 ICON_RULES config key kept', 'ICON_RULES("icon.rules", "")' in CC)
ck('AR02 bounded icon bitmap cache kept',
   'MAX_ENTRIES' in IC_TXT and 'MAX_EDGE_DP' in IC_TXT
   and 'inSampleSize' in IC_TXT)
ck('AR03 media import + provider path kept',
   'importMedia' in rd(os.path.join(SRC, 'ConfigStore.java'))
   and 'public static Uri media(String name)' in CB)
ck('AR04 UI writes rule then broadcasts',
   'private void writeIconRule(' in SA and 'ConfigStore.write(' in SA)

# ---- L1 row data -----------------------------------------------------------
ck('AS01 L1IconRows holds the 23 device-captured titles',
   'TITLES' in L1_TXT
   and all(t in L1_TXT for t in ('WLAN', '蓝牙', '移动网络', '荣耀互联',
                                 '荣耀 AI & YOYO', '声音和触感',
                                 '通知和状态栏', '隐私和安全')))
ck('AS02 title rule gid is pipe-free',
   'RULE_KEY = "title:"' in L1_TXT and 'ruleGid' in L1_TXT)
ck('AS03 captured data file archived',
   os.path.isfile(os.path.join(ROOT, 'probe', 'probe-report',
                               'l1_rows', 'l1_titles_2026-11-23.txt')))

# ---- UI --------------------------------------------------------------------
ck('AT01 icon card offers the 23-row list',
   'L1IconRows.TITLES' in SA and 'private void renderKeyDirectory()' in SA
   and 'iconKey = L1IconRows.ruleGid(title)' in SA)
ck('AT02 row list uses expand/collapse in outer scroll (no nested ScrollView)',
   'iconListExpanded' in SA
   and 'ScrollView listScroll = new ScrollView(this)' not in SA
   and '展开全部' in SA)
ck('AT03 picker wired to REQ_ICON_SWAP',
   'REQ_ICON_SWAP' in SA and 'pickIconSwap' in SA)

# ---- earlier features still intact ----------------------------------------
ck('AU01 manifest has both new labels',
   MF.count('android:label="MagicOS11GlassForSetting"') == 2)
ck('AU02 launcher icon declared + packaged',
   'android:icon="@mipmap/ic_launcher"' in MF
   and os.path.isfile(os.path.join(ROOT, 'app', 'src', 'main', 'res',
                                   'mipmap-xxxhdpi', 'ic_launcher.png')))
ck('AU03 glass fill params present (p175)',
   all(k in CC for k in ('GLASS_FILL_COLOR', 'GLASS_FILL_ALPHA',
                         'GLASS_FILL_IMAGE', 'GLASS_FILL_IMAGE_FIT')))
ck('AU04 package / provider authority unchanged',
   'com.magicglass.provider' in MF and 'author=MagicGlass' in PROP_TXT)

# ---- APK -------------------------------------------------------------------
if os.path.isfile(APK):
    ck('D01 p194 signed APK exists', True)
    try:
        with zipfile.ZipFile(APK) as z:
            names = z.namelist()
        ck('D02 APK contains classes.dex', 'classes.dex' in names)
        ck('D03 APK contains xposed module.prop',
           'META-INF/xposed/module.prop' in names)
        ck('D04 APK contains launcher icon mipmaps',
           any('res/mipmap-' in n and 'ic_launcher' in n for n in names))
        ck('D05 APK does not embed MIUI base classes',
           not any('miui' in n.lower() for n in names))
    except Exception as e:
        ck('D02 APK readable (%s)' % e, False)
else:
    for n in ('D01 p194 signed APK exists', 'D02 APK classes.dex',
              'D03 APK module.prop', 'D04 APK launcher icons',
              'D05 APK no MIUI base'):
        ck(n, False)

# ---- archive snapshot ------------------------------------------------------
ck('E01 p194 APK archived',
   os.path.isfile(os.path.join(ARC, 'MagicGlass-0.5.0-p194.apk')))
ck('E02 p194 source snapshots archived',
   os.path.isfile(os.path.join(ARC, 'IconSwap-p194-current.java'))
   and os.path.isfile(os.path.join(ARC, 'GlassEntry-p194-current.java')))

# ---- p194: 图片压缩 + 居中方形裁切 + 圆形图标化 --------------------------
ck('AV01 decode downsamples to a hard pixel cap',
   'inSampleSize' in IC_TXT and 'MAX_EDGE_DP' in IC_TXT
   and 'createScaledBitmap' in IC_TXT)
ck('AV02 center-square + circular mask applied',
   'toRoundIcon' in IC_TXT
   and 'BitmapShader' in IC_TXT and 'Shader.TileMode.CLAMP' in IC_TXT
   and 'canvas.drawCircle(r, r, r, paint)' in IC_TXT)
ck('AV03 circular output is ARGB_8888 (transparent corners)',
   'Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)' in IC_TXT)
ck('AV04 center crop translates source by half the excess',
   'setTranslate(-(w - size) / 2f, -(h - size) / 2f)' in IC_TXT)
ck('AV05 round icon applied in load() path before caching',
   IC_TXT.find('toRoundIcon(decoded)') > 0
   and IC_TXT.find('toRoundIcon(decoded)') < IC_TXT.find('CACHE.put(name, decoded)'))
ck('AV06 failure-safe: toRoundIcon returns original on error',
   'return src;' in IC_TXT)

# ---- p194: 交互裁剪（拖动/缩放 → 烘焙圆形 PNG） -------------------------
CV_PATH = os.path.join(SRC, 'CropView.java')
CV = rd(CV_PATH) if os.path.isfile(CV_PATH) else ''
CA_PATH = os.path.join(SRC, 'IconCropActivity.java')
CA = rd(CA_PATH) if os.path.isfile(CA_PATH) else ''
MFTXT = rd(os.path.join(ROOT, 'app', 'src', 'main', 'AndroidManifest.xml'))
CS = rd(os.path.join(SRC, 'ConfigStore.java'))
ck('AW01 CropView supports drag + pinch zoom',
   'onTouchEvent' in CV and 'ScaleGestureDetector' in CV
   and 'ACTION_MOVE' in CV)
ck('AW02 crop clamps so the circle never shows empty edges',
   'private void clamp()' in CV and 'minTx' in CV and 'maxTy' in CV)
ck('AW03 export bakes a circular ARGB bitmap of requested size',
   'public Bitmap exportRound(int size)' in CV
   and 'Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)' in CV
   and 'clipPath' in CV)
ck('AW04 crop activity hosts view + zoom slider + confirm',
   'class IconCropActivity' in CA and 'cropView.setZoomFactor' in CA
   and 'exportRound(outSizePx)' in CA)
ck('AW05 cropped bitmap persisted as PNG via ConfigStore',
   'importBitmap' in CS and 'CompressFormat.PNG' in CS
   and 'ConfigStore.importBitmap(this, out, "icon")' in CA)
ck('AW06 crop activity registered in manifest (not exported)',
   '.IconCropActivity' in MFTXT and 'android:exported="false"' in MFTXT)
ck('AW07 settings launches crop before writing the rule',
   'REQ_ICON_CROP' in SA and 'IconCropActivity.class' in SA
   and SA.find('REQ_ICON_CROP') < SA.find('writeIconRule(iconKey, iconName)'))

# ---- p194: B1 分组结构（标题栏 + 状态/外观/关于，外观带 38px 缩略） --------
ck('AX01 header bar with title, author and status dot',
   'private View headerBar()' in SA and 'ZOOQOO · 基于 LSPosed' in SA
   and 'MagicGlass' in SA)
ck('AX02 three groups: 模块状态 / 外观 / 关于',
   'groupLabel("模块状态")' in SA and 'groupLabel("外观")' in SA
   and 'groupLabel("关于")' in SA)
ck('AX03 appearance rows carry 38px thumbnails',
   'private View thumbBg()' in SA and 'private View thumbGlass()' in SA
   and 'private View thumbIcons()' in SA
   and 'new LinearLayout.LayoutParams(dp(38), dp(38))' in SA)
ck('AX04 rows expand in place, keeping original controls',
   'expandedSection' in SA and 'expandedSection == section ? -1 : section' in SA
   and 'body.addView(backgroundCard())' in SA
   and 'body.addView(glassAdvancedCard())' in SA
   and 'body.addView(iconSwapCard())' in SA)
ck('AX05 status group shows running state and scope',
   'private View statusGroup()' in SA and '生效范围' in SA)
ck('AX06 about group shows author and build',
   'private View aboutGroup()' in SA and 'GlassEntry.BUILD' in SA)
ck('AX07 tab row no longer used in rebuild',
   'body.addView(tabRow())' not in SA)

# ---- p194: 三个总开关上移到外观分组顶部 -------------------------------
ck('AY01 master switches relocated to tiles (p194)',
   'switchTile("液态玻璃效果"' in SA
   and 'switchTile("自定义背景"' in SA
   and 'switchRow("诊断日志"' in SA)
ck('AY02 switches placed above the 背景 row',
   SA.find('switchRowInset("诊断日志"') < SA.find('appearanceRow(0, "背景"'))
ck('AY03 display card no longer owns glass switch (p194)',
   'switchRow("液态玻璃效果"' not in SA)
ck('AY04 background card no longer owns its switch',
   'switchRow("自定义背景"' not in SA)
ck('AY05 switches still bound to the same params',
   'ConfigContract.Param.GLASS_ENABLED' in SA
   and 'ConfigContract.Param.BG_ENABLED' in SA
   and 'ConfigContract.Param.PERF_DEBUG_LOG' in SA)

# ---- p194: 方案1 磁贴工作台 + 六套主题切换 -----------------------------
ck('AZ01 six theme palettes defined',
   'THEME_NAMES' in SA and 'public static final int' not in SA
   and SA.count('0xFF') > 40)
ck('AZ02 accent colors are instance fields reset by applyTheme',
   'private int C_ACCENT' in SA and 'private int C_HERO' in SA
   and 'C_ACCENT = THEME_ACCENT[themeIndex]' in SA)
ck('AZ03 theme persisted locally and applied before first view',
   'KEY_UI_THEME' in SA and 'putString(KEY_UI_THEME' in SA
   and 'getString(KEY_UI_THEME' in SA
   and SA.find('applyTheme();') < SA.find('ConfigStore.ensureDefaults(this);'))
ck('AZ04 theme chips switch and rebuild immediately',
   'private void applyThemeIndex(int idx)' in SA and 'applyTheme();' in SA
   and 'swatch.setOnClickListener' in SA)
ck('AZ05 quick switches are tiles at top',
   'private View quickSwitchTiles()' in SA
   and 'quickSwitchTiles());' in SA
   and SA.find('body.addView(quickSwitchTiles());') < SA.find('body.addView(statusGroup());'))
ck('AZ06 diagnostic log is a separate group at the bottom',
   'private View diagnosticGroup()' in SA
   and 'body.addView(diagnosticGroup());' in SA
   and SA.find('body.addView(diagnosticGroup());') > SA.find('body.addView(aboutGroup());'))
ck('AZ07 theme switch sits above diagnostic log',
   SA.find('body.addView(themeGroup());') < SA.find('body.addView(diagnosticGroup());'))
ck('AZ08 old three switches removed from appearance group',
   'switchRowInset("液态玻璃效果"' not in SA
   and 'switchRowInset("诊断日志"' not in SA
   and 'switchRowInset("自定义背景"' not in SA)
ck('AZ09 all three params still bound (no function lost)',
   'ConfigContract.Param.GLASS_ENABLED' in SA
   and 'ConfigContract.Param.BG_ENABLED' in SA
   and 'ConfigContract.Param.PERF_DEBUG_LOG' in SA)
ck('AZ10 wireframe static class uses mirrored theme fields',
   'sWireAccentSoft' in SA and 'sWireAccent' in SA
   and 'fill(sWireAccentSoft)' in SA)

# ---- p194: 删除重复的第二套标题 ----------------------------------------
ck('BA01 onCreate no longer builds the p174 duplicate header',
   'One UI 紧凑标题' not in SA
   and 'root.addView(header, wrap())' not in SA
   and 'titles.addView(text("MagicGlass", 18f' not in SA)
ck('BA02 status field removed (no dangling writes)',
   'private TextView status;' not in SA
   and 'status.setText(' not in SA)
ck('BA03 single title comes from headerBar in rebuild',
   'body.addView(headerBar());' in SA
   and SA.count('addView(headerBar())') == 1)

# ---- p194: 界面显示名对齐项目名 ----------------------------------------
ck('BB01 header title uses the project name',
   'text("MagicOS11GlassForSetting", 17f' in SA
   and 'text("MagicGlass", 21f' not in SA)
ck('BB02 footer note uses the project name',
   'text("MagicOS11GlassForSetting " + GlassEntry.BUILD' in SA
   and 'text("MagicGlass " + GlassEntry.BUILD' not in SA)

print('\n%d checks, %d failures' % (CHECKS[0], len(FAILS)))
if FAILS:
    print('  FAILED: ' + FAILS[0])
