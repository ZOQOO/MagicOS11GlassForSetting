#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""assert_p80.py -- deterministic (no device) invariants for 0.5.0-p80.

User (verbatim): "还是没有改好，首帧是不是可以直接向背景设置调用背景图片？
账号中心的白块尝试寻找之前WLAN 蓝牙标题栏修改的方法进行尝试。还是先改这两项。"

Scope: EXACTLY two items, other files byte-identical to p79.
Device offline (adb -s A4UF6R6116003189 -> device not found): no visual claim.

Prior evidence used (not guesses):
  §45.3  real-device log: 3x "backdrop FAIL why=1 ... snap=0x0" ON ENTRY —
         the background screen snapshot does not exist yet, so the window
         could only paint windowBgColor and then jumped to the wallpaper.
  p64 §5  the accepted WLAN/蓝牙 fix = stripRowsIn()'s bounded first-frame
         walk + isOpaqueSolidFill() measured property + saveAndClear() latch.
  p64 §6  p64's OWN falsifiable candidate, never implemented before:
         "若 bg=StateListDrawable/op=-3 且白条仍在 ... 下一版需要按 Drawable
          实测颜色再扩展". 账号中心 is a separate app whose header uses such
          a Drawable, so every earlier getOpacity() gate skipped it.
  p55    full-screen-width structure (ratio, no absolute screen-Y threshold).
  p66/p74/p75  WindowBackdropDrawable / transitionFrame are verified paths;
         the new picture is used ONLY when drawRegion() fails.

Checks: A version, B first-frame real background picture, C p64 method +
measured colour, D L1 freeze / untouched files, E criteria + dead ends,
F no new frame-path work.
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
    return read(os.path.join(ARC, name[:-5] + '-p79-current.java'))


GE = f('GlassEntry.java')
CS = f('ConfigStore.java')
BD = f('BackdropLayer.java')
RC = f('RenderController.java')
MP = f('ModuleProvider.java')
CB = f('ConfigBus.java')
PROP_TXT = read(PROP)
ALL_CODE = code_only(''.join(f(n) for n in sorted(os.listdir(SRC))
                             if n.endswith('.java')))

derive = method_body(CS, 'public static String deriveWindowColor(')
ensure_preview = method_body(BD, 'private void ensureWindowPreview(')
win_draw = method_body(BD, 'public void draw(Canvas c)')
apply_body = method_body(BD, 'public void apply(ConfigContract.Slot slot,')
measured = method_body(RC, 'private static boolean measuredOpaqueWhite(')
strip_rows = method_body(RC, 'private void stripRowsIn(')
gate = method_body(RC, 'private boolean accountIdGate()')
pre = method_body(RC, 'private boolean onPreDraw()')
sync = method_body(RC, 'private void syncRows(')

# ---- A. version truth -----------------------------------------------------
ck('A01 GlassEntry.BUILD == 0.5.0-p80',
   'public static final String BUILD = "0.5.0-p80";' in GE)
ck('A02 module.prop version == 0.5.0-p80', 'version=0.5.0-p80' in PROP_TXT)
ck('A03 module.prop versionCode == 108', 'versionCode=108' in PROP_TXT)
ck('A04 p79 marker gone from module.prop', '0.5.0-p79' not in PROP_TXT)
ck('A05 BUILD and module.prop agree',
   '0.5.0-p80' in GE and '0.5.0-p80' in PROP_TXT)

# ---- B. first frame: the REAL background picture --------------------------
ck('B01 preview file name is a fixed, path-safe constant',
   'WINDOW_PREVIEW_NAME = "window_preview.png"' in CS
   and '..' not in 'window_preview.png')
ck('B02 preview is derived from the SAME frame for image AND video',
   'frame = mmr.getFrameAtTime(0)' in derive
   and 'frame = BitmapFactory.decodeStream(in, null, opts)' in derive
   and 'Bitmap small' in derive)
ck('B03 preview is downscaled to <= 320 px longest edge',
   'WINDOW_PREVIEW_MAX_EDGE = 320' in CS
   and '(float) WINDOW_PREVIEW_MAX_EDGE / Math.max(fw, fh)' in derive)
ck('B04 preview is written as ONE PNG next to the media files',
   'small.compress(Bitmap.CompressFormat.PNG, 90, os)' in derive
   and 'new File(f.getParentFile(), WINDOW_PREVIEW_NAME)' in derive)
ck('B05 temporary scaled Bitmap is recycled',
   'if (small != frame) small.recycle();' in derive)
ck('B06 default-visible write log',
   'mg-state windowPreview wrote=' in derive)
ck('B07 host loads it through the EXISTING media provider',
   bool(ensure_preview)
   and 'ConfigBus.media(ConfigStore.WINDOW_PREVIEW_NAME)' in ensure_preview
   and 'getContentResolver().openInputStream' in ensure_preview)
ck('B08 host loads it exactly ONCE per process',
   'if (sWindowPreviewLoaded) return;' in ensure_preview
   and 'sWindowPreviewLoaded = true;' in ensure_preview)
