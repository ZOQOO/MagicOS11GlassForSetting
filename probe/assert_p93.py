#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""assert_p93.py -- deterministic (no device) invariants for 0.5.0-p93.

User (verbatim): "完全按照这个文件思路制作一版成品，其他问题暂时搁置。"
Blueprint: `probe/probe-report/first-frame-analysis-2026-11-15.txt`
(findings §70), i.e. plan A (preferred) + C (required) + B (negative log),
shipped as ONE finished APK. "Other issues" (p1's P0/P1/P2, 账号中心 white
card, settings page, L1) are explicitly shelved: NO source outside the three
first-frame files differs from p81.

p81 already implemented A/C/B. This build closes the three requirements of
the blueprint that p81 still missed:
  §3.1/§4 A  PREFERRED guard point `HnSplashscreenContentDrawer
             .createContentView(StartingWindowInfo, ...)` -- arg0 IS the
             StartingWindowInfo, so no parameter guessing.
  §4 A       "static cache (SystemUI long-lived, read once; REFRESH on
             config change)" -- a RECEIVER_EXPORTED config receiver plus a
             generation counter, in BOTH processes.
  §4 B       "confirm the return type of StartingWindowController
             .addStartingWindow before hooking; skipValue() returning
             0/false for a NON-void method may change the ROM's decision"
             -- decompiled classes3.dex proves all three are void; a hard
             return-type gate makes that an invariant.
  §6         exact acceptance line `mg-state startingWindowPreview
             applied=<pkg>`.

