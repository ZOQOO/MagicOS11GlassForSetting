#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""assert_p72.py -- deterministic (no device) invariants for 0.5.0-p72.

p72 is a USER-ORDERED REVERT ("效果不行，还是退回之前p68那种样式，后台设置
不用回退"): the video background returns to the p68 behaviour -- NO glass
sampling at all (snapshot() -> null, drawRegion() false, tint-only glass) --
while the settings page keeps its p69/p71 dual-slider state.

Checks:
  A. The whole p69-p71 capture pipeline is GONE from every source file.
  B. The p68 video contract is in place (videoMode gate + recycle branch).
  C. The non-video p69 plate survives (none / BG off / failed decode).
  D. Byte-identical L1 / historical protection:
       - drawRegion() == p67 archive (the p68 state)
       - snapshot() image tail == p67 archive
       - RenderController == p66 archive (the p68 wiring state)
       - MaterialBridge / LiquidGlassView / LiquidShader == L1-ACCEPTED-p30
       - SettingsActivity == p71 archive (settings NOT reverted)
  E. Every other source file == its p71 archive (only BackdropLayer /
     RenderController / GlassEntry-BUILD differ).
  F. Version truth: BUILD 0.5.0-p72, module.prop code 100.
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
    """Strip block and line comments, keep string literals' text intact
    enough for token checks (we never strip inside quotes here because the
    tokens we test never appear inside string literals in the p71 sources)."""
    text = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
    return re.sub(r'//[^\n]*', '', text)


BD = read(os.path.join(SRC, 'BackdropLayer.java'))
BD67 = read(os.path.join(ARC, 'BackdropLayer-p67-current.java'))
BD71 = read(os.path.join(ARC, 'BackdropLayer-p71-current.java'))
RC = read(os.path.join(SRC, 'RenderController.java'))
RC66 = read(os.path.join(ARC, 'RenderController-p66-current.java'))
SA = read(os.path.join(SRC, 'SettingsActivity.java'))
SA71 = read(os.path.join(ARC, 'SettingsActivity-p71-current.java'))
GE = read(os.path.join(SRC, 'GlassEntry.java'))

# ---- A. capture pipeline is GONE from ALL sources --------------------------
ALL = ''.join(read(os.path.join(SRC, f)) for f in os.listdir(SRC)
              if f.endswith('.java'))
ALL_CODE = code_only(ALL)
GONE_TOKENS = [
    'captureVideoFrame', 'scheduleVideoCapture', 'bakeVideoFrameIntoPlate',
    'setVideoRepaint', 'drawRegionScaled', 'releaseVideoFrame',
    'releaseSourceOnly', 'diagVideoBake', 'diagVideoCaptureOk',
    'diagVideoCaptureFail', 'diagVideoAlloc',
    'VIDEO_CAPTURE_INTERVAL_MS', 'VIDEO_CAPTURE_MAX_EDGE',
    'VIDEO_CAPTURE_MAX_FAIL', 'PLATE_SCALE_PCT', 'plateScale',
    'videoFrame', 'videoRepaint', 'videoCapturePosted', 'videoCaptureTask',
    'videoCaptureDisabled', 'videoCaptureFail', 'lastVideoCaptureMs',
    'sVideoBake', 'sVideoCapOk', 'sVideoCapFail', 'VIDCAP',
    'Bitmap.getBitmap', '.getBitmap(',
]
for tok in GONE_TOKENS:
    ck('A0 pipeline token GONE from all sources: ' + tok,
       tok not in ALL_CODE)

# ---- B. the p68 video contract --------------------------------------------
snap = method_body(BD, 'private Bitmap snapshot()')
ck('B01 snapshot() body non-empty', bool(snap))
ck('B02 snapshot() gates the plate on !videoMode',
   'if (plateActive && !videoMode) ensurePlateSnapshot();' in snap)
app = method_body(BD, 'public void apply(ConfigContract.Slot slot,')
vi = app.find('if ("video".equals(kind))')
vend = app.find('return;', vi)
vbranch = app[vi:vend]
ck('B03 apply() video branch found', vi >= 0 and vend > vi)
ck('B04 video branch RECYCLES (p68: clears current + screenBmp)',
   'recycle();' in vbranch and 'releaseSourceOnly' not in vbranch)
ck('B05 video branch bakes NO plate',
   'ensurePlateSnapshot' not in vbranch)
ck('B06 video branch sets videoMode = true', 'videoMode = true;' in vbranch)
ck('B07 leaving-video resets the flag without frame work',
   'videoMode = false;\n        stopVideo();' in code_only(app).replace(
       'videoMode = false;\n            releaseVideoFrame();\n            snapshotStyleKey = "";', ''))
upd = code_only(method_body(BD, 'public void onSurfaceTextureUpdated(SurfaceTexture st)'))
ck('B08 onSurfaceTextureUpdated is EMPTY again (p68, comments stripped)',
   upd.replace(' ', '').replace('\n', '') == '{}')
ck('B09 video page objective signal kept: FAIL why=1 path intact',
   'noteFail(1,' in method_body(BD, 'public boolean drawRegion(Canvas c,'))
npl = method_body(BD, 'private void notePlateLog()')
ck('B10 VIDGLASS log states the p72 contract',
   'videoGlass=off' in npl and 'alloc=' in npl and 'bake=' not in npl)

# ---- C. the non-video p69 plate SURVIVES ---------------------------------
ck('C01 plateActive field kept', 'private boolean plateActive = true;' in BD)
ck('C02 ensurePlateSnapshot() kept', 'private void ensurePlateSnapshot()' in BD)
ck('C03 createBitmap sites: exactly the image snapshot + the plate',
   BD.count('Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)') == 2
   and 'Bitmap.createBitmap' in method_body(BD, 'private void ensurePlateSnapshot()'))
hm = method_body(BD, 'private void hideMedia()')
ck('C04 hideMedia keeps the plate for non-video kinds',
   'if (plateActive && !videoMode) ensurePlateSnapshot();' in hm)
ck('C05 hideMedia mirrors videoMode from the CONFIGURED kind',
   '"video".equals(cfg.asString(ConfigContract.Param.BG_KIND))' in hm)
rel = method_body(BD, 'public void release(boolean restoreOem)')
ck('C06 release() still shuts the plate down',
   'plateActive = false;' in rel)

# ---- D. byte-identical historical protection ------------------------------
dr = method_body(BD, 'public boolean drawRegion(Canvas c,')
dr67 = method_body(BD67, 'public boolean drawRegion(Canvas c,')
ck('D01 drawRegion() == p67 archive (the p68 state)', dr == dr67 and bool(dr))
def snap_tail(t):
    b = method_body(t, 'private Bitmap snapshot()')
    i = b.find('int w, h;')
    return b[i:] if i >= 0 else ''
ck('D02 snapshot() image tail == p67 archive',
   snap_tail(BD) == snap_tail(BD67) and bool(snap_tail(BD)))
ck('D03 snapshot() image branch has NO plateScale write',
   'plateScale' not in snap_tail(BD))
ck('D04 RenderController == p66 archive (p68 wiring state)', RC == RC66)
ck('D05 SettingsActivity == p71 archive (settings NOT reverted)', SA == SA71)
for f in ('MaterialBridge.java', 'LiquidGlassView.java', 'LiquidShader.java'):
    ck('D06 ' + f + ' == L1-ACCEPTED-p30 baseline',
       open(os.path.join(SRC, f), 'rb').read()
       == open(os.path.join(BASE, f), 'rb').read())

# ---- E. every other source == its p71 archive ------------------------------
CHANGED = {'BackdropLayer.java', 'RenderController.java', 'GlassEntry.java'}
mismatch = []
for f in sorted(os.listdir(SRC)):
    if not f.endswith('.java'):
        continue
    old = os.path.join(ARC, f[:-5] + '-p71-current.java')
    if not os.path.isfile(old):
        mismatch.append(f + ' (no p71 archive)')
        continue
    if read(os.path.join(SRC, f)) != read(old) and f not in CHANGED:
        mismatch.append(f)
ck('E01 only BackdropLayer/RenderController/GlassEntry differ from p71',
   not mismatch)
ge = method_body(GE, 'BUILD')  # may be a field, fall back to plain search
ck('E02 GlassEntry BUILD bumped', '0.5.0-p72' in GE)
ge71 = read(os.path.join(ARC, 'GlassEntry-p71-current.java'))
ck('E03 GlassEntry differs from p71 ONLY in the BUILD string',
   GE.replace('0.5.0-p72', '0.5.0-p71') == ge71)
bd_delta = BD != BD71
ck('E04 BackdropLayer genuinely changed vs p71', bd_delta)

# ---- F. version truth -------------------------------------------------------
prop = read(os.path.join(ROOT, 'app', 'src', 'main', 'resources', 'META-INF',
                         'xposed', 'module.prop'))
ck('F01 module.prop version=0.5.0-p72', 'version=0.5.0-p72' in prop)
ck('F02 module.prop versionCode=100', 'versionCode=100' in prop)

print()
if FAILS:
    print('ASSERT_P72 FAILED: %d' % len(FAILS))
    for f in FAILS:
        print('  - ' + f)
    sys.exit(1)
print('ASSERT_P72 OK: all checks passed')
