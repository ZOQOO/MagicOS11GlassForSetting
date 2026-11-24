#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""assert_p79.py -- deterministic (no device) invariants for 0.5.0-p79.

User (verbatim): "没有解决成功，第一次打开设置还是白色然后跳变成选择的背景，
有没有可能把这个时间消除掉直接显示选择的背景，还是这个问题在账号中心L2级
菜单最上面有矩形白卡删掉它，但是不要删除上面那个渐隐白色效果，其他先不要动，
有问题先找前面的资料，经验。"

Scope: EXACTLY two items. Everything else must stay byte-identical to p78.
Device offline (adb -s A4UF6R6116003189 -> device not found), so no visual claim.

Prior evidence used (not guesses):
  §16.4  offline DEX audit of com.android.systemui.apk found THREE
         starting-window entry points (ShellTaskOrganizer.addStartingWindow
         [earliest], StartingWindowController.addStartingWindow [dispatch],
         HnSplashscreenWindowCreator.addSplashScreenStartingWindow).
  §18.6  ON-DEVICE proof: with the skip hook firing, frame 0 showed real
         content + glass instead of the 35 KB pure-white frame.
  §53.3  "主路径 SkipHooker 命中时不会走到，但一旦没命中就是白" — a missed
         skip is exactly the white splash; the colour hooker is only fallback.
  §41.2  white splash is the ROM theme windowBackground drawn BEFORE our hook.
  p64 §4 keepBackground() keeps every "blur"/"appbar" class.
  p64 §24.3  "Soft words (search/header/blur/switch) do NOT reject a
         card-named View, so HnBlurCardXxx is still detected" — a blur-named
         View CAN be the opaque white card. p77 made "blur" an unconditional
         keep word and therefore preserved the account card.
  modules_0.3.6_p1.user.log lines 5110-5125: 账号中心 real-device
         "slot=ext.id generic=true", first frame
         "backgrounds cleared: rows=0 tracked=0", "scan: rects=0".
  p55    a view touching both screen edges is OEM (our glass insets 14dp).

Checks: A version, B first-frame, C account white card, D L1 freeze /
untouched files, E criteria + dead ends, F no new frame-path work.
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
PROP = os.path.join(ROOT, 'app', 'src', 'main', 'resources',
                    'META-INF', 'xposed', 'module.prop')

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
    t = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
    return re.sub(r'//[^\n]*', '', t)


def f(name):
    return read(os.path.join(SRC, name))


def arc(name):
    return read(os.path.join(ARC, name[:-5] + '-p78-current.java'))


GE = f('GlassEntry.java')
CB = f('ConfigBus.java')
RC = f('RenderController.java')
BD = f('BackdropLayer.java')
MP = f('ModuleProvider.java')
PROP_TXT = read(PROP)
ALL_CODE = code_only(''.join(f(n) for n in sorted(os.listdir(SRC))
                             if n.endswith('.java')))

add_start = method_body(GE, 'private boolean hookAddStartingWindow(')
hook_skips = method_body(GE, 'private int hookSkips(')
install = method_body(GE, 'private void installStartingWindowHooks(')
warm = method_body(CB, 'public static void warmWindowColor(')
win_color = method_body(CB, 'public static int windowColor()')
fade = method_body(RC, 'private static boolean isFadeWhiteLayer(')
strip_child = method_body(RC, 'private void stripExtWhiteChild(')
strip_children = method_body(RC, 'private void stripExtCardChildren(')
strip_rows = method_body(RC, 'private void stripRowsIn(')
full_width = method_body(RC, 'private boolean isFullWidthOem(')
gate = method_body(RC, 'private boolean accountIdGate()')
pre = method_body(RC, 'private boolean onPreDraw()')

# ---- A. version truth -----------------------------------------------------
ck('A01 GlassEntry.BUILD == 0.5.0-p79',
   'public static final String BUILD = "0.5.0-p79";' in GE)
ck('A02 module.prop version == 0.5.0-p79', 'version=0.5.0-p79' in PROP_TXT)
ck('A03 module.prop versionCode == 107', 'versionCode=107' in PROP_TXT)
ck('A04 p78 marker gone from module.prop', '0.5.0-p78' not in PROP_TXT)
ck('A05 BUILD and module.prop agree',
   '0.5.0-p79' in GE and '0.5.0-p79' in PROP_TXT)

# ---- B. first frame: skip coverage + reliable fallback colour ------------
ck('B01 hookAddStartingWindow body non-empty', bool(add_start))
ck('B02 hookSkips helper is non-empty', bool(hook_skips))
ck('B03 ShellTaskOrganizer earliest entry is hooked',
   'com.android.wm.shell.ShellTaskOrganizer' in add_start)
