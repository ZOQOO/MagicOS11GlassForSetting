#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""assert_p81.py -- deterministic (no device) invariants for 0.5.0-p81.

User (verbatim): "根据上面的结论做一版。" — implement the layered conclusion of
`first-frame-analysis-2026-11-15.txt` (findings §70): A = paint the SystemUI
starting window with the REAL background picture, C = close the host first
frame, B = default-visible negative log for the skip route.

Evidence used (all already on file, NO new guess):
  §70.1  t1 SystemUI draws the white starting window BEFORE the Settings
         process exists; p77-p80 all patched t2..t4 and could not reach it.
  §70.3  jadx classes3.dex: SplashscreenContentDrawer
         .makeSplashScreenContentView / .makeSimpleSplashScreenContentView
         return SplashScreenView and carry StartingWindowInfo;
         HnSplashscreenWindowCreator builds a plain FrameLayout and calls
         WindowManagerGlobal.addView(frameLayout, params, ...);
         createLayoutParameters sets params.packageName = activityInfo.packageName.
  §70.4  p80 ensureWindowPreview() cached its own failure forever and did
         synchronous IPC + decode on the UI thread in apply().
  §18.6  real-device proof that SKIPPING the starting window yields frame 0
         with real content + glass; used as the automatic fallback here.
  §69    "Offline Blind-Patch Trap": a green gate proves packaging only.

Checks: A version, B plan A hooks, C plan C prefetch, D plan B diagnostics,
E L1 freeze / change scope, F red lines + dead ends, G criteria strings.

Device is offline (adb -s A4UF6R6116003189 -> device not found): this script
makes NO claim about the loaded dex or the visible first frame.
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
REPORT_DIR = os.path.join(ROOT, 'probe', 'probe-report')
SCOPE = os.path.join(ROOT, 'app', 'src', 'main', 'resources',
                     'META-INF', 'xposed', 'scope.list')
COMPILE = os.path.join(ROOT, 'probe', 'compile_check.py')

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
    return read(os.path.join(ARC, name[:-5] + '-p80-current.java'))


GE = f('GlassEntry.java')
CB = f('ConfigBus.java')
BD = f('BackdropLayer.java')
RC = f('RenderController.java')
MP = f('ModuleProvider.java')
CS = f('ConfigStore.java')
PROP_TXT = read(PROP)
ALL_CODE = code_only(''.join(f(n) for n in sorted(os.listdir(SRC))
                             if n.endswith('.java')))

prefetch = method_body(CB, 'private static void prefetchSync(')
request = method_body(CB, 'public static Bitmap requestFirstFrame(')
warmup = method_body(CB, 'public static void warmUpAsync(')
warmcolour = method_body(CB, 'public static void warmWindowColor(')
starting_preview = method_body(CB, 'public static Bitmap startingPreview(')
ensure_preview = method_body(BD, 'private void ensureWindowPreview(')
win_draw = method_body(BD, 'public void draw(Canvas c)')
apply_body = method_body(BD, 'public void apply(ConfigContract.Slot slot,')
skip_hook = method_body(GE, 'class StartingWindowSkipHooker')
paint_hook = method_body(GE, 'class StartingWindowPaintHooker')
root_hook = method_body(GE, 'class StartingWindowRootHooker')
paint_methods = method_body(GE, 'private int hookPaintMethods(')
root_add = method_body(GE, 'private int hookRootAddView(')
paint_install = method_body(GE, 'private void installStartingWindowHooks(')
seen = method_body(GE, 'private static void seen(')

# ---- A. version truth -----------------------------------------------------
ck('A01 GlassEntry.BUILD == 0.5.0-p81',
   'public static final String BUILD = "0.5.0-p81";' in GE)
ck('A02 module.prop version == 0.5.0-p81', 'version=0.5.0-p81' in PROP_TXT)
ck('A03 module.prop versionCode == 109', 'versionCode=109' in PROP_TXT)
ck('A04 p80 marker gone from module.prop', '0.5.0-p80' not in PROP_TXT)
ck('A05 BUILD and module.prop agree',
   '0.5.0-p81' in GE and '0.5.0-p81' in PROP_TXT)
ck('A06 build fingerprint line survives',
   'say("mg-build " + BUILD' in GE)

