#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""assert_p75.py -- deterministic (no device) invariants for 0.5.0-p75.

User: "这个过渡是对的，但是现在这个图不是现在背景的实时样子，因为新设置
视频背景后大小会拉伸错位，这个过渡没法对于当时的背景样子。"

p74 froze ONE video frame but RE-DERIVED its geometry at draw time with
ScaleMode.matrix(shrunkenFrame -> window bounds), while applyVideoTransform()
uses (intrinsic video size -> video View size). Those disagree whenever the
bounds differ from the View, and decisively right after picking a new video:
videoWidth/Height are still 0 until onPrepared(), so applyVideoTransform()
returns early (screen shows identity / the previous transform) while p74
still computes a fresh matrix from the View size. The frozen picture is
stretched and cannot match the live backdrop.

p75 does NOT reconstruct geometry: it reads the transform ACTUALLY in effect
(video.getTransform(null) -- the remedy recorded in p69_report.txt §3 after
verifying AOSP + real-device reports that getBitmap() ignores setTransform()),
captures at the VIDEO VIEW aspect ratio and bakes that live matrix into a
small Bitmap which is a scaled copy of the on-screen picture.
WindowBackdropDrawable then only magnifies it 1:1.

Checks:
  A. Live transform is used; NO matrix recomputation remains.
  B. One-shot / lowest-power contract is preserved.
  C. Image / L1 / p74 paths byte-identical; drawRegion untouched.
  D. Version truth: BUILD 0.5.0-p75, module.prop code 103.
  E. Lifecycle + objective log criteria.
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
    """Brace-balanced body (including outer braces) of ONE method."""
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


BD = read(os.path.join(SRC, 'BackdropLayer.java'))
BD74 = read(os.path.join(ARC, 'BackdropLayer-p74-current.java'))
GE = read(os.path.join(SRC, 'GlassEntry.java'))
GE74 = read(os.path.join(ARC, 'GlassEntry-p74-current.java'))
ALL = ''.join(read(os.path.join(SRC, f)) for f in os.listdir(SRC)
              if f.endswith('.java'))
ALL_CODE = code_only(ALL)

cap = method_body(BD, 'private void captureTransitionFrame()')
draw = method_body(BD, 'public void draw(Canvas c)')
rel = method_body(BD, 'public void release(boolean restoreOem)')

# ---- A. live transform, no geometry reconstruction ------------------------
ck('A01 captureTransitionFrame() body non-empty', bool(cap))
ck('A02 uses the ACTUAL TextureView transform',
   'video.getTransform(null)' in cap)
ck('A03 capture does NOT recompute ScaleMode.matrix',
   'ScaleMode.matrix' not in cap)
ck('A04 WindowBackdropDrawable.draw() does NOT recompute any matrix',
   'ScaleMode.matrix' not in draw and bool(draw))
ck('A05 draw only magnifies 1:1 into window bounds',
   'transitionDst.set(0, 0, w, h);' in draw
   and 'c.drawBitmap(t, null, transitionDst, transitionPaint);' in draw)
ck('A06 capture uses the VIDEO VIEW aspect, not intrinsic video size',
   'int viewW = video.getWidth();' in cap
   and 'int viewH = video.getHeight();' in cap
   and 'Math.max(viewW, viewH)' in cap)
ck('A07 capture no longer depends on videoWidth/videoHeight',
   'videoWidth' not in cap and 'videoHeight' not in cap)
ck('A08 transform chain scale -> live -> inverse scale',
   cap.count('c.scale(') == 2 and 'c.concat(live)' in cap
   and cap.find('c.concat(live)') > cap.find('c.scale((float) tw / viewW')
   and cap.find('c.scale((float) viewW / tw') > cap.find('c.concat(live)'))
ck('A09 baked frame is a NEW bitmap; raw readback is recycled once',
   'Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)' in cap
   and 'raw.recycle()' in cap)
ck('A10 log carries the geometry for device verification',
   'view="' in cap and 'snap="' in cap and 'live="' in cap)
ck('A11 p75 rationale documented in code',
   'getTransform(null)' in BD and 'p74' in BD and 'p69_report.txt' in BD)

# ---- B. one-shot / lowest power ------------------------------------------
ck('B01 getBitmap appears EXACTLY once in all app sources',
   ALL_CODE.count('.getBitmap(') == 1)
ck('B02 getBitmap is inside the one-shot capture method',
   '.getBitmap(' in cap)
ck('B03 capture called from exactly ONE site',
   len(re.findall(r'(?<!private void )captureTransitionFrame\(\);', ALL_CODE)) == 1)
ck('B04 capture is in the FINISHING branch before stopVideo/GONE',
   'if (wasVideo) captureTransitionFrame();' in rel
   and rel.find('if (wasVideo) captureTransitionFrame();') < rel.find('stopVideo();')
   and rel.find('if (wasVideo) captureTransitionFrame();')
   < rel.find('video.setVisibility(View.GONE);'))
