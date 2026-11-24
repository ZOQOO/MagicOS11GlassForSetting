#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""assert_p76.py -- deterministic (no device) invariants for 0.5.0-p76.

User: "后台设置好背景后，第一次进入设置会有大约1-2秒的延迟才会出现
背景，看一下可否进行优化，在整体优化一下功耗。"

Code-level first-frame chain (no device available, so no timing claim is
made): RenderController.attach() -> refresh(true) -> ConfigBus.load()
(1 cross-process provider query, which on a cold host process must bind /
possibly START the module provider) -> backdrop.apply() -> showImage() ->
decode() (2 cross-process openInputStream + a multi-megapixel decode) ->
snapshot() (full-screen bitmap). Every L1/L2/L3 Activity builds a NEW
BackdropLayer, so the whole decode repeats on every page.

p76 fixes, without changing any visual result or the frozen L1 render path:
  1. ConfigBus.warmUpAsync(): bind the provider EARLY (at hook install, on a
     daemon thread) so the cold start is hidden before any Activity exists.
  2. Event-driven ConfigBus Source cache: invalidate() is called by the
     config broadcast receiver on EVERY settings write, so idle pages /
     repeated onResume pay ZERO IPC while live edits still take effect.
  3. Process-level decoded-Bitmap cache: L1/L2/L3 reuse one Bitmap, so only
     the first ever decode pays IPC + JPEG decode. Shared Bitmap is never
     recycled by an Activity.
  4. requestRender() only on a real change: repeated lifecycle refreshes no
     longer force an extra full draw traversal.
  5. Objective counters in the device log for before/after comparison.

Checks: A warm-up, B config cache, C bitmap cache, D power/no new frame
work, E L1 byte-identical protection, F version truth.
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

FAILS = []


def ck(name, ok):
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
    text = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
    return re.sub(r'//[^\n]*', '', text)


CB = read(os.path.join(SRC, 'ConfigBus.java'))
GE = read(os.path.join(SRC, 'GlassEntry.java'))
BD = read(os.path.join(SRC, 'BackdropLayer.java'))
RC = read(os.path.join(SRC, 'RenderController.java'))
BD75 = read(os.path.join(ARC, 'BackdropLayer-p75-current.java'))
GE75 = read(os.path.join(ARC, 'GlassEntry-p75-current.java'))
ALL = ''.join(read(os.path.join(SRC, f)) for f in os.listdir(SRC)
              if f.endswith('.java'))
ALL_CODE = code_only(ALL)

warm = method_body(CB, 'public static void warmUpAsync(')
load = method_body(CB, 'public static ConfigContract.Source load(')
inval = method_body(CB, 'public static void invalidate()')
decode = method_body(BD, 'private Bitmap decode(String name)')
recycle = method_body(BD, 'private void recycle()')
refresh = method_body(RC, 'private void refresh(boolean force)')

# ---- A. early provider warm-up -------------------------------------------
ck('A01 warmUpAsync() body non-empty', bool(warm))
ck('A02 runs on a daemon, MIN_PRIORITY background thread',
   'setDaemon(true)' in warm and 'Thread.MIN_PRIORITY' in warm
   and 't.start()' in warm)
ck('A03 exactly ONE warm-up per host process',
   'sWarmUpDone' in warm and 'if (sWarmUpDone) return;' in warm)
ck('A04 reads ONLY our own config keys (no writes)',
   'URI_CONFIG' in warm and 'insert(' not in warm and 'update(' not in warm)
ck('A05 warm-up is scheduled at hook install, before any Activity',
   'ConfigBus.warmUpAsync((Context) app)' in GE
   and GE.find('ConfigBus.warmUpAsync') > GE.find('installRenderHooks();'))
ck('A06 obtains Context by reflection (no hidden-API compile dependency)',
   'Class.forName("android.app.ActivityThread")' in GE
   and 'currentApplication' in GE)
ck('A07 warm-up objective timing log',
   'config warmup ms=' in warm)

# ---- B. event-driven config cache ----------------------------------------
ck('B01 invalidate() is no longer a no-op',
   'sCached = null;' in inval and bool(inval))
ck('B02 load() returns the cached Source with ZERO IPC',
   'ConfigContract.Source cached = sCached;' in load
   and 'if (cached != null)' in load
   and 'providerMap' not in load[:load.find('if (cached != null)')]
   and 'return cached;' in load)
ck('B03 cache is populated only after a real provider read',
   'providerMap(ctx)' in load and 'sCached = src;' in load)
ck('B04 remote/default fallback preserved (p63 semantics)',
   'remoteOrDefault(map)' in CB and 'ConfigContract.from(remote)' in CB)
ck('B05 broadcast receiver invalidates BEFORE debounced refresh',
   'ConfigBus.invalidate();' in RC
   and RC.find('ConfigBus.invalidate();')
   < RC.find('handler.postDelayed(configRefresh'))
ck('B06 objective counters: ipcTotal / hits / ms',
   'ipcTotal=' in load and 'hits=' in load and 'ms=' in load)
ck('B07 no TTL / timer in the cache path',
   'TTL' not in code_only(CB) and 'postDelayed' not in CB
   and 'System.currentTimeMillis()' not in CB)