# ---- B. plan A: SystemUI starting window gets a REAL picture --------------
ck('B01 hook targets come from the decompiled DEX, not a guessed class',
   'com.android.wm.shell.startingsurface.SplashscreenContentDrawer' in GE
   and 'com.android.wm.shell.startingsurface.HnSplashscreenContentDrawer' in GE
   and 'android.view.WindowManagerGlobal' in GE)
ck('B02 the two factory methods are the decompiled ones',
   '"makeSplashScreenContentView"' in GE
   and '"makeSimpleSplashScreenContentView"' in GE
   and 'getParameterTypes().length < 2' in paint_methods)
ck('B03 factory hooks are AFTER hooks (proceed first, paint after)',
   paint_hook.find('Object result = chain.proceed();')
   < paint_hook.find('v.setBackground('))
ck('B04 content View background is a BitmapDrawable of the real picture',
   'v.setBackground(new BitmapDrawable(v.getResources(), b));' in paint_hook
   and 'ConfigBus.startingPreview()' in paint_hook)
ck('B05 root FrameLayout hook reads LayoutParams.packageName (DEX line 73)',
   'field(chain.getArg(1), "packageName")' in root_hook
   and 'p instanceof String ? (String) p : null' in root_hook)
ck('B06 root hook is a BEFORE hook and still proceeds',
   root_hook.find('v.setBackground(') < root_hook.find('return chain.proceed();'))
ck('B07 root View is painted with the same picture',
   'v.setBackground(new BitmapDrawable(v.getResources(), b));' in root_hook)
ck('B08 package-name whitelist guard on BOTH new hooks',
   'Scope.isStartingWindowTarget(pkg)' in paint_hook
   and 'Scope.isStartingWindowTarget(pkg)' in root_hook)
ck('B09 hooks do nothing without a ready preview',
   'if (b == null) return result;' in paint_hook
   and 'if (b != null)' in root_hook)
ck('B10 LSPosed 2.2.1: all three hookers are public static final',
   all(('public static final class ' + n
        + ' implements XposedInterface.Hooker') in GE
       for n in ('StartingWindowPaintHooker', 'StartingWindowRootHooker',
                 'StartingWindowSkipHooker')))
ck('B11 every reflective hook install is wrapped and deoptimized',
   'Class.forName(' in paint_methods
   and 'getDeclaredMethods()' in paint_methods
   and 'deoptimize(m)' in paint_methods
   and 'deoptimize(m)' in root_add
   and 'catch (Throwable' in paint_methods)
ck('B12 hook count is logged and drives the A/B decision',
   'sPaintHooks = hookStartingWindowPaint(loader);' in paint_install
   and 'mg-state startingWindow paintHooks=' in paint_install
   and 'sPaintHooks > 0' in skip_hook)
ck('B13 package resolution reuses the field names proven by the DEX',
   'paintPackage(chain)' in paint_hook
   and 'startingPackage(chain.getArg(i))' in GE)
ck('B14 default-visible APPLIED evidence for content and root',
   'mg-state startingWindowPreview applied=content pkg=' in paint_hook
   and 'mg-state startingWindowPreview applied=root pkg=' in root_hook)
ck('B15 failure of a paint hook can never break systemui',
   'say("mg-state startingWindowPreview content failed: " + t)' in paint_hook
   and 'catch (Throwable ignored)' in root_hook)
ck('B16 no /system write, no device file write, no theme resource rewrite',
   'XResources' not in ALL_CODE and 'openOutputStream' not in ALL_CODE
   and '/system' not in code_only(GE))
ck('B17 the ROM decompiled evidence is archived',
   os.path.isfile(os.path.join(REPORT_DIR, 'sysui_dec',
                               'HnSplashscreenContentDrawer.java'))
   and os.path.isfile(os.path.join(REPORT_DIR, 'sysui_dec',
                                   'SplashscreenContentDrawer.java')))
ck('B18 the layered analysis this build implements is archived',
   os.path.isfile(os.path.join(REPORT_DIR,
                               'first-frame-analysis-2026-11-15.txt')))

# ---- C. plan C: close the host first frame --------------------------------
ck('C01 apply() no longer does IPC or decode on the UI thread',
   'getContentResolver()' not in ensure_preview
   and 'openInputStream' not in ensure_preview
   and 'BitmapFactory' not in ensure_preview
   and 'new Thread(' not in ensure_preview)