ck('B05 wasVideo read BEFORE videoMode is cleared',
   'boolean wasVideo = videoMode;' in rel
   and rel.find('boolean wasVideo = videoMode;') < rel.find('videoMode = false;'))
ck('B06 small edge cap retained',
   'private static final int TRANSITION_MAX_EDGE = 320;' in BD
   and 'TRANSITION_MAX_EDGE / Math.max(viewW, viewH)' in cap)
ck('B07 NO continuous capture: frame callback still empty',
   re.sub(r'//[^\n]*', '',
          re.sub(r'/\*.*?\*/', '',
                 method_body(BD, 'public void onSurfaceTextureUpdated(SurfaceTexture st)'),
                 flags=re.S)).replace(' ', '').replace('\n', '') == '{}')
ck('B08 no timer / post / invalidation loop',
   'post(' not in cap and 'postDelayed' not in cap
   and 'invalidate()' not in cap and 'while (' not in cap)
ck('B09 no blur effect / shader in transition draw',
   'createBlurEffect' not in draw and 'createRuntimeShaderEffect' not in draw)
ck('B10 failure keeps the p43 opaque base',
   'sTransitionFail++' in cap and 'backdrop TRANSITION fail=' in cap)
ck('B11 FILTER_BITMAP upscaling IS the blur',
   'new Paint(Paint.FILTER_BITMAP_FLAG)' in BD
   and 'transitionPaint' in draw)
ck('B12 opaque base + tint/dim unchanged',
   'c.drawColor(windowBgColor);' in draw
   and 'if (windowTintARGB != 0) c.drawColor(windowTintARGB);' in draw)

# ---- C. byte-identical historical protection ------------------------------
dr = method_body(BD, 'public boolean drawRegion(Canvas c,')
dr74 = method_body(BD74, 'public boolean drawRegion(Canvas c,')
ck('C01 drawRegion() == p74 byte-for-byte (untouched)', dr == dr74 and bool(dr))
for f in ('MaterialBridge.java', 'LiquidGlassView.java', 'LiquidShader.java'):
    ck('C02 ' + f + ' == L1-ACCEPTED-p30 baseline',
       open(os.path.join(SRC, f), 'rb').read()
       == open(os.path.join(BASE, f), 'rb').read())
CHANGED = {'BackdropLayer.java', 'GlassEntry.java'}
mismatch = []
for f in sorted(os.listdir(SRC)):
    if not f.endswith('.java'):
        continue
    o = os.path.join(ARC, f[:-5] + '-p74-current.java')
    if not os.path.isfile(o):
        mismatch.append(f + ' (no p74 archive)')
    elif read(os.path.join(SRC, f)) != read(o) and f not in CHANGED:
        mismatch.append(f)
ck('C03 only BackdropLayer / GlassEntry(BUILD) differ from p74', not mismatch)
ck('C04 RenderController == p74',
   read(os.path.join(SRC, 'RenderController.java'))
   == read(os.path.join(ARC, 'RenderController-p74-current.java')))
ck('C05 SettingsActivity == p74',
   read(os.path.join(SRC, 'SettingsActivity.java'))
   == read(os.path.join(ARC, 'SettingsActivity-p74-current.java')))
ck('C06 image/L1 still uses the p66 fixed snapshot',
   'drawRegion(c, 0, 0, w, h);' in draw)

# ---- D. version truth -------------------------------------------------------
prop = read(os.path.join(ROOT, 'app', 'src', 'main', 'resources', 'META-INF',
                         'xposed', 'module.prop'))
ck('D01 module.prop version=0.5.0-p75', 'version=0.5.0-p75' in prop)
ck('D02 module.prop versionCode=103', 'versionCode=103' in prop)
ck('D03 BUILD == 0.5.0-p75',
   'public static final String BUILD = "0.5.0-p75";' in GE)
ck('D04 GlassEntry differs from p74 ONLY in BUILD string',
   GE.replace('0.5.0-p75', '0.5.0-p74') == GE74)

# ---- E. lifecycle / criteria ---------------------------------------------
ck('E01 real detach frees the one-shot frame',
   'releaseTransitionFrame();' in rel
   and 'private void releaseTransitionFrame()' in BD)
ck('E02 CPU capture stays out of the glass shader path',
   'captureVideoFrame' not in ALL_CODE
   and 'bakeVideoFrameIntoPlate' not in ALL_CODE
   and 'VIDEO_CAPTURE' not in ALL_CODE)
ck('E03 getTransform stub present for real javac',
   'getTransform(android.graphics.Matrix m)' in
   read(os.path.join(ROOT, 'probe', 'compile_check.py')))

print()
if FAILS:
    print('ASSERT_P75 FAILED: %d' % len(FAILS))
    for f in FAILS:
        print('  - ' + f)
    sys.exit(1)
print('ASSERT_P75 OK: all checks passed')