ck('B04 StartingWindowController dispatch entry is hooked',
   'StartingWindowController' in add_start)
ck('B05 HnSplashscreenWindowCreator splash entry is hooked',
   'HnSplashscreenWindowCreator' in add_start
   and 'addSplashScreenStartingWindow' in add_start)
ck('B06 every overload is hooked (not just the first match)',
   'getDeclaredMethods()' in hook_skips
   and 'for (Method m :' in hook_skips
   and 'findMethod(c, "addStartingWindow", 1)' not in add_start)
ck('B07 package-name guard is unchanged',
   'new StartingWindowSkipHooker()' in hook_skips
   and 'Scope.isStartingWindowTarget(pkg)' in GE)
ck('B08 default-visible skip-hook count',
   'mg-state startingWindow skipHooks=' in add_start)
ck('B09 SystemUI primes the colour through the provider',
   'ConfigBus.warmWindowColor((Context) app)' in install)
ck('B10 warmWindowColor reads the provider, not remote prefs',
   'providerMap(ctx)' in warm and 'sRemote' not in warm)
ck('B11 warmWindowColor is one daemon low-priority thread',
   'Thread.MIN_PRIORITY' in warm and 'setDaemon(true)' in warm
   and 'if (ctx == null || sWindowColorFromProvider) return;' in warm)
ck('B12 windowColor() prefers the provider-derived colour',
   'if (sWindowColorFromProvider) return sWindowColor;' in win_color)
ck('B13 p66 remote-prefs fallback is preserved',
   'SharedPreferences sp = sRemote;' in win_color
   and 'ConfigContract.WINDOW_BASE_COLOR' in win_color)
ck('B14 default-visible colour-source line',
   'mg-state windowColourSource=provider win=' in warm)
ck('B15 colour hook and skipValue are preserved',
   'hookBackgroundColor(loader)' in install
   and 'private static Object skipValue(' in GE
   and 'private static Method findMethod(' in GE)
ck('B16 no SystemUI file writes / resources edits',
   'XResources' not in ALL_CODE and 'setWindowBackground' not in ALL_CODE)

# ---- C. 账号中心 white card (keep the fading white effect) ---------------
ck('C01 isFadeWhiteLayer body non-empty', bool(fade))
ck('C02 MEASURED opaque fill is NEVER kept as a fade layer',
   'if (isOpaqueSolidFill(bg) || isOpaqueSolidFill(fg)) return false;' in fade)
ck('C03 "blur" is no longer an unconditional keep word',
   'n.contains("blur")' not in code_only(fade))
ck('C04 AppbarBlurBg keeps its name-based protection',
   'n.contains("appbarblurbg")' in fade)
ck('C05 translucent fade signature is preserved',
   'bg.getOpacity() != -1' in fade and 'fg.getOpacity() != -1' in fade)
ck('C06 account strip no longer uses the class-name whitelist',
   'if (account) {' in strip_child
   and 'if (isFadeWhiteLayer(v)) return;' in strip_child
   and '} else if (keepBackground(v)) return;' in strip_child)
ck('C07 all NON-account slots keep the identical old path',
   '} else if (keepBackground(v)) return;' in strip_child
   and 'isOpaqueSolidFill(bg)' in strip_child)
ck('C08 p77 caller-gate omission is fixed',
   '(!extWhiteGate() && !accountIdGate())' in strip_children)
ck('C09 CHROME branch no longer depends on the chrome class name alone',
   'isChromeClass(cls) || accountTopCard' in strip_rows)
ck('C10 accountTopCard uses p55 structure + measured fill',
   'accountChrome && isFullWidthOem(v)' in strip_rows
   and 'isOpaqueSolidFill(v.getBackground())' in strip_rows)
ck('C11 isFullWidthOem is ratio-based (no absolute screen-Y threshold)',
   bool(full_width) and 'dw * 0.94f' in full_width
   and 'getLocationOnScreen' not in full_width)
ck('C12 default-visible proof of a real strip',
   'mg-state ACCOUNTSTRIP cls=' in RC)
ck('C13 ACCOUNTSTRIP is throttled, never per frame',
   'now - lastAccountStripLogMs > 2000L' in RC
   and 'lastAccountStripLogMs = now;' in RC)
ck('C14 p78 fade-keep diagnostic is preserved',
   'mg-state ACCOUNTFADE keep=' in RC)
ck('C15 the fading layer is still protected inside CHROME',
   '!accountChrome || !isFadeWhiteLayer(v)' in strip_rows)