ck('C02 p80 failure-caching defect is gone: loaded=true only on success',
   'if (p == null) return;' in ensure_preview
   and ensure_preview.find('if (p == null) return;')
       < ensure_preview.find('sWindowPreviewLoaded = true;')
   and ensure_preview.count('sWindowPreviewLoaded = true;') == 1)
ck('C03 apply() uses the daemon-prefetched picture and a repaint callback',
   'ConfigBus.requestFirstFrame(ctx, previewReady)' in ensure_preview
   and 'refreshWindowBg();' in ensure_preview)
ck('C04 the ready callback repaints the window on the main thread',
   'private final Runnable previewReady' in BD
   and 'refreshWindowBg();' in BD
   and 'postPreviewReady()' in CB
   and 'new Handler(Looper.getMainLooper()).post(r)' in CB)
ck('C05 prefetch runs on MIN_PRIORITY daemon threads',
   all('setPriority(Thread.MIN_PRIORITY)' in b
       and 'setDaemon(true)' in b
       and 'new Thread(' in b for b in (warmup, warmcolour, request)))
ck('C06 BOTH processes warm the SAME prefetch path',
   'prefetchSync(ctx);' in warmup and 'prefetchSync(ctx);' in warmcolour)
ck('C07 config query precedes the media read (lazy derivation on upgrades)',
   'Map<String, String> map = providerMap(ctx);' in prefetch
   and prefetch.find('providerMap(ctx)') < prefetch.find('openInputStream('))
ck('C08 media still goes through the existing read-only provider',
   'media(ConfigStore.WINDOW_PREVIEW_NAME)' in prefetch
   and 'ctx.getContentResolver().openInputStream(' in prefetch)
ck('C09 bounded retry, failure NOT cached',
   'PREVIEW_TRIES = 3' in CB and 'MAX_PREVIEW_ATTEMPTS = 6' in CB
   and 'if (bmp != null) sPrefetchDone = true;' in prefetch
   and 'sPreviewAttempts >= MAX_PREVIEW_ATTEMPTS' in request)
ck('C10 process-wide Bitmap is guarded against recycled instances',
   'b == null || b.isRecycled() ? null : b' in starting_preview
   and 'private static volatile Bitmap sStartingPreview;' in CB)
ck('C11 default-visible prefetch / load evidence',
   'mg-state firstFramePrefetch preview=' in prefetch
   and 'mg-state startingWindowPreview load=' in prefetch
   and 'mg-state windowPreview load=' in ensure_preview)
ck('C12 p78 provider-derived colour and p76 warmup log survive',
   'mg-state windowColourSource=provider win=' in prefetch
   and 'config warmup ms=' in warmup
   and 'sWindowColorFromProvider' in CB)
ck('C13 ensureWindowPreview() still runs in apply() before the first draw',
   'ensureWindowPreview();' in apply_body
   and apply_body.find('clearWindowBackground();')
       < apply_body.find('ensureWindowPreview();'))
ck('C14 window backdrop priority unchanged: base > transition > p20 > preview',
   'c.drawColor(windowBgColor);' in win_draw
   and 'Bitmap t = transitionFrame;' in win_draw
   and 'snapshot = drawRegion(c, 0, 0, w, h);' in win_draw
   and 'if (!snapshot)' in win_draw
   and 'Bitmap p = sWindowPreview;' in win_draw
   and win_draw.find('Bitmap t = transitionFrame;')
       < win_draw.find('drawRegion(c, 0, 0, w, h)'))
ck('C15 tint overlay and opaque Drawable contract survive',
   'if (windowTintARGB != 0) c.drawColor(windowTintARGB);' in win_draw
   and 'PixelFormat.OPAQUE' in BD)
ck('C16 firstFrame SOURCE is directly measured, once, and L1 is silent',
   'mg-state firstFrame src=' in win_draw
   and '!sFirstFrameLogged && slot != ConfigContract.Slot.L1' in win_draw
   and win_draw.count('sFirstFrameLogged = true;') == 1)
ck('C17 source classification covers transition/snapshot/preview/colour',
   all(s in win_draw for s in ('"transition"', '"snapshot"',
                               '"preview"', '"color"')))