All class names / signatures come from the archived jadx output in
`probe/probe-report/sysui_dec/`, never a guess. Device is offline
(adb -s A4UF6R6116003189 -> device not found): this script makes NO claim
about the loaded dex or the visible first frame. A green gate proves
"the planned code is inside the APK", nothing more (§69).
"""

import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'magicglass')
ARC = os.path.join(ROOT, 'probe', 'probe-report', 'archive')
BASELINE = os.path.join(ROOT, 'probe', 'probe-report', 'baselines',
                        'L1-ACCEPTED-p30', 'src', 'main', 'java', 'com',
                        'magicglass')
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
    """p81 snapshot = the exact baseline this build started from."""
    return read(os.path.join(ARC, name[:-5] + '-p81-current.java'))


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
invalidate = method_body(CB, 'public static void invalidateStartingPreview(')
preview_gen = method_body(CB, 'public static int previewGeneration(')
ensure_preview = method_body(BD, 'private void ensureWindowPreview(')
win_draw = method_body(BD, 'public void draw(Canvas c)')
apply_body = method_body(BD, 'public void apply(ConfigContract.Slot slot,')
skip_hook = method_body(GE, 'class StartingWindowSkipHooker')
paint_hook = method_body(GE, 'class StartingWindowPaintHooker')
root_hook = method_body(GE, 'class StartingWindowRootHooker')
guard_hook = method_body(GE, 'class StartingWindowGuardHooker')
guard_install = method_body(GE, 'private int hookContentViewGuard(')
guard_methods = method_body(GE, 'private int hookGuardMethods(')
guard_pkg = method_body(GE, 'private static String guardPackage(')
config_receiver = method_body(GE, 'private static void registerConfigRefresh(')
paint_methods = method_body(GE, 'private int hookPaintMethods(')
root_add = method_body(GE, 'private int hookRootAddView(')
paint_install = method_body(GE, 'private void installStartingWindowHooks(')
skip_install = method_body(GE, 'private int hookSkips(')
seen = method_body(GE, 'private static void seen(')

# ---- A. version truth -----------------------------------------------------
ck('A01 GlassEntry.BUILD == 0.5.0-p93',
   'public static final String BUILD = "0.5.0-p93";' in GE)
ck('A02 module.prop version == 0.5.0-p93', 'version=0.5.0-p93' in PROP_TXT)
ck('A03 module.prop versionCode == 121', 'versionCode=121' in PROP_TXT)
ck('A04 p81 marker gone from module.prop', '0.5.0-p81' not in PROP_TXT)
ck('A05 BUILD and module.prop agree',
   '0.5.0-p93' in GE and '0.5.0-p93' in PROP_TXT)
ck('A06 build fingerprint line survives', 'say("mg-build " + BUILD' in GE)

# ---- B. NEW: report §3.1 + §4 A PREFERRED guard point --------------------
ck('B01 createContentView is hooked on BOTH decompiled drawer classes',
   'hookGuardMethods(loader,' in guard_install
   and 'com.android.wm.shell.startingsurface.HnSplashscreenContentDrawer'
       in guard_install
   and 'com.android.wm.shell.startingsurface.SplashscreenContentDrawer'
       in guard_install)
ck('B02 guard hook resolves the DEX method name only, no guessed class',
   'm.getName().equals("createContentView")' in guard_methods
   and 'm.getParameterTypes().length < 1' in guard_methods
   and 'Class.forName(className, false, loader)' in guard_methods)
ck('B03 guard is a BEFORE hook that ALWAYS proceeds (plan A failure rule)',
   'return chain.proceed();' in guard_hook
   and 'chain.proceed()' not in guard_hook.split('return chain.proceed();')[0]
   and guard_hook.find('chain.proceed()') > guard_hook.find('seen(chain, pkg,'))
ck('B04 guard arg0 IS StartingWindowInfo (DEX line 136), no param scan',
   'startingPackage(chain.getArg(0))' in guard_hook
   and 'chain.getArg(1)' not in guard_hook)
ck('B05 guard emits the default-visible NEGATIVE line for every entry',
   'seen(chain, pkg, target ? "guard" : "nontarget");' in guard_hook
   and 'mg-state startingWindow seen method=' in seen)
ck('B06 guard records a short-lived, whitelist-only package',
   'sGuardPackage = pkg;' in guard_hook
   and 'sGuardAtMs = SystemClock.uptimeMillis();' in guard_hook
   and 'sGuardPackage = null;' in guard_hook
   and 'SystemClock.uptimeMillis() - sGuardAtMs > GUARD_TTL_MS' in guard_pkg
   and '!Scope.isStartingWindowTarget(p)' in guard_pkg)
ck('B07 guard triggers the bounded prefetch on a cold provider',
   'ConfigBus.startingPreview() == null' in guard_hook
   and 'ConfigBus.requestFirstFrame(sSystemUiContext, null)' in guard_hook)
ck('B08 guard failures can never break SystemUI',
   'say("mg-state startingWindow guard failed (proceeding): " + t)'
       in guard_hook
   and 'catch (Throwable' in guard_methods)
ck('B09 guard count is installed and logged, separate from paint count',
   'sGuardHooks = hookContentViewGuard(loader);' in paint_install
   and 'mg-state startingWindow guardHooks=' in paint_install
   and 'sPaintHooks = hookStartingWindowPaint(loader);' in paint_install)
ck('B10 the guard is the fallback for p81 parameter guessing in BOTH hooks',
   'if (pkg == null) pkg = guardPackage();' in paint_hook
   and 'if (pkg == null) pkg = guardPackage();' in root_hook)
ck('B11 LSPosed 2.2.1: guard hooker is public static final',
   'public static final class StartingWindowGuardHooker '
   'implements XposedInterface.Hooker' in GE)
ck('B12 decompiled DEX evidence for the guard signature is archived',
   os.path.isfile(os.path.join(REPORT_DIR, 'sysui_dec',
                               'HnSplashscreenContentDrawer.java'))
   and 'public void createContentView(' in
       read(os.path.join(REPORT_DIR, 'sysui_dec',
                         'HnSplashscreenContentDrawer.java')))

# ---- C. NEW: report §4 A "REFRESH on config change" ----------------------
ck('C01 SystemUI registers a RECEIVER_EXPORTED config receiver',
   'new BroadcastReceiver()' in config_receiver
   and 'new IntentFilter(ConfigBus.ACTION_CONFIG_CHANGED)' in config_receiver
   and 'Context.RECEIVER_EXPORTED' in config_receiver
   and 'ctx.registerReceiver(' in config_receiver)
ck('C02 a 2-arg registerReceiver fallback survives for older ROMs',
   config_receiver.count('ctx.registerReceiver(') == 2
   and 'catch (Throwable t)' in config_receiver)
ck('C03 receiver is registered exactly once per long-lived SystemUI',
   'sConfigReceiverRegistered' in config_receiver
   and 'sConfigReceiverRegistered = true;' in config_receiver
   and 'registerConfigRefresh((Context) app)' in paint_install)
ck('C04 receiver invalidates the static preview and re-arms the daemon',
   'ConfigBus.invalidateStartingPreview();' in config_receiver
   and 'ConfigBus.requestFirstFrame(ctx, null)' in config_receiver
   and 'mg-state startingWindowPreview refresh=config' in config_receiver)
ck('C05 receiver never does IPC / decode on its own thread',
   'openInputStream' not in config_receiver
   and 'decodeStream' not in config_receiver
   and 'postDelayed(' not in code_only(config_receiver))
ck('C06 invalidateStartingPreview drops refs WITHOUT recycling a live Bitmap',
   'sStartingPreview = null;' in invalidate
   and 'recycle()' not in invalidate
   and 'sPrefetchDone = false;' in invalidate
   and 'sPreviewAttempts = 0;' in invalidate
   and 'sWindowColorFromProvider = false;' in invalidate
   and 'sPreviewGeneration++;' in invalidate)
ck('C07 a generation counter is readable by the host process',
   'private static volatile int sPreviewGeneration;' in CB
   and 'return sPreviewGeneration;' in preview_gen)
ck('C08 a config change DURING a read discards the stale result',
   'final int gen = sPreviewGeneration;' in prefetch
   and 'if (gen != sPreviewGeneration) {' in prefetch
   and 'bmp = null;' in prefetch
   and 'mg-state startingWindowPreview stale=1 gen=' in prefetch)
ck('C09 p93: bounded self-retry (spin budget) instead of a lifetime budget',
   'if (ctx != null && !sPrefetchDone)' in prefetch
   and 'sPreviewSpins < PREVIEW_MAX_SPINS' in prefetch
   and 'if (stale) sPreviewSpins = 0;' in prefetch
   and 'setPriority(Thread.MIN_PRIORITY)' in prefetch
   and 'setDaemon(true)' in prefetch
   and 'postDelayed(' not in code_only(prefetch))
ck('C10 the HOST also drops its stale picture and repaints',
   'int gen = ConfigBus.previewGeneration();' in ensure_preview
   and 'if (sWindowPreviewGen != gen)' in ensure_preview
   and 'sWindowPreviewLoaded = false;' in ensure_preview
   and 'mg-state windowPreview refresh gen=' in ensure_preview
   and 'refreshWindowBg();' in ensure_preview)
ck('C11 host refresh is a volatile read only (no IPC / alloc / frame work)',
   'getContentResolver()' not in ensure_preview
   and 'openInputStream' not in ensure_preview
   and 'BitmapFactory' not in ensure_preview
   and 'Bitmap.createBitmap' not in ensure_preview)
ck('C12 ConfigStore broadcasts to SystemUI (Scope.all includes SYSTEMUI)',
   'i.setPackage(pkg)' in CS
   and 'Scope.all()' in CS
   and 'out[out.length - 2] = SYSTEMUI;' in f('Scope.java'))
ck('C13 the HOST config receiver also invalidates the static picture',
   'ConfigBus.invalidate();' in RC
   and 'invalidateStartingPreview();' in method_body(CB, 'public static void invalidate()')
   and 'sCached = null;' in method_body(CB, 'public static void invalidate()'))
ck('C14 no second receiver / timer is added to the host path',
   RC.count('new BroadcastReceiver()') == 1
   and f('RenderController.java') == arc('RenderController.java'))

# ---- D. NEW: report §4 B return-type confirmation -------------------------
ck('D01 skip hooks are installed on VOID methods ONLY (§4 B hard gate)',
   'm.getReturnType() != void.class' in skip_install
   and 'hook=skipped' in skip_install
   and 'hook(m).intercept(new StartingWindowSkipHooker());' in skip_install)
ck('D02 the skip gate logs the rejected overload instead of hiding it',
   'mg-state startingWindow skipReturn=' in skip_install
   and 'm.getReturnType().getName()' in skip_install)
ck('D03 decompiled DEX proves all three entries are void',
   all(os.path.isfile(os.path.join(REPORT_DIR, 'sysui_dec', n))
       for n in ('ShellTaskOrganizer.java',
                 'StartingWindowController.java',
                 'HnSplashscreenWindowCreator.java'))
   and 'public void addStartingWindow(StartingWindowInfo' in
       read(os.path.join(REPORT_DIR, 'sysui_dec',
                         'ShellTaskOrganizer.java'))
   and 'public void addStartingWindow(StartingWindowInfo' in
       read(os.path.join(REPORT_DIR, 'sysui_dec',
                         'StartingWindowController.java'))
   and 'public void addSplashScreenStartingWindow(' in
       read(os.path.join(REPORT_DIR, 'sysui_dec',
                         'HnSplashscreenWindowCreator.java')))
ck('D04 skipValue zero-value helper still exists as the last-resort guard',
   'private static Object skipValue(' in GE
   and 'Boolean.FALSE' in GE)

# ---- E. report §6 exact criteria strings ---------------------------------
ck('E01 p93: NO path emits applied= any more -- the picture splash was '
   'rejected by the user and skip is the only live path',
   'startingWindowPreview applied=' not in paint_hook
   and 'startingWindowPreview applied=' not in root_hook)
ck('E02 §6 skipHooks / seen / guardHooks / paintHooks all present',
   all(s in GE for s in ('mg-state startingWindow skipHooks=',
                         'mg-state startingWindow seen method=',
                         'mg-state startingWindow guardHooks=',
                         'mg-state startingWindow paintHooks=')))
ck('E03 §6 preview load / windowPreview load / firstFrame src / colour',
   all(s in (GE + CB + BD) for s in
       ('mg-state startingWindowPreview load=',
        'mg-state windowPreview load=',
        'mg-state firstFrame src=',
        'mg-state windowColourSource=provider win=')))
ck('E04 §6 refresh evidence lines are default-visible (mg-state prefix)',
   all(s in (GE + CB + BD) for s in
       ('mg-state startingWindowPreview refresh=config',
        'mg-state startingWindowPreview invalidated gen=',
        'mg-state startingWindowPreview refreshReceiver=',
        'mg-state windowPreview refresh gen=',
        'mg-state startingWindowPreview stale=1 gen=')))
ck('E05 mg-state channel still bypasses the default-off perf.debugLog',
   'msg.startsWith("mg-state ")' in GE and 'mg-build ' in GE)
ck('E06 the blueprint this build implements is archived',
   os.path.isfile(os.path.join(REPORT_DIR,
                               'first-frame-analysis-2026-11-15.txt')))

# ---- F. p81 plan A/C/B NOT rolled back -----------------------------------
ck('F01 hook targets come from the decompiled DEX, not a guessed class',
   'com.android.wm.shell.startingsurface.SplashscreenContentDrawer' in GE
   and 'com.android.wm.shell.startingsurface.HnSplashscreenContentDrawer' in GE
   and 'android.view.WindowManagerGlobal' in GE)
ck('F02 the two factory methods are the decompiled ones',
   '"makeSplashScreenContentView"' in GE
   and '"makeSimpleSplashScreenContentView"' in GE
   and 'getParameterTypes().length < 2' in paint_methods)
ck('F03 factory hooks are AFTER hooks (proceed first, paint after)',
   paint_hook.find('Object result = chain.proceed();')
   < paint_hook.find('v.setBackground('))
ck('F04 p93: the content View is never given a bitmap (no picture frame)',
   'setBackground(new BitmapDrawable' not in paint_hook
   and 'ConfigBus.startingPreview()' not in paint_hook)
ck('F05 root hook reads LayoutParams.packageName (DEX line 73)',
   'field(chain.getArg(1), "packageName")' in root_hook
   and 'p instanceof String ? (String) p : null' in root_hook)
ck('F06 p93: root hook still proceeds unconditionally (it can never '
   'swallow or replace the ROM call)',
   'return chain.proceed();' in root_hook
   and 'return null' not in root_hook)
ck('F07 package-name whitelist guard on BOTH paint hooks',
   'Scope.isStartingWindowTarget(pkg)' in paint_hook
   and 'Scope.isStartingWindowTarget(pkg)' in root_hook)
ck('F08 p93: neither retained hook depends on the preview picture, so a '
   'cold provider can never resurrect a splash',
   'ConfigBus.startingPreview()' not in paint_hook
   and 'ConfigBus.startingPreview()' not in root_hook)
ck('F09 all four hookers are public static final (LSPosed 2.2.1)',
   all(('public static final class ' + n
        + ' implements XposedInterface.Hooker') in GE
       for n in ('StartingWindowPaintHooker', 'StartingWindowRootHooker',
                 'StartingWindowSkipHooker', 'StartingWindowGuardHooker')))
ck('F10 every reflective hook install is wrapped and deoptimized',
   all('deoptimize(m)' in b for b in
       (paint_methods, root_add, guard_methods, skip_install))
   and 'catch (Throwable' in guard_methods)
ck('F11 p93: paint hooks stay installed but no longer decide anything '
   '(the starting window is always suppressed)',
   'sPaintHooks = hookStartingWindowPaint(loader);' in paint_install
   and 'mg-state startingWindow paintHooks=' in paint_install
   and 'sPaintHooks > 0' not in skip_hook)
ck('F12 p93: the surviving evidence lines describe the empty form',
   'startingWindowPreview emptyContent=1' in paint_hook
   and 'startingWindowPreview empty=' in root_hook)
ck('F13 paint-hook failure can never break systemui',
   'say("mg-state startingWindowPreview content failed: " + t)' in paint_hook
   and 'catch (Throwable ignored)' in root_hook)
ck('F14 no /system write, no device file write, no theme resource rewrite',
   'XResources' not in ALL_CODE and 'openOutputStream' not in ALL_CODE
   and '/system' not in code_only(GE))
ck('F15 the ROM decompiled evidence is archived',
   os.path.isfile(os.path.join(REPORT_DIR, 'sysui_dec',
                               'SplashscreenContentDrawer.java')))

ck('F16 apply() no longer does IPC or decode on the UI thread',
   'getContentResolver()' not in ensure_preview
   and 'openInputStream' not in ensure_preview
   and 'BitmapFactory' not in ensure_preview)
ck('F17 p80 failure-caching defect stays gone: loaded=true only on success',
   'if (p == null) return;' in ensure_preview
   and ensure_preview.find('if (p == null) return;')
       < ensure_preview.find('sWindowPreviewLoaded = true;')
   and ensure_preview.count('sWindowPreviewLoaded = true;') == 1)
ck('F18 apply() uses the daemon-prefetched picture and a repaint callback',
   'ConfigBus.requestFirstFrame(ctx, previewReady)' in ensure_preview
   and 'refreshWindowBg();' in ensure_preview)
ck('F19 the ready callback repaints the window on the main thread',
   'private final Runnable previewReady' in BD
   and 'postPreviewReady()' in CB
   and 'new Handler(Looper.getMainLooper()).post(r)' in CB)
ck('F20 prefetch runs on MIN_PRIORITY daemon threads',
   all('setPriority(Thread.MIN_PRIORITY)' in b
       and 'setDaemon(true)' in b
       and 'new Thread(' in b for b in (warmup, warmcolour, request)))
ck('F21 BOTH processes warm the SAME prefetch path',
   'prefetchSync(ctx);' in warmup and 'prefetchSync(ctx);' in warmcolour)
ck('F22 config query precedes the media read (lazy derivation on upgrades)',
   'Map<String, String> map = providerMap(ctx);' in prefetch
   and prefetch.find('providerMap(ctx)') < prefetch.find('openInputStream('))
ck('F23 media still goes through the existing read-only provider',
   'media(ConfigStore.WINDOW_PREVIEW_NAME)' in prefetch
   and 'ctx.getContentResolver().openInputStream(' in prefetch)
ck('F24 p93: failure NOT cached, bounded by cooldown not lifetime',
   'PREVIEW_TRIES = 3' in CB and 'PREVIEW_COOLDOWN_MS' in CB
   and 'MAX_PREVIEW_ATTEMPTS = 6;' not in CB
   and 'if (bmp != null) sPrefetchDone = true;' in prefetch
   and 'now - sLastPreviewAttemptMs < PREVIEW_COOLDOWN_MS' in request)
ck('F25 process-wide Bitmap is guarded against recycled instances',
   'b == null || b.isRecycled() ? null : b' in starting_preview
   and 'private static volatile Bitmap sStartingPreview;' in CB)
ck('F26 p78 provider-derived colour and p76 warmup log survive',
   'mg-state windowColourSource=provider win=' in prefetch
   and 'config warmup ms=' in warmup
   and 'sWindowColorFromProvider' in CB)
ck('F27 ensureWindowPreview() still runs in apply() before the first draw',
   'ensureWindowPreview();' in apply_body
   and apply_body.find('clearWindowBackground();')
       < apply_body.find('ensureWindowPreview();'))
ck('F28 window backdrop priority unchanged: base > transition > p20 > preview',
   'c.drawColor(windowBgColor);' in win_draw
   and 'Bitmap t = transitionFrame;' in win_draw
   and 'snapshot = drawRegion(c, 0, 0, w, h);' in win_draw
   and 'if (!snapshot)' in win_draw
   and 'Bitmap p = sWindowPreview;' in win_draw)
ck('F29 tint overlay and opaque Drawable contract survive',
   'if (windowTintARGB != 0) c.drawColor(windowTintARGB);' in win_draw
   and 'PixelFormat.OPAQUE' in BD)
ck('F30 p93: firstFrame SOURCE still one-shot, now also on L1 + carries slot',
   'mg-state firstFrame src=' in win_draw
   and 'if (!sFirstFrameLogged)' in win_draw
   and 'slot=' in win_draw
   and win_draw.count('sFirstFrameLogged = true;') == 1)
ck('F31 source classification covers transition/snapshot/preview/colour',
   all(s in win_draw for s in ('"transition"', '"snapshot"',
                               '"preview"', '"color"')))
ck('F32 preview never enters the glass sampling chain',
   'sWindowPreview' not in method_body(BD, 'public boolean drawRegion(Canvas c,')
   and 'sWindowPreview' not in method_body(BD, 'private Bitmap snapshot()'))
ck('F33 p84: preview is decoded at screen scale and composited with ScaleMode '
   '(the 33x33 thumbnail was the measured defect)',
   'WINDOW_PREVIEW_NAME = "window_preview.png"' in CS
   and 'writeWindowPreview(c, f, kind, frame);' in CS
   and 'ScaleMode.matrix(mode, zoom, fx, fy,' in CS
   and 'int tw = Math.max(1, vw / 2);' in CS)
ck('F34 p80 lazy derivation trigger is untouched',
   '|| !ConfigStore.hasWindowPreview(getContext())' in MP
   and f('ModuleProvider.java') == arc('ModuleProvider.java'))

ck('F35 EVERY starting-window entry emits a default-visible seen line',
   'seen(chain, pkg,' in skip_hook
   and 'mg-state startingWindow seen method=' in seen
   and ' pkg=' in seen and ' mode=' in seen)
ck('F36 seen line is emitted BEFORE the skip decision',
   skip_hook.find('seen(chain, pkg,')
   < skip_hook.find('if (target) {'))
ck('F37 p93: EVERY target package is skipped unconditionally — no splash '
   'frame of any kind can appear',
   'if (target) {' in skip_hook
   and 'return skipValue(chain);' in skip_hook
   and 'boolean paint' not in skip_hook)
ck('F38 p79 three-entry skip coverage is not rolled back',
   'ShellTaskOrganizer' in GE and 'StartingWindowController' in GE
   and 'HnSplashscreenWindowCreator' in GE and 'hookSkips' in GE)
ck('F39 p93: the skip path no longer depends on a preview being ready, '
   'so a cold provider can never delay or re-enable the splash',
   'ConfigBus.requestFirstFrame(sSystemUiContext, null)' not in skip_hook
   and 'sSystemUiContext = (Context) app;' in paint_install)

# ---- G. L1 freeze, exact change scope, red lines -------------------------
for name in ('MaterialBridge.java', 'LiquidGlassView.java', 'LiquidShader.java'):
    ck('G01 %s == p30 baseline (bytes)' % name,
       open(os.path.join(SRC, name), 'rb').read()
       == open(os.path.join(BASE, name), 'rb').read())
for name, sig in [('drawRegion()', 'public boolean drawRegion(Canvas c,'),
                  ('snapshot()', 'private Bitmap snapshot()'),
                  ('recycle()', 'private void recycle()'),
                  ('ensurePlateSnapshot()', 'private void ensurePlateSnapshot()')]:
    a, b = method_body(BD, sig), method_body(arc('BackdropLayer.java'), sig)
    ck('G02 %s == p81 and non-vacuous' % name, bool(a and b) and a == b)
ck('G02b p93: video gate requires a READY surface before claiming a draw',
   'if (!video.isAvailable()) return false;'
   in method_body(BD, 'private boolean drawLiveVideoRegion('))
UNTOUCHED = ('CardScanner.java', 'ConfigContract.java',
             'EdgeFade.java', 'FallbackGlass.java', 'LiquidGlassView.java',
             'LiquidShader.java', 'MaterialBridge.java', 'ModuleProvider.java',
             'OemCardSuppressor.java', 'PageLevel.java', 'RenderController.java',
             'ScaleMode.java', 'Scope.java', 'SettingsActivity.java')
for name in UNTOUCHED:
    ck('G03 %s == p81 (other issues shelved)' % name, f(name) == arc(name))
diff = []
for n in sorted(os.listdir(SRC)):
    if n.endswith('.java'):
        p = os.path.join(ARC, n[:-5] + '-p81-current.java')
        if not os.path.isfile(p) or f(n) != read(p):
            diff.append(n)
ck('G04 exactly 3 sources differ from p81',
   diff == ['BackdropLayer.java', 'ConfigBus.java', 'ConfigStore.java',
           'GlassEntry.java'])
ck('G05 all 18 p81 and 18 p93 snapshots exist',
   len([n for n in os.listdir(ARC) if n.endswith('-p81-current.java')]) == 18
   and len([n for n in os.listdir(ARC)
            if n.endswith('-p93-current.java')]) == 18)
ck('G06 p81 APK archived before build.sh clears build-out/',
   os.path.isfile(os.path.join(ARC, 'MagicGlass-0.5.0-p81.apk')))
ck('G07 the user-supplied 0.2.1-p1 APK is archived',
   os.path.isfile(os.path.join(ARC, 'MagicGlass-0.2.1-p1.apk')))
ck('G08 9 scopes unchanged',
   len([l for l in read(SCOPE).splitlines() if l.strip()]) == 9)
ck('G09 zero MIUI inheritance',
   not re.search(r'setMi\w*|com\.ciallo', ALL_CODE))
ck('G10 p80 account-centre measured-colour candidate is NOT rolled back',
   'measuredOpaqueWhite' in RC and f('RenderController.java') == arc('RenderController.java'))
ck('G11 modern settings page is NOT rolled back',
   f('SettingsActivity.java') == arc('SettingsActivity.java'))
ck('G12 p1 P0 (query derivation) is deliberately NOT touched',
   'ConfigStore.ensureWindowColor(getContext())' in MP
   and f('ModuleProvider.java') == arc('ModuleProvider.java'))
ck('G13 p1 P1 (opaque window backdrop base) is preserved',
   'c.drawColor(windowBgColor);' in win_draw
   and 'Bitmap p = sWindowPreview;' in win_draw
   and 'p = ConfigBus.startingPreview();' in win_draw)

ck('G14 no hard reboot / device write / system partition edit anywhere',
   'adb reboot' not in ALL_CODE and 'openOutputStream' not in ALL_CODE
   and 'setprop ctl.restart' not in ALL_CODE)
ck('G15 CPU video-capture dead end stays absent',
   'captureVideoFrame' not in ALL_CODE
   and 'VIDEO_CAPTURE_INTERVAL_MS' not in ALL_CODE)
ck('G16 p73 GPU live layer and p74/p75 transition survive',
   'drawLiveVideoRegion' in BD and 'drawRenderNode' in BD
   and 'TRANSITION snap=once' in BD and 'video.getTransform(null)' in BD)
ck('G17 video hard gate survives', 'if (videoMode && !released)' in BD)
ck('G18 frame path: no Bitmap allocation / decode in the window draw method',
   all(s not in win_draw for s in ('Bitmap.createBitmap', 'decodeStream',
                                   'new Thread(', 'openInputStream')))
ck('G19 no mg-state line inside per-frame scan/sync/drawRegion',
   'mg-state ' not in method_body(RC, 'private boolean onPreDraw()')
   and all('mg-state ' not in method_body(BD, s)
           for s in ('public boolean drawRegion(Canvas c,',
                     'private Bitmap snapshot()',
                     'private void ensurePlateSnapshot()')))
ck('G20 no timer / polling added to the first-frame path',
   all('postDelayed(' not in code_only(b)
       for b in (ensure_preview, win_draw, prefetch, config_receiver)))
ck('G21 compile_check stub covers all classes used by the new code',
   all(s in read(COMPILE) for s in
       ('android.content.BroadcastReceiver', 'android.content.IntentFilter',
        'android.graphics.drawable.BitmapDrawable', 'android.os.SystemClock',
        'RECEIVER_EXPORTED')))
ck('G22 no absolute screen-Y threshold in any starting-window hook',
   'getLocationOnScreen' not in GE and 'heightPixels' not in GE)

# ---- H: p93 long-lived-SystemUI fix (the ONLY behavioural change) --------
ck('H01 the LIFETIME attempt budget is gone (a long-lived SystemUI could '
   'burn it at boot and never retry again for the whole boot cycle)',
   'MAX_PREVIEW_ATTEMPTS = 6;' not in CB
   and 'sPreviewAttempts >= MAX_PREVIEW_ATTEMPTS' not in CB)
ck('H02 retries are throttled by a TIME WINDOW, not a lifetime counter',
   'PREVIEW_COOLDOWN_MS = 1200L' in CB
   and 'now - sLastPreviewAttemptMs < PREVIEW_COOLDOWN_MS' in request)
ck('H03 the cooldown is cleared when the user picks a NEW background',
   'sLastPreviewAttemptMs = 0;' in method_body(CB,
       'public static void invalidateStartingPreview('))
ck('H04 consecutive reads inside one trip are spaced (cold provider needs '
   'a moment to bind; three instant reads used to fail together)',
   'Thread.sleep(PREVIEW_COOLDOWN_MS)' in prefetch
   and 'if (i > 0)' in prefetch)
ck('H05 self-retry is bounded per request chain (host gets its one attach)',
   'PREVIEW_MAX_SPINS = 3' in CB
   and 'sPreviewSpins < PREVIEW_MAX_SPINS' in prefetch)
ck('H06 a new external request restores the spin budget',
   'sPreviewSpins = 0;' in request)
ck('H07 warmWindowColor stops on the PICTURE, not on the colour '
   '(a colour success must not suppress the picture read)',
   'if (ctx == null || sPrefetchDone) return;' in method_body(CB,
       'public static void warmWindowColor('))
ck('H08 a successful read still stops all further retries',
   'if (bmp != null) sPrefetchDone = true;' in prefetch
   and 'if (ctx == null || sPrefetchDone || sPrefetchRunning) return null;' in request)
ck('H09 no timer / alarm / polling is introduced by the retry path',
   'postDelayed(' not in code_only(prefetch)
   and 'postDelayed(' not in code_only(request)
   and 'AlarmManager' not in CB and 'Timer(' not in CB)
ck('H10 retry threads stay daemon + MIN_PRIORITY (never block exit or UI)',
   code_only(prefetch).count('setDaemon(true)') >= 1
   and code_only(prefetch).count('setPriority(Thread.MIN_PRIORITY)') >= 1
   and code_only(request).count('setDaemon(true)') >= 1)

ck('H11 p93: the post-proceed kept/reapplied review is REMOVED together '
   'with the picture path (skip is the only live form)',
   'kept=0 reapplied=1' not in root_hook
   and 'now == applied' not in root_hook
   and 'nothing to re-apply' in root_hook)
ck('H12 p93: no bitmap identity comparison survives in the root hook',
   'applied != null && now == applied' not in root_hook
   and 'getBitmap' not in code_only(root_hook))
ck('H13 p93: the surviving starting-window evidence is default-visible '
   '(`mg-state startingWindowPreview empty=`)',
   'mg-state startingWindowPreview empty=' in GE
   and 'mg-state startingWindowPreview kept=' not in GE)

ck('H14 p93: L1 render trio is STILL byte-identical to p30 (the probe '
   'optimisation that touched MaterialBridge was reverted for exactly this)',
   f('LiquidGlassView.java') == read(os.path.join(
       BASELINE, 'LiquidGlassView.java'))
   and f('MaterialBridge.java') == read(os.path.join(
       BASELINE, 'MaterialBridge.java'))
   and f('LiquidShader.java') == read(os.path.join(
       BASELINE, 'LiquidShader.java')))
ck('H14b p93: the slower/lower layers are untouched (other issues shelved)',
   f('RenderController.java')
   == read(os.path.join(ARC, 'RenderController-p93-current.java'))
   and f('ConfigStore.java')
   == read(os.path.join(ARC, 'ConfigStore-p93-current.java'))
   and f('ModuleProvider.java')
   == read(os.path.join(ARC, 'ModuleProvider-p93-current.java'))
   and f('ConfigStore.java')
   == read(os.path.join(ARC, 'ConfigStore-p93-current.java'))
   and f('CardScanner.java')
   == read(os.path.join(ARC, 'CardScanner-p93-current.java')))
ck('H15 the p82 archive is kept for rollback (never delete an old build)',
   os.path.exists(os.path.join(ARC, 'MagicGlass-0.5.0-p82.apk')))

# ---- J. p93 root-cause fixes (all measured on device) --------------------
ck('J01 SystemUI gets a Context from Instrumentation.callApplicationOnCreate '
   '(ActivityThread.currentApplication() is null there -> prefetch never ran)',
   'callApplicationOnCreate' in GE
   and 'class StartingWindowAppHooker' in GE
   and 'mg-state startingWindow ctx=captured' in GE)
ck('J02 the Application hook warms the SAME picture + config receiver',
   'ConfigBus.warmWindowColor(ctx);' in method_body(GE,
       'class StartingWindowAppHooker')
   and 'registerConfigRefresh(ctx);' in method_body(GE,
       'class StartingWindowAppHooker'))
ck('J03 the app hook is installed from installStartingWindowHooks and logged',
   'hookAppCreate(loader)' in paint_install
   and 'mg-state startingWindow appHook=' in paint_install)
ck('J04 first-frame race: the draw falls back to the process-wide picture '
   'when the host cache is still empty (measured src alternated preview/color)',
   'p = ConfigBus.startingPreview();' in win_draw)
ck('J05 the preview knows its source, so switching image<->video regenerates',
   'WINDOW_PREVIEW_SRC_NAME' in CS
   and 'writePreviewSource(f.getParentFile(), f.getName());' in CS
   and 'readPreviewSource(' in CS)
ck('J06 a too-small (33x33-era) preview counts as MISSING and is rebuilt',
   'if (o.outWidth < 200 || o.outHeight < 200) return false;' in CS)
ck('J07 the preview is composited with the SAME matrix as the backdrop, '
   'so frame 0 cannot be cropped differently (p74/p75 lesson)',
   'ScaleMode.matrix(mode, zoom, fx, fy,' in CS
   and 'cv.concat(m);' in CS)

# ---- J. p93 startup-timing work (measured on device) ---------------------
ck('J01 the settings process also has NO Application in onPackageLoaded, '
   'so the warm-up is installed on Instrumentation.callApplicationOnCreate',
   'Class.forName("android.app.Instrumentation")' in GE
   and 'callApplicationOnCreate' in GE
   and 'class ConfigWarmHooker' in GE)
ck('J02 ConfigWarmHooker warms the provider AND exposes ctx=app in the log',
   'ConfigBus.warmUpAsync(ctx);' in method_body(GE, 'class ConfigWarmHooker')
   and 'ConfigBus.warmWindowColor(ctx);' in method_body(GE,
       'class ConfigWarmHooker')
   and 'mg-state config warm ctx=app' in GE)
ck('J03 the hook is a BEFORE-hook and can never swallow the call',
   method_body(GE, 'class ConfigWarmHooker').count('return chain.proceed();') == 1)
ck('J04 config load is now ALWAYS visible, so the startup budget can be '
   'attributed from one capture (measured miss=5 ms)',
   'mg-state config load cache=miss' in CB
   and 'mg-state config load cache=hit' in CB)
ck('J05 the first-frame line reports the bitmap that was ACTUALLY drawn '
   '(the old one printed src=preview together with preview=none)',
   'Bitmap used = previewUsed != null ? previewUsed : sWindowPreview;' in BD
   and 'used.isRecycled() ? "none"' in BD)
ck('J06 the async probe was fully REVERTED: it gained 0 ms on device '
   '(probe ms=0) and would have broken the p30 byte-identical render trio',
   'probeAsync' not in f('MaterialBridge.java')
   and 'awaitProbe' not in f('MaterialBridge.java')
   and 'ensureAvailability' not in GE
   and 'sProbeLatch' not in f('MaterialBridge.java'))

# ---- K. p93: startup stage clock + the empty-splash experiment -----------
ck('K01 the stage clock is anchored at module load and exposed to the '
   'first-frame line (one clock, so both lines are comparable)',
   'sBootMs = SystemClock.uptimeMillis();' in GE
   and 'public static long stageAt()' in GE
   and 'at=+" + GlassEntry.stageAt()' in BD)
ck('K02 stage lines are BOUNDED (eight per process) - never a per-frame path',
   'if (n >= 8) return;' in GE and 'sStageLines' in GE)
ck('K03 every lifecycle callback reports its own hook cost',
   'stage(String.valueOf(chain.getExecutable().getName())' in GE
   and 'SystemClock.uptimeMillis() - t0' in GE)
ck('K04 the p90/p91 empty-splash experiment was REVERTED to unconditional '
   'skip: no measurable gain (877-915 vs 1624-1639 on the SAME build) and a '
   'real black-frame risk',
   'return skipValue(chain);' in GE
   and 'seen(chain, pkg, target ? "skip" : "nontarget");' in GE)

print('\n%d checks, %d failures' % (CHECKS[0], len(FAILS)))
if FAILS:
    print('FAILED:')
    for x in FAILS:
        print('  - ' + x)
    sys.exit(1)
print('ALL_GREEN')
sys.exit(0)
