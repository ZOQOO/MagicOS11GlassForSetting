#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""assert_p104.py -- deterministic (no device) invariants for 0.5.0-p104.

Scope of this build (user request): "是不是可以尝试使用其他方法把设置背景
显示完成后的大小位置设置成这个模糊图的底图？" -- i.e. STOP re-deriving the
splash framing and instead CAPTURE the settled backdrop.

This script only asserts what is inside the APK / sources. It makes NO claim
about the loaded dex, the visible splash, or the user's visual acceptance.
A green gate proves "the planned code is inside the build", nothing more.
"""

import hashlib
import os
import re
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'magicglass')
ARC = os.path.join(ROOT, 'probe', 'probe-report', 'archive')
BASE = os.path.join(ROOT, 'probe', 'probe-report', 'baselines',
                    'L1-ACCEPTED-p30', 'src', 'main', 'java', 'com',
                    'magicglass')
PROP = os.path.join(ROOT, 'app', 'src', 'main', 'resources',
                    'META-INF', 'xposed', 'module.prop')
SCOPE = os.path.join(ROOT, 'app', 'src', 'main', 'resources',
                     'META-INF', 'xposed', 'scope.list')
APK = os.path.join(ROOT, 'build-out', 'MagicGlass-0.5.0-p104.apk')

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


def code_only(text):
    t = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
    return re.sub(r'//[^\n]*', '', t)


def f(name):
    return read(os.path.join(SRC, name))


GE = f('GlassEntry.java')
ML = f('MgLog.java')
CS = f('ConfigStore.java')
CB = f('ConfigBus.java')
MP = f('ModuleProvider.java')
BL = f('BackdropLayer.java')
SB = f('SplashBase.java')
PROP_TXT = read(PROP)

# ---- A. version / packaging ---------------------------------------------
ck('A01 BUILD == 0.5.0-p104',
   'public static final String BUILD = "0.5.0-p104";' in GE)
ck('A02 module.prop version == 0.5.0-p104',
   'version=0.5.0-p104' in PROP_TXT)
ck('A03 module.prop versionCode == 132', 'versionCode=132' in PROP_TXT)
ck('A04 BUILD and module.prop agree',
   '0.5.0-p104' in GE and '0.5.0-p104' in PROP_TXT)
ck('A05 mg-build fingerprint line survives', 'say("mg-build " + BUILD' in GE)
ck('A06 APK exists', os.path.isfile(APK))
ck('A07 APK SHA256 is stable and recorded',
   hashlib.sha256(open(APK, 'rb').read()).hexdigest()
   == '5e5d522385267b24165532473464cd9c15b5173844696745d8f2f491bfddb6c5')
ck('A08 APK is v3-signed / zip has exactly one classes.dex',
   (lambda z: 'classes.dex' in z.namelist()
    and z.namelist().count('classes.dex') == 1)(zipfile.ZipFile(APK)))
ck('A09 dex carries the p104 build fingerprint',
   b'0.5.0-p104' in zipfile.ZipFile(APK).read('classes.dex'))
ck('A10 scope.list still has exactly 9 entries',
   len([l for l in read(SCOPE).splitlines() if l.strip()]) == 9)
ck('A11 zero MIUI / HonorBG inheritance in LIVE CODE (comments state the ban)',
   all('miui' not in code_only(s).lower() and 'HonorBG' not in code_only(s)
       for s in (GE, ML, CS, CB, MP, BL, SB)))

# ---- B. module-process crash fix (measured on device) --------------------
ck('B01 MgLog does NOT extend or statically type any libxposed class',
   'XposedModule' not in code_only(ML)
   and 'io.github.libxposed' not in code_only(ML))
ck('B02 MgLog keeps the module reference untyped (Object)',
   'private static volatile Object sModule;' in ML)
ck('B03 MgLog reaches the framework logger only by reflection',
   'java.lang.reflect.Method' in ML and 'log.invoke(module' in ML)
ck('B04 GlassEntry binds MgLog at process load',
   'MgLog.bind(this);' in GE)
ck('B05 GlassEntry.say is a pure delegation (no libxposed type in the method)',
   'static void say(String msg) {\n        MgLog.say(msg);\n    }' in GE)
ck('B06 GlassEntry.verbose delegates too',
   'return MgLog.verbose();' in GE and 'MgLog.setVerbose(on);' in GE)
ck('B07 ConfigStore has ZERO GlassEntry code references',
   'GlassEntry' not in code_only(CS))
ck('B08 ConfigBus has ZERO GlassEntry code references',
   'GlassEntry' not in code_only(CB))
ck('B09 ModuleProvider has ZERO GlassEntry code references',
   'GlassEntry' not in code_only(MP))
ck('B10 those three files all log through MgLog',
   'MgLog.say' in CS and 'MgLog.say' in CB and 'MgLog.say' in MP)
ck('B11 MgLog still mirrors to logcat (device evidence channel)',
   'android.util.Log.i(TAG, msg);' in ML)
ck('B12 mg-state lines are still always visible with debugLog off',
   'msg.startsWith("mg-state ")' in ML)

# ---- C. captured base map: provider write channel ------------------------
ck('C01 provider registers a THIRD uri (splashbase)',
   'MATCHER.addURI(ConfigBus.AUTHORITY, "splashbase", SPLASH);' in MP)
ck('C02 openFile is gated to the splashbase uri',
   'MATCHER.match(uri) != SPLASH' in MP)
ck('C03 openFile rejects every non-write mode',
   '!mode.startsWith("w")' in MP)
ck('C04 the ONLY writable target is the fixed temp name',
   'ConfigStore.SPLASH_BASE_TMP' in MP
   and 'MODE_TRUNCATE' in MP)
ck('C05 the media/* read path is still read-only',
   'throw new FileNotFoundException("read-only provider");' in MP)
ck('C06 commit validates the capture against the CURRENT signature',
   'ConfigStore.installSplashBase(c, arg)' in MP)
ck('C07 a successful commit broadcasts so BOTH processes drop the old picture',
   'ConfigStore.notifyChanged(c);' in MP)
ck('C08 commit returns a deterministic ok flag',
   'out.putInt("ok", ok ? 1 : 0);' in MP)
ck('C09 commit logs in the module process with the safe logger',
   'mg-state splashBase commit=ok ' in MP and 'MgLog.say' in MP)

# ---- D. ConfigStore captured-map validity --------------------------------
ck('D01 currentSignature reads the SAME five render fields as the sidecar',
   all(k in CS for k in ('ConfigContract.Param.BG_SOURCE',
                         'ConfigContract.Param.BG_SCALE',
                         'ConfigContract.Param.BG_SCALE_ZOOM',
                         'ConfigContract.Param.BG_SCALE_FOCUS_X',
                         'ConfigContract.Param.BG_SCALE_FOCUS_Y')))
ck('D02 installSplashBase refuses a stale signature',
   '!current.equals(signature)' in CS and 'tmp.delete();' in CS)
ck('D03 installSplashBase promotes the temp file to window_preview.png',
   'tmp.renameTo(png)' in CS)
ck('D04 installSplashBase writes the |cap marker',
   'writePreviewSource(dir, signature, true);' in CS)
ck('D05 the sidecar records the suffix',
   'signature + CAP_SUFFIX' in CS and 'CAP_SUFFIX = "|cap"' in CS)
ck('D06 a captured map skips the 90% screen-width rule',
   'boolean cap = made != null && made.endsWith(CAP_SUFFIX);' in CS
   and 'if (!cap) {' in CS)
ck('D07 a captured map is STILL signature-checked (no stale picture)',
   'made.substring(0, made.length() - CAP_SUFFIX.length())' in CS
   and '!made5.equals(want)' in CS)
ck('D08 the 200 px lower bound still applies to every preview',
   'o.outWidth < 200 || o.outHeight < 200' in CS)
ck('D09 p103 full-signature rebuild survives',
   'String want = sourceName + "|" + mode + "|" + zoom + "|" + fx + "|" + fy;' in CS)

# ---- E. host-side capture: reuse the p75 mechanism, no new geometry ------
ck('E01 video capture uses getBitmap + the ACTUAL live transform',
   'video.getBitmap(tw, th)' in BL
   and 'video.getTransform(null)' in BL)
ck('E02 no recomputed ScaleMode.matrix inside the video capture body',
   (lambda i: i >= 0
    and 'ScaleMode.matrix' not in BL[i:i + 2600])(
       BL.find('private Bitmap captureVideoFrame')))
ck('E03 transition capture still uses its frozen 320 px budget',
   'captureVideoFrame(TRANSITION_MAX_EDGE)' in BL)
ck('E04 the base map asks for a larger one-shot frame',
   'captureVideoFrame(SPLASH_BASE_MAX_EDGE)' in BL)
ck('E05 image path reuses the p20 screen snapshot',
   ': snapshot();' in BL)
ck('E06 the capture is scheduled once per style signature',
   'sig.equals(splashBaseSig)' in BL and 'splashBaseSig = sig;' in BL)
ck('E07 the capture is delayed off the first-frame path',
   'handler.postDelayed(captureBase, SPLASH_BASE_DELAY_MS)' in BL)
ck('E08 capture retries are bounded',
   'splashBaseTries++ < 3' in BL)
ck('E09 release() cancels a pending capture',
   'handler.removeCallbacks(captureBase);' in BL)
ck('E10 scheduleSplashBase is called for BOTH video and image',
   BL.count('scheduleSplashBase();') == 2)
ck('E11 the capture is logged as default-visible evidence',
   'mg-state splashBase grab=' in BL)

# ---- F. SplashBase transport --------------------------------------------
ck('F01 the host writes through the provider, never into module private dirs',
   'openOutputStream(URI, "w")' in SB
   and 'getFilesDir' not in SB)
ck('F02 the commit method and signature are the ones the provider expects',
   '"commit", signature' in SB)
ck('F03 the stored picture is capped in size (no full-screen PNG)',
   'MAX_EDGE = 720' in SB)
ck('F04 encode and IO run on a daemon worker, not the UI thread',
   'new Thread(new Runnable()' in SB and 'setDaemon(true)' in SB)
ck('F05 any failure is non-fatal (composed preview stays as fallback)',
   'mg-state splashBase skip why=' in SB)
ck('F06 the source bitmap is never recycled by the transport',
   'small != src' in SB and 'src.isRecycled()' in SB)
ck('F07 signature is the SAME five fields the provider compares',
   'public static String signature(String source, String mode, int zoom,'
   in SB and 'source.trim() + "|"' in SB)
ck('F08 commit result is logged with size + bytes + ok',
   'mg-state splashBase cap=' in SB and 'bytes=' in SB and ' ok=' in SB)

# ---- G. frozen baseline / no collateral changes -------------------------
for name in ('MaterialBridge.java', 'LiquidGlassView.java', 'LiquidShader.java'):
    ck('G01 %s == p30 baseline (bytes)' % name,
       read(os.path.join(SRC, name)) == read(os.path.join(BASE, name)))
ck('G02 the p103 GPU blur and its radius are untouched',
   'SPLASH_BLUR_RADIUS = 64f' in GE
   and 'RenderEffect.createBlurEffect(' in GE)
ck('G03 the ROM exit-animation branch is untouched (immediate proceed)',
   'playRevealAnimation' in GE and 'removeImmediately' in GE)
ck('G04 archived p104 APK matches the built APK byte for byte',
   open(APK, 'rb').read() == open(os.path.join(
       ARC, 'MagicGlass-0.5.0-p104.apk'), 'rb').read())
ck('G05 every changed source has a p104 archive snapshot',
   all(os.path.isfile(os.path.join(ARC, n + '-p104-current.java'))
       for n in ('GlassEntry', 'MgLog', 'ConfigStore', 'ConfigBus',
                 'ModuleProvider', 'BackdropLayer', 'SplashBase')))

print('\n%d checks, %d failures' % (CHECKS[0], len(FAILS)))
if FAILS:
    print('FAILED:')
    for x in FAILS:
        print('  - ' + x)
    sys.exit(1)
print('ALL_GREEN')
sys.exit(0)