ck('B09 it is loaded in apply() BEFORE the first draw traversal',
   'ensureWindowPreview();' in apply_body
   and apply_body.find('clearWindowBackground();')
       < apply_body.find('ensureWindowPreview();'))
ck('B10 window still paints an opaque base first (p42 no-black rule)',
   'c.drawColor(windowBgColor);' in win_draw)
ck('B11 the real picture is used ONLY when drawRegion() fails',
   '} else if (!drawRegion(c, 0, 0, w, h)) {' in win_draw
   and 'Bitmap p = sWindowPreview;' in win_draw
   and 'c.drawBitmap(p, null, transitionDst, transitionPaint);' in win_draw)
ck('B12 p74/p75 transitionFrame keeps TOP priority',
   win_draw.find('Bitmap t = transitionFrame;') < win_draw.find('} else if (!drawRegion')
   and 'c.drawBitmap(t, null, transitionDst, transitionPaint);' in win_draw)
ck('B13 tint overlay and p66 structure survive',
   'if (windowTintARGB != 0) c.drawColor(windowTintARGB);' in win_draw
   and 'getOpacity()' in BD)
ck('B14 default-visible load log',
   'mg-state windowPreview load=' in ensure_preview)
ck('B15 preview never enters the glass sampling chain',
   'sWindowPreview' not in method_body(BD, 'public boolean drawRegion(Canvas c,')
   and 'sWindowPreview' not in method_body(BD, 'private Bitmap snapshot()'))
ck('B16 no new device-side IPC mechanism / file write',
   'XResources' not in ALL_CODE
   and 'openOutputStream' not in ALL_CODE)
ck('B17 upgrade installs are detected by preview-file presence',
   'public static boolean hasWindowPreview(Context c)' in CS
   and 'WINDOW_PREVIEW_NAME' in method_body(CS, 'public static boolean hasWindowPreview('))
ck('B18 p78 lazy derivation also fires when ONLY the preview is missing',
   '|| !ConfigStore.hasWindowPreview(getContext())' in MP)
ck('B19 ensureWindowColor() covers the preview-only upgrade case',
   'boolean havePreview = hasWindowPreview(c);'
   in method_body(CS, 'public static boolean ensureWindowColor(')
   and 'deriveWindowColor(f, kind);'
   in method_body(CS, 'public static boolean ensureWindowColor('))
ck('B20 preview-only repair writes no config and sends no broadcast',
   'mg-state windowColour derived=preview-only' in CS
   and 'notifyChanged' not in method_body(CS, 'public static boolean ensureWindowColor('))

# ---- C. 账号中心: p64's method + p64 §6 measured candidate ---------------
ck('C01 measuredOpaqueWhite body is non-empty', bool(measured))
ck('C02 paints into a 1x1 Bitmap and reads the pixel',
   'Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)' in measured
   and 'new Canvas(b)' in measured and 'b.getPixel(0, 0)' in measured)
ck('C03 p56 near-white + opaque alpha rule',
   '(px >>> 24) != 0xFF' in measured
   and 'r >= 0xE0 && g >= 0xE0 && bl >= 0xE0' in measured)
ck('C04 temporary Bitmap is always recycled',
   'finally' in measured and 'b.recycle();' in measured)
ck('C05 candidate is enabled ONLY for 账号中心',
   'if (!opaque && accountChrome)' in strip_rows
   and 'measuredOpaqueWhite(chromeBg)' in strip_rows)
ck('C06 Settings L2/L3 keep p64\'s original predicate',
   'boolean opaque = isOpaqueSolidFill(chromeBg);' in strip_rows)
ck('C07 p64 structure-first: full-screen width, measurement INSIDE',
   'boolean accountTopCard = accountChrome && isFullWidthOem(v);'
   in strip_rows
   and 'isChromeClass(cls) || accountTopCard' in strip_rows)
ck('C08 p64 action: saveAndClear() latch + revive/restore',
   'saveAndClear(v);' in strip_rows
   and 'restripLatched' in RC and 'restoreRows' in RC)
ck('C09 fading white effect is still protected',
   '!accountChrome || !isFadeWhiteLayer(v)' in strip_rows)
ck('C10 p64 §6 asked for Drawable type + opacity in the log',
   'mg-state ACCOUNTCAND cls=' in strip_rows
   and 'bg=" + drawableDesc(chromeBg)' in strip_rows
   and 'opSolid=' in strip_rows and 'measured=' in strip_rows)
ck('C11 candidate line is throttled (never per frame)',
   'now - lastAccountCandLogMs > 2000L' in strip_rows
   and 'lastAccountCandLogMs = now;' in strip_rows)
ck('C12 successful strip proves whether the pixel test was needed',
   'mg-state ACCOUNTSTRIP cls=' in strip_rows
   and '+ " measured=" + measured' in strip_rows)
ck('C13 account gate is still EXT_ID + generic (L1 no-op)',
   'return genericEnabled && slot == ConfigContract.Slot.EXT_ID;' in gate)