ck('C16 account gate is still exactly EXT_ID + generic (L1 no-op)',
   'return genericEnabled && slot == ConfigContract.Slot.EXT_ID;' in gate)
ck('C17 HnTransparentPattern is not kept by a "transparent" word',
   'n.contains("transparent")' not in code_only(fade))
ck('C18 verbose p64/p77 criteria survive',
   'ACCOUNTCHROME ' in RC and 'ACCOUNTWHITE ' in RC
   and 'BGUNIFY slot=' in RC)

# ---- D. L1 freeze / "其他先不要动" ---------------------------------------
for name in ('MaterialBridge.java', 'LiquidGlassView.java', 'LiquidShader.java'):
    ck('D01 %s == p30 baseline (bytes)' % name,
       open(os.path.join(SRC, name), 'rb').read()
       == open(os.path.join(BASE, name), 'rb').read())
ck('D02 BackdropLayer.java whole file == p78', BD == arc('BackdropLayer.java'))
for name, sig in [('drawRegion()', 'public boolean drawRegion(Canvas c,'),
                  ('snapshot()', 'private Bitmap snapshot()'),
                  ('recycle()', 'private void recycle()'),
                  ('drawLiveVideoRegion()', 'private boolean drawLiveVideoRegion('),
                  ('WindowBackdropDrawable.draw', 'public void draw(Canvas c)')]:
    a, b = method_body(BD, sig), method_body(arc('BackdropLayer.java'), sig)
    ck('D03 %s == p78 and non-vacuous' % name, bool(a and b) and a == b)
for name in ('ConfigContract.java', 'ConfigStore.java', 'SettingsActivity.java',
             'ScaleMode.java', 'EdgeFade.java', 'ModuleProvider.java',
             'CardScanner.java', 'OemCardSuppressor.java', 'PageLevel.java',
             'Scope.java'):
    ck('D04 %s == p78' % name,
       os.path.isfile(os.path.join(ARC, name[:-5] + '-p78-current.java'))
       and f(name) == arc(name))
diff = []
for n in sorted(os.listdir(SRC)):
    if n.endswith('.java'):
        p = os.path.join(ARC, n[:-5] + '-p78-current.java')
        if not os.path.isfile(p) or f(n) != read(p):
            diff.append(n)
ck('D05 exactly 3 sources differ from p78',
   diff == ['ConfigBus.java', 'GlassEntry.java', 'RenderController.java'])
ck('D06 all 18 p78 snapshots exist',
   len([n for n in os.listdir(ARC) if n.endswith('-p78-current.java')]) == 18)
ck('D07 p78 lazy colour derivation is NOT rolled back',
   'ConfigStore.ensureWindowColor(getContext())' in MP)

# ---- E. criteria / dead ends ---------------------------------------------
ck('E01 CPU video-capture dead end stays absent',
   'captureVideoFrame' not in ALL_CODE
   and 'VIDEO_CAPTURE_INTERVAL_MS' not in ALL_CODE)
ck('E02 p74/p75 one-shot transition frame survives',
   'TRANSITION snap=once' in BD and 'video.getTransform(null)' in BD)
ck('E03 p73 GPU live layer survives',
   'drawLiveVideoRegion' in BD and 'drawRenderNode' in BD)
ck('E04 mg-build startup signature survives',
   'say("mg-build " + BUILD + " loaded in " + pkg);' in GE)
ck('E05 mg-state default-visible channel survives',
   'msg.startsWith("mg-state ")' in GE)
ck('E06 video hard gate survives', 'if (videoMode && !released)' in BD)

# ---- F. no new frame-path work -------------------------------------------
ck('F01 no mg-state line inside onPreDraw()', 'mg-state ' not in pre)
ck('F02 BackdropLayer has no new state lines',
   all('mg-state ' not in method_body(BD, s)
       for s in ('public boolean drawRegion(Canvas c,',
                 'private Bitmap snapshot()',
                 'private void ensurePlateSnapshot()')))
ck('F03 windowColor() is a volatile read on the draw path',
   'sWindowColorFromProvider' in win_color
   and 'providerMap(' not in win_color)
ck('F04 warmWindowColor runs at most once',
   'if (ctx == null || sWindowColorFromProvider) return;' in warm)
ck('F05 no timer/post(Runnable) introduced',
   'postDelayed(' not in code_only(CB))

print('\n%d checks, %d failures' % (CHECKS[0], len(FAILS)))
if FAILS:
    print('FAILED:')
    for x in FAILS:
        print('  - ' + x)
    sys.exit(1)
print('ALL_GREEN')
sys.exit(0)
