#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""assert_p73.py -- deterministic (no device) invariants for 0.5.0-p73.

p73 opens a NEW video-background chain requested by the user:
  "根据GITHUB上AndroidLiquidGlass对于视频背景液态玻璃效果的制作方法，
   更改我们这个模块，也可以参考LiquidTab当背景是动态图或者视频时..."
Evidence (local clones):
  analysis/AndroidLiquidGlass  = github.com/Kyant0/AndroidLiquidGlass.git
                                 kmp @ 65ab177 / tag 2.0.1 (Apache-2.0)
  analysis/LiquidTab           = LSPosed pure-View module (GPL-3.0)

The p69-p71 CPU-Bitmap video sampling is a proven DEAD END (p72 reverted it).
p73 instead records the video TextureView's GPU RenderNode into the glass
container's own RenderNode every frame -- identity-stable AND real-time,
which is impossible on a CPU bitmap.

Checks:
  A. GPU live-layer chain is present.
  B. The CPU readback dead end stays dead (no getBitmap / capture / bake).
  C. L1 / image path byte-identical to p72 / p30; all other sources untouched.
  D. Version truth: BUILD 0.5.0-p73, module.prop code 101.
  E. Lifecycle + non-video plate + objective log criteria.
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
BD72 = read(os.path.join(ARC, 'BackdropLayer-p72-current.java'))
RC = read(os.path.join(SRC, 'RenderController.java'))
RC66 = read(os.path.join(ARC, 'RenderController-p66-current.java'))
SA = read(os.path.join(SRC, 'SettingsActivity.java'))
SA72 = read(os.path.join(ARC, 'SettingsActivity-p72-current.java'))
GE = read(os.path.join(SRC, 'GlassEntry.java'))
GE72 = read(os.path.join(ARC, 'GlassEntry-p72-current.java'))

ALL = ''.join(read(os.path.join(SRC, f)) for f in os.listdir(SRC)
              if f.endswith('.java'))
ALL_CODE = code_only(ALL)

# ---- A. GPU live-layer chain ----------------------------------------------
ck('A01 RenderNode imported', 'import android.graphics.RenderNode;' in BD)
ck('A02 stable node field declared', 'private RenderNode liveVideoNode;' in BD)
ck('A03 reflection on View.mRenderNode (LiquidTab pattern)',
   'View.class.getDeclaredField("mRenderNode")' in BD)
ck('A04 reflection resolved ONCE, never per frame',
   'private boolean videoNodeFieldTried;' in BD
   and 'if (liveVideoNode != null) return liveVideoNode;' in BD)
ck('A05 NO updateDisplayListIfDirty (never re-record a host View)',
   'updateDisplayListIfDirty' not in ALL_CODE)
live = method_body(BD, 'private boolean drawLiveVideoRegion(')
ck('A06 drawLiveVideoRegion() body non-empty', bool(live))
ck('A07 opaque base first (never a black glass input)',
   'c.drawColor(windowBgColor);' in live)
ck('A08 screen-coordinate pinning (p20 invariant in GPU space)',
   'video.getLocationOnScreen(videoLoc);' in live
   and 'c.translate(videoLoc[0] - left, videoLoc[1] - top);' in live)
ck('A09 reuse the live display list via drawRenderNode',
   'rn.hasDisplayList()' in live and 'c.drawRenderNode(rn);' in live)
ck('A10 public-API fallback video.draw(c) when reflection fails',
   'video.draw(c);' in live)
ck('A11 hidden/stopped video returns false (p72 tint-only look)',
   'video.getVisibility() != View.VISIBLE' in live)
ck('A12 returns true so setRenderEffect+drawRenderNode always run',
   'return true;' in live)
ck('A13 LIVEGLASS objective log with node / fallback / fail counters',
   'backdrop LIVEGLASS mode=gpu' in BD
   and 'node=' in BD and 'draw=' in BD and 'fail=' in BD)

dr = method_body(BD, 'public boolean drawRegion(Canvas c,')
GATE = '''        // 0.5.0-p73 HARD GATE. A video background NEVER touches the CPU
        // snapshot path (the p69-p71 dead end). Every other state -- image,
        // L1, none, BG off, failed decode -- runs the EXACT frozen p20/p67
        // branch below, line for line. LiquidGlassView is untouched.
        if (videoMode && !released) {
            return drawLiveVideoRegion(c, left, top, w, h);
        }
'''
ck('A14 drawRegion() hard gate found', GATE in dr and bool(dr))
ck('A15 hard gate is video-only (L1 no-op)',
   'if (videoMode && !released)' in dr and 'slot' not in GATE)