# ---- C. process-level bitmap cache ---------------------------------------
ck('C01 static cache declared with name + counters',
   'private static volatile String sImageCacheName;' in BD
   and 'private static volatile Bitmap sImageCache;' in BD
   and 'sDecodeHits' in BD and 'sDecodeMisses' in BD)
ck('C02 decode() checks the cache BEFORE any IPC',
   decode.find('if (hit != null') < decode.find('ConfigBus.media(name)'))
ck('C03 cache hit does not open any stream',
   'openInputStream' not in decode[:decode.find('currentFromCache = false;')]
   if 'currentFromCache = false;' in decode else False)
ck('C04 successful decode stores name + Bitmap',
   'sImageCacheName = name;' in decode and 'sImageCache = bmp;' in decode)
ck('C05 shared Bitmap is NEVER recycled by an Activity',
   'currentFromCache' in recycle
   and '!currentFromCache' in recycle
   and 'currentFromCache = false;' in recycle)
ck('C06 showImage() marks the shared flag after recycle()',
   'currentFromCache = (bmp == sImageCache && bmp != null);' in BD)
ck('C07 screen snapshot is still freed per Activity',
   's.recycle()' in recycle)
ck('C08 objective counters: cache=hit/miss, ms, sample, size',
   'backdrop decode cache=hit' in decode
   and 'backdrop decode cache=miss' in decode
   and 'sample=' in decode and 'size=' in decode)

# ---- D. power: no new frame work -----------------------------------------
ck('D01 requestRender() only on a real change',
   'if (force || slotChanged || versionChanged || glassChanged'
   in refresh
   and '|| bgChanged' in refresh
   and refresh[refresh.find('if (force || slotChanged'):]
       .count('requestRender();') == 1)
ck('D02 config broadcast still repaints (versionChanged in the gate)',
   'versionChanged' in refresh[refresh.find('if (force ||'):])
ck('D03 NO new timer/post/invalidation loop in the frame path',
   'onSurfaceTextureUpdated' in BD
   and 'postDelayed' not in code_only(method_body(
       BD, 'private boolean drawLiveVideoRegion('))
   and 'captureVideoFrame' not in ALL_CODE)
ck('D04 idle-frame scan skip (p63) still present',
   'SCAN_INTERVAL_IDLE_MS' in RC and 'no scan at all' in RC)
ck('D05 debug log gate (p63) still present',
   'startupLine(msg)' in GE and 'sVerbose' in GE)
ck('D06 no new per-frame Bitmap allocation',
   'createBitmap' not in method_body(BD, 'private boolean drawLiveVideoRegion(')
   and 'createBitmap' not in method_body(BD, 'public boolean drawRegion(Canvas c,'))

# ---- E. L1 / frozen render protection ------------------------------------
ck('E01 drawRegion() == p75 byte-for-byte',
   method_body(BD, 'public boolean drawRegion(Canvas c,')
   == method_body(BD75, 'public boolean drawRegion(Canvas c,')
   and bool(method_body(BD, 'public boolean drawRegion(Canvas c,')))
ck('E02 snapshot() == p75 byte-for-byte',
   method_body(BD, 'private Bitmap snapshot()')
   == method_body(BD75, 'private Bitmap snapshot()'))
for f in ('MaterialBridge.java', 'LiquidGlassView.java', 'LiquidShader.java'):
    ck('E03 ' + f + ' == L1-ACCEPTED-p30 baseline',
       open(os.path.join(SRC, f), 'rb').read()
       == open(os.path.join(BASE, f), 'rb').read())
CHANGED = {'BackdropLayer.java', 'ConfigBus.java', 'GlassEntry.java',
           'RenderController.java'}
mismatch = []
for f in sorted(os.listdir(SRC)):
    if not f.endswith('.java'):
        continue
    o = os.path.join(ARC, f[:-5] + '-p75-current.java')
    if not os.path.isfile(o):
        mismatch.append(f + ' (no p75 archive)')
    elif read(os.path.join(SRC, f)) != read(o) and f not in CHANGED:
        mismatch.append(f)
ck('E04 only the four intended sources differ from p75', not mismatch)
ck('E05 SettingsActivity == p75 (settings untouched)',
   read(os.path.join(SRC, 'SettingsActivity.java'))
   == read(os.path.join(ARC, 'SettingsActivity-p75-current.java')))

# ---- F. version truth ------------------------------------------------------
prop = read(os.path.join(ROOT, 'app', 'src', 'main', 'resources', 'META-INF',
                         'xposed', 'module.prop'))
ck('F01 module.prop version=0.5.0-p76', 'version=0.5.0-p76' in prop)
ck('F02 module.prop versionCode=104', 'versionCode=104' in prop)
ck('F03 BUILD == 0.5.0-p76',
   'public static final String BUILD = "0.5.0-p76";' in GE)

print()
if FAILS:
    print('ASSERT_P76 FAILED: %d' % len(FAILS))
    for f in FAILS:
        print('  - ' + f)
    sys.exit(1)
print('ASSERT_P76 OK: all checks passed')
