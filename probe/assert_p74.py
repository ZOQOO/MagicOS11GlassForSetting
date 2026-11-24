#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""assert_p74.py -- deterministic (no device) invariants for 0.5.0-p74.

User request: "和之前修复图片背景时一样L2返回L1时需要一个过渡背景，
现在背景是视频时L2返回L1的时候也需要过渡背景，我认为这时候可以使用
当前视频背景的一个模糊快照来作为过渡，当然是使用功耗消耗最少的方式最好。"

Image-era fix (p43/p66, findings §42/43/53): WindowBackdropDrawable draws an
opaque base + the p20 fixed screen snapshot via drawRegion(0,0,w,h), so the
outgoing transition shows the same wallpaper. A VIDEO has no CPU snapshot
(p73 uses a GPU RenderNode), and release(false) stops the player / hides the
TextureView, so the window falls back to a flat colour.

p74 freezes ONE low-resolution video frame at release(false), BEFORE
stopVideo()/GONE, and WindowBackdropDrawable upscales it with a filtered
Paint (bilinear magnification IS the blur). This is a STATIC one-shot
transition picture, NOT the p69-p71 continuous CPU-sampling dead end.

Checks:
  A. One-shot capture is present, bounded and off the draw path.
  B. The blur is implicit (small frame + FILTER_BITMAP), no effect chain.
  C. Image / L1 / p73 paths byte-identical; drawRegion untouched.
  D. Version truth: BUILD 0.5.0-p74, module.prop code 102.
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
BD73 = read(os.path.join(ARC, 'BackdropLayer-p73-current.java'))
GE = read(os.path.join(SRC, 'GlassEntry.java'))
GE73 = read(os.path.join(ARC, 'GlassEntry-p73-current.java'))
ALL = ''.join(read(os.path.join(SRC, f)) for f in os.listdir(SRC)
              if f.endswith('.java'))
ALL_CODE = code_only(ALL)

# ---- A. one-shot capture --------------------------------------------------
cap = method_body(BD, 'private void captureTransitionFrame()')
ck('A01 captureTransitionFrame() body non-empty', bool(cap))
ck('A02 getBitmap appears EXACTLY once in all app sources',
   ALL_CODE.count('.getBitmap(') == 1)
ck('A03 getBitmap is inside the one-shot capture method',
   '.getBitmap(' in cap)
ck('A04 capture called from exactly ONE site',
   len(re.findall(r'(?<!private void )captureTransitionFrame\(\);', ALL_CODE)) == 1)
rel = method_body(BD, 'public void release(boolean restoreOem)')
ck('A05 capture is in the FINISHING (restoreOem=false) branch',
   'if (wasVideo) captureTransitionFrame();' in rel
   and rel.find('if (wasVideo) captureTransitionFrame();') < rel.find('stopVideo();'))
ck('A06 capture happens BEFORE video is hidden',
   rel.find('if (wasVideo) captureTransitionFrame();')
   < rel.find('video.setVisibility(View.GONE);'))
ck('A07 wasVideo is read BEFORE videoMode is cleared',
   'boolean wasVideo = videoMode;' in rel
   and rel.find('boolean wasVideo = videoMode;') < rel.find('videoMode = false;'))
ck('A08 guard: needs a live SurfaceTexture + VISIBLE video',
   'surface == null' in cap and 'video.getVisibility() != View.VISIBLE' in cap)
ck('A09 small edge cap (lowest power)',
   'private static final int TRANSITION_MAX_EDGE = 320;' in BD
   and 'TRANSITION_MAX_EDGE / Math.max(vw, vh)' in cap)
ck('A10 NO continuous capture: frame callback still empty',
   re.sub(r'//[^\n]*', '',
          re.sub(r'/\*.*?\*/', '',
                 method_body(BD, 'public void onSurfaceTextureUpdated(SurfaceTexture st)'),
                 flags=re.S)).replace(' ', '').replace('\n', '') == '{}')
ck('A11 no timer / post / invalidation loop in the capture',
   'post(' not in cap and 'postDelayed' not in cap
   and 'invalidate()' not in cap and 'while (' not in cap)
ck('A12 failure falls back to the p43 opaque base, never black',
   'sTransitionFail++' in cap and 'backdrop TRANSITION fail=' in cap)
ck('A13 one-shot log with ok/fail counters',
   'backdrop TRANSITION snap=once' in cap and 'ok=' in cap)

# ---- B. implicit blur, no effect chain -----------------------------------
draw = method_body(BD, 'public void draw(Canvas c)')
ck('B01 WindowBackdropDrawable.draw() body non-empty', bool(draw))
ck('B02 uses the one-shot frame when present',
   'Bitmap t = transitionFrame;' in draw
   and 't != null && !t.isRecycled()' in draw)