ck('C18 preview never enters the glass sampling chain',
   'sWindowPreview' not in method_body(BD, 'public boolean drawRegion(Canvas c,')
   and 'sWindowPreview' not in method_body(BD, 'private Bitmap snapshot()'))
ck('C19 p80 preview generation in the module process is untouched',
   'WINDOW_PREVIEW_NAME = "window_preview.png"' in CS
   and 'WINDOW_PREVIEW_MAX_EDGE = 320' in CS
   and f('ConfigStore.java') == arc('ConfigStore.java'))
ck('C20 p80 lazy derivation trigger (colour OR preview missing) untouched',
   '|| !ConfigStore.hasWindowPreview(getContext())' in MP
   and f('ModuleProvider.java') == arc('ModuleProvider.java'))

# ---- D. plan B: negative diagnostics + automatic fallback -----------------
ck('D01 EVERY starting-window entry emits a default-visible seen line',
   'seen(chain, pkg,' in skip_hook
   and 'mg-state startingWindow seen method=' in seen
   and ' pkg=' in seen and ' mode=' in seen)
ck('D02 seen line is emitted BEFORE the skip/paint decision',
   skip_hook.find('seen(chain, pkg,')
   < skip_hook.find('if (target && !paint)'))
ck('D03 A and B are mutually exclusive and never show a white splash',
   'boolean paint = target && sPaintHooks > 0' in skip_hook
   and 'ConfigBus.startingPreview() != null' in skip_hook
   and 'if (target && !paint)' in skip_hook
   and 'return skipValue(chain);' in skip_hook)
ck('D04 p79 three-entry skip coverage is not rolled back',
   'ShellTaskOrganizer' in GE and 'StartingWindowController' in GE
   and 'HnSplashscreenWindowCreator' in GE and 'hookSkips' in GE)
ck('D05 skipValue zero-value helper survives',
   'private static Object skipValue(' in GE
   and 'Boolean.FALSE' in GE)
ck('D06 a cold provider gets a bounded retry on the SystemUI side',
   'ConfigBus.requestFirstFrame(sSystemUiContext, null)' in skip_hook
   and 'sSystemUiContext = (Context) app;' in paint_install)
ck('D07 mg-state channel bypasses the default-off perf.debugLog',
   'msg.startsWith("mg-state ")' in GE and 'mg-build ' in GE)
ck('D08 all new diagnostic lines use the always-visible prefix',
   all(s in GE for s in ('mg-state startingWindow paintHooks=',
                         'mg-state startingWindow seen method=',
                         'mg-state startingWindowPreview applied=content',
                         'mg-state startingWindowPreview applied=root')))

# ---- E. L1 freeze and exact change scope ----------------------------------
for name in ('MaterialBridge.java', 'LiquidGlassView.java', 'LiquidShader.java'):
    ck('E01 %s == p30 baseline (bytes)' % name,
       open(os.path.join(SRC, name), 'rb').read()
       == open(os.path.join(BASE, name), 'rb').read())
for name, sig in [('drawRegion()', 'public boolean drawRegion(Canvas c,'),
                  ('snapshot()', 'private Bitmap snapshot()'),
                  ('recycle()', 'private void recycle()'),
                  ('ensurePlateSnapshot()', 'private void ensurePlateSnapshot()'),
                  ('drawLiveVideoRegion()', 'private boolean drawLiveVideoRegion(')]:
    a, b = method_body(BD, sig), method_body(arc('BackdropLayer.java'), sig)
    ck('E02 %s == p80 and non-vacuous' % name, bool(a and b) and a == b)
UNTOUCHED = ('CardScanner.java', 'ConfigContract.java', 'ConfigStore.java',
             'EdgeFade.java', 'FallbackGlass.java', 'LiquidGlassView.java',
             'LiquidShader.java', 'MaterialBridge.java', 'ModuleProvider.java',
             'OemCardSuppressor.java', 'PageLevel.java', 'RenderController.java',
             'ScaleMode.java', 'Scope.java', 'SettingsActivity.java')
for name in UNTOUCHED:
    ck('E03 %s == p80' % name, f(name) == arc(name))