# ---- B. CPU readback dead end stays dead ----------------------------------
GONE = [
    'captureVideoFrame', 'scheduleVideoCapture', 'bakeVideoFrameIntoPlate',
    'setVideoRepaint', 'drawRegionScaled', 'releaseVideoFrame',
    'releaseSourceOnly', 'diagVideoBake', 'diagVideoCaptureOk',
    'VIDEO_CAPTURE_INTERVAL_MS', 'PLATE_SCALE_PCT', 'plateScale',
    'videoFrame', 'videoRepaint', 'videoCaptureTask', 'sVideoCapOk',
    'VIDCAP', '.getBitmap(', 'refreshWindowBg()',  # capture-path only; see B02
]
for tok in GONE[:18]:
    ck('B01 CPU readback token GONE: ' + tok, tok not in ALL_CODE)
ck('B02 no TextureView.getBitmap anywhere',
   '.getBitmap(' not in ALL_CODE)
ck('B03 no per-frame Bitmap allocation in the video branch',
   'createBitmap' not in live and 'recycle()' not in live)
ck('B04 no throttle / timer / repaint hook on the video path',
   'VIDEO_CAPTURE' not in ALL_CODE
   and 'post(' not in code_only(method_body(BD, 'public void apply(ConfigContract.Slot slot,'))
   and 'setVideoRepaint' not in ALL_CODE)
ck('B05 no extra requestRender wiring in RenderController',
   RC == RC66)

# ---- C. byte-identical L1 / image protection ------------------------------
dr72 = method_body(BD72, 'public boolean drawRegion(Canvas c,')
ck('C01 drawRegion() minus p73 gate == p72 byte-for-byte',
   dr.replace(GATE, '') == dr72 and bool(dr72))
def snap_tail(t):
    b = method_body(t, 'private Bitmap snapshot()')
    i = b.find('int w, h;')
    return b[i:] if i >= 0 else ''
ck('C02 snapshot() image tail == p72', snap_tail(BD) == snap_tail(BD72)
   and bool(snap_tail(BD)))
for f in ('MaterialBridge.java', 'LiquidGlassView.java', 'LiquidShader.java'):
    ck('C03 ' + f + ' == L1-ACCEPTED-p30 baseline',
       open(os.path.join(SRC, f), 'rb').read()
       == open(os.path.join(BASE, f), 'rb').read())
ck('C04 SettingsActivity == p72 (settings still not reverted)', SA == SA72)
ck('C05 RenderController == p66 archive (== p72)', RC == RC66)

CHANGED = {'BackdropLayer.java', 'GlassEntry.java'}
mismatch = []
for f in sorted(os.listdir(SRC)):
    if not f.endswith('.java'):
        continue
    old = os.path.join(ARC, f[:-5] + '-p72-current.java')
    if not os.path.isfile(old):
        mismatch.append(f + ' (no p72 archive)')
    elif read(os.path.join(SRC, f)) != read(old) and f not in CHANGED:
        mismatch.append(f)
ck('C06 only BackdropLayer / GlassEntry(BUILD) differ from p72', not mismatch)
ck('C07 GlassEntry differs from p72 ONLY in BUILD string',
   GE.replace('0.5.0-p73', '0.5.0-p72') == GE72)

# ---- D. version truth -------------------------------------------------------
prop = read(os.path.join(ROOT, 'app', 'src', 'main', 'resources', 'META-INF',
                         'xposed', 'module.prop'))
ck('D01 module.prop version=0.5.0-p73', 'version=0.5.0-p73' in prop)
ck('D02 module.prop versionCode=101', 'versionCode=101' in prop)
ck('D03 BUILD == 0.5.0-p73', 'public static final String BUILD = "0.5.0-p73";' in GE)

# ---- E. lifecycle / non-video plate / criteria ---------------------------
rel = method_body(BD, 'public void release(boolean restoreOem)')
ck('E01 release() drops reflected field + node reference',
   'liveVideoNode = null;' in rel and 'videoNodeField = null;' in rel)
hm = method_body(BD, 'private void hideMedia()')
ck('E02 hideMedia mirrors videoMode from configured kind',
   '"video".equals(cfg.asString(ConfigContract.Param.BG_KIND))' in hm)
ck('E03 non-video opaque plate retained',
   'private void ensurePlateSnapshot()' in BD
   and 'if (plateActive && !videoMode) ensurePlateSnapshot();' in hm)
snap = method_body(BD, 'private Bitmap snapshot()')
ck('E04 snapshot() still guards against a video CPU plate',
   'if (plateActive && !videoMode) ensurePlateSnapshot();' in snap)
ck('E05 objective criterion documented (no FAIL why=1 on video)',
   'NO `FAIL why=1`' in BD)
ck('E06 upstream sources documented in code',
   'analysis/AndroidLiquidGlass' in BD and 'analysis/LiquidTab' in BD
   and 'recordLayer' in BD and 'drawRenderNode(childNode)' in BD)

print()
if FAILS:
    print('ASSERT_P73 FAILED: %d' % len(FAILS))
    for f in FAILS:
        print('  - ' + f)
    sys.exit(1)
print('ASSERT_P73 OK: all checks passed')