ck('B03 FILTER_BITMAP upscaling IS the blur',
   'new Paint(Paint.FILTER_BITMAP_FLAG)' in BD
   and 'c.drawBitmap(t, 0f, 0f, transitionPaint);' in draw)
ck('B04 same ScaleMode filling as the live video',
   'ScaleMode.matrix(scaleMode, scaleZoom,' in draw
   and 't.getWidth(), t.getHeight(), w, h' in draw)
ck('B05 image/L1 still uses the p66 fixed snapshot',
   'drawRegion(c, 0, 0, w, h);' in draw)
ck('B06 opaque base + tint/dim unchanged',
   'c.drawColor(windowBgColor);' in draw
   and 'if (windowTintARGB != 0) c.drawColor(windowTintARGB);' in draw)
ck('B07 NO blur RenderEffect / shader / createBitmap on transition draw',
   'createBlurEffect' not in draw and 'createRuntimeShaderEffect' not in draw
   and 'createBitmap' not in draw and 'recycle()' not in draw)

# ---- C. byte-identical historical protection ------------------------------
dr = method_body(BD, 'public boolean drawRegion(Canvas c,')
dr73 = method_body(BD73, 'public boolean drawRegion(Canvas c,')
ck('C01 drawRegion() == p73 byte-for-byte (untouched)', dr == dr73 and bool(dr))
for f in ('MaterialBridge.java', 'LiquidGlassView.java', 'LiquidShader.java'):
    ck('C02 ' + f + ' == L1-ACCEPTED-p30 baseline',
       open(os.path.join(SRC, f), 'rb').read()
       == open(os.path.join(BASE, f), 'rb').read())
CHANGED = {'BackdropLayer.java', 'GlassEntry.java'}
mismatch = []
for f in sorted(os.listdir(SRC)):
    if not f.endswith('.java'):
        continue
    o = os.path.join(ARC, f[:-5] + '-p73-current.java')
    if not os.path.isfile(o):
        mismatch.append(f + ' (no p73 archive)')
    elif read(os.path.join(SRC, f)) != read(o) and f not in CHANGED:
        mismatch.append(f)
ck('C03 only BackdropLayer / GlassEntry(BUILD) differ from p73', not mismatch)
ck('C04 RenderController == p73 (no repaint wiring added)',
   read(os.path.join(SRC, 'RenderController.java'))
   == read(os.path.join(ARC, 'RenderController-p73-current.java')))
ck('C05 SettingsActivity == p73 (settings untouched)',
   read(os.path.join(SRC, 'SettingsActivity.java'))
   == read(os.path.join(ARC, 'SettingsActivity-p73-current.java')))

# ---- D. version truth -------------------------------------------------------
prop = read(os.path.join(ROOT, 'app', 'src', 'main', 'resources', 'META-INF',
                         'xposed', 'module.prop'))
ck('D01 module.prop version=0.5.0-p74', 'version=0.5.0-p74' in prop)
ck('D02 module.prop versionCode=102', 'versionCode=102' in prop)
ck('D03 BUILD == 0.5.0-p74',
   'public static final String BUILD = "0.5.0-p74";' in GE)
ck('D04 GlassEntry differs from p73 ONLY in BUILD string',
   GE.replace('0.5.0-p74', '0.5.0-p73') == GE73)

# ---- E. lifecycle / criteria ---------------------------------------------
ck('E01 real detach frees the one-shot frame',
   'releaseTransitionFrame();' in rel
   and 'private void releaseTransitionFrame()' in BD)
ck('E02 finishing keeps the frame for the outgoing window',
   'releaseTransitionFrame();' not in
   rel[rel.find('} else {'):] if '} else {' in rel else False)
ck('E03 CPU capture stays out of the glass shader path',
   'captureVideoFrame' not in ALL_CODE
   and 'bakeVideoFrameIntoPlate' not in ALL_CODE
   and 'VIDEO_CAPTURE' not in ALL_CODE)
ck('E04 one-shot boundary documented in code (not the §58 dead end)',
   'NOT the p69-p71 CPU-sampling dead end' in BD
   or 'p69-p71 CPU-sampling dead end' in BD)
ck('E05 image-era transition sources documented',
   'p43/p66' in BD and 'WindowBackdropDrawable' in BD)

print()
if FAILS:
    print('ASSERT_P74 FAILED: %d' % len(FAILS))
    for f in FAILS:
        print('  - ' + f)
    sys.exit(1)
print('ASSERT_P74 OK: all checks passed')