diff = []
for n in sorted(os.listdir(SRC)):
    if n.endswith('.java'):
        p = os.path.join(ARC, n[:-5] + '-p80-current.java')
        if not os.path.isfile(p) or f(n) != read(p):
            diff.append(n)
ck('E04 exactly 3 sources differ from p80',
   diff == ['BackdropLayer.java', 'ConfigBus.java', 'GlassEntry.java'])
ck('E05 all 18 p80 snapshots and p81 snapshots exist',
   len([n for n in os.listdir(ARC) if n.endswith('-p80-current.java')]) == 18
   and len([n for n in os.listdir(ARC) if n.endswith('-p81-current.java')]) == 18)
ck('E06 p80 APK archived before build.sh clears build-out/',
   os.path.isfile(os.path.join(ARC, 'MagicGlass-0.5.0-p80.apk')))
ck('E07 9 scopes unchanged',
   len([l for l in read(SCOPE).splitlines() if l.strip()]) == 9)
ck('E08 zero MIUI inheritance',
   not re.search(r'setMi\w*|com\.ciallo', ALL_CODE))
ck('E09 p80 account-centre measured-colour candidate is NOT rolled back',
   'measuredOpaqueWhite' in RC and f('RenderController.java') == arc('RenderController.java'))
ck('E10 modern settings page is NOT rolled back',
   f('SettingsActivity.java') == arc('SettingsActivity.java'))

# ---- F. red lines / dead ends / frame path --------------------------------
ck('F01 no hard reboot / device write / system partition edit anywhere',
   'adb reboot' not in ALL_CODE and 'openOutputStream' not in ALL_CODE
   and 'setprop ctl.restart' not in ALL_CODE)
ck('F02 CPU video-capture dead end stays absent',
   'captureVideoFrame' not in ALL_CODE
   and 'VIDEO_CAPTURE_INTERVAL_MS' not in ALL_CODE)
ck('F03 p73 GPU live layer and p74/p75 transition survive',
   'drawLiveVideoRegion' in BD and 'drawRenderNode' in BD
   and 'TRANSITION snap=once' in BD and 'video.getTransform(null)' in BD)
ck('F04 video hard gate survives', 'if (videoMode && !released)' in BD)
ck('F05 frame path: no Bitmap allocation / decode in the window draw method',
   all(s not in win_draw for s in ('Bitmap.createBitmap', 'decodeStream',
                                   'new Thread(', 'openInputStream')))
ck('F06 no mg-state line inside per-frame scan/sync/drawRegion',
   'mg-state ' not in method_body(RC, 'private boolean onPreDraw()')
   and all('mg-state ' not in method_body(BD, s)
           for s in ('public boolean drawRegion(Canvas c,',
                     'private Bitmap snapshot()',
                     'private void ensurePlateSnapshot()')))
ck('F07 no timer / polling added to the first-frame path',
   'postDelayed(' not in code_only(ensure_preview)
   and 'postDelayed(' not in code_only(win_draw))
ck('F08 compile_check stub covers the new BitmapDrawable',
   'android.graphics.drawable.BitmapDrawable' in read(COMPILE))
ck('F09 no absolute screen-Y threshold in the new hooks',
   'getLocationOnScreen' not in GE and 'heightPixels' not in GE)

# ---- G. device criteria (strings only, no claim of effect) ----------------
ck('G01 build/version truth criterion is present in code',
   'mg-build ' in GE and 'BUILD' in GE)
ck('G02 all six layer-classification signals are emitted',
   all(s in (GE + CB + BD) for s in
       ('mg-state startingWindow skipHooks=',
        'mg-state startingWindow seen method=',
        'mg-state startingWindow paintHooks=',
        'mg-state startingWindowPreview load=',
        'mg-state startingWindowPreview applied=',
        'mg-state firstFrame src=')))
ck('G03 host-side first-frame and colour signals are emitted',
   all(s in (CB + BD) for s in
       ('mg-state windowPreview load=',
        'mg-state firstFramePrefetch preview=',
        'mg-state windowColourSource=provider win=')))

print('\n%d checks, %d failures' % (CHECKS[0], len(FAILS)))
if FAILS:
    print('FAILED:')
    for x in FAILS:
        print('  - ' + x)
    sys.exit(1)
print('ALL_GREEN')
sys.exit(0)