ck('C14 pixel test is NOT on the per-frame syncRows path',
   'measuredOpaqueWhite' not in sync and 'measuredOpaqueWhite' not in pre)

# ---- D. L1 freeze / "还是先改这两项" -------------------------------------
for name in ('MaterialBridge.java', 'LiquidGlassView.java', 'LiquidShader.java'):
    ck('D01 %s == p30 baseline (bytes)' % name,
       open(os.path.join(SRC, name), 'rb').read()
       == open(os.path.join(BASE, name), 'rb').read())
for name, sig in [('drawRegion()', 'public boolean drawRegion(Canvas c,'),
                  ('snapshot()', 'private Bitmap snapshot()'),
                  ('recycle()', 'private void recycle()'),
                  ('ensurePlateSnapshot()', 'private void ensurePlateSnapshot()'),
                  ('drawLiveVideoRegion()', 'private boolean drawLiveVideoRegion(')]:
    a, b = method_body(BD, sig), method_body(arc('BackdropLayer.java'), sig)
    ck('D02 %s == p79 and non-vacuous' % name, bool(a and b) and a == b)
for name in ('ConfigContract.java', 'SettingsActivity.java', 'ScaleMode.java',
             'EdgeFade.java', 'CardScanner.java',
             'OemCardSuppressor.java', 'PageLevel.java', 'Scope.java',
             'ConfigBus.java'):
    ck('D03 %s == p79' % name,
       os.path.isfile(os.path.join(ARC, name[:-5] + '-p79-current.java'))
       and f(name) == arc(name))
ck('D04 GlassEntry differs from p79 ONLY in BUILD',
   GE.replace('0.5.0-p80', '0.5.0-p79') == arc('GlassEntry.java'))
diff = []
for n in sorted(os.listdir(SRC)):
    if n.endswith('.java'):
        p = os.path.join(ARC, n[:-5] + '-p79-current.java')
        if not os.path.isfile(p) or f(n) != read(p):
            diff.append(n)
ck('D05 exactly 5 sources differ from p79 (all inside the two items)',
   diff == ['BackdropLayer.java', 'ConfigStore.java', 'GlassEntry.java',
            'ModuleProvider.java', 'RenderController.java'])
ck('D06 all 18 p79 snapshots exist',
   len([n for n in os.listdir(ARC) if n.endswith('-p79-current.java')]) == 18)
ck('D07 p79 skip-hook coverage is NOT rolled back',
   'ShellTaskOrganizer' in GE and 'hookSkips' in GE)
ck('D08 p79 provider-derived window colour is NOT rolled back',
   'sWindowColorFromProvider' in CB)
ck('D09 p78 lazy colour derivation is NOT rolled back',
   'ConfigStore.ensureWindowColor(getContext())' in MP)

# ---- E. criteria / dead ends ---------------------------------------------
ck('E01 CPU video-capture dead end stays absent',
   'captureVideoFrame' not in ALL_CODE
   and 'VIDEO_CAPTURE_INTERVAL_MS' not in ALL_CODE)
ck('E02 p74/p75 one-shot transition frame survives',
   'TRANSITION snap=once' in BD and 'video.getTransform(null)' in BD)
ck('E03 p73 GPU live layer survives',
   'drawLiveVideoRegion' in BD and 'drawRenderNode' in BD)
ck('E04 mg-build / mg-state channels survive',
   'say("mg-build " + BUILD' in GE and 'msg.startsWith("mg-state ")' in GE)
ck('E05 video hard gate survives', 'if (videoMode && !released)' in BD)
ck('E06 settings three sliders survive (SettingsActivity untouched)',
   f('SettingsActivity.java') == arc('SettingsActivity.java'))

# ---- F. no new frame-path work -------------------------------------------
ck('F01 no mg-state line inside onPreDraw()', 'mg-state ' not in pre)
ck('F02 no preview state line inside drawRegion/snapshot/plate',
   all('mg-state ' not in method_body(BD, s)
       for s in ('public boolean drawRegion(Canvas c,',
                 'private Bitmap snapshot()',
                 'private void ensurePlateSnapshot()')))
ck('F03 preview read happens once, outside the draw method',
   'getContentResolver()' not in win_draw
   and 'if (sWindowPreviewLoaded) return;' in ensure_preview)
ck('F04 new preview path adds no timer/post (p63 rotate timer is pre-existing)',
   'postDelayed(' not in code_only(ensure_preview)
   and 'new Thread(' not in code_only(ensure_preview)
   and 'postDelayed(' not in code_only(win_draw))
ck('F05 preview is a single small static Bitmap, never per-frame allocated',
   BD.count('BitmapFactory.decodeStream(in, null, o)') == 1
   and 'private static volatile Bitmap sWindowPreview;' in BD)

print('\n%d checks, %d failures' % (CHECKS[0], len(FAILS)))
if FAILS:
    print('FAILED:')
    for x in FAILS:
        print('  - ' + x)
    sys.exit(1)
print('ALL_GREEN')
sys.exit(0)
