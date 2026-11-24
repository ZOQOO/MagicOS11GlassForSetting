#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""assert_p78.py -- deterministic (no device) invariants for 0.5.0-p78.

User (verbatim): "安装以后应该是没有应用成功这几个更改项都没有生效，检查一下"

Device is offline from our side (adb -s A4UF6R6116003189 -> device not found),
so this build does NOT claim any device/visual result. Offline findings:

  L0  DEPLOYMENT. The p77 APK really contains all three fixes (dex markers
      bg.windowColor / ACCOUNTCHROME / BGUNIFY / accountIdGate /
      isFadeWhiteLayer / plateBakedKey, versionCode 105). Project protocol
      device-protocol.md states explicitly: "`install -r` alone is NOT proof.
      lspd caches the module dex". Three fixes living in three different
      subsystems failing TOGETHER is the signature of the OLD dex still
      executing. A pure soft reboot (setprop ctl.restart zygote) is required.

  L1  REAL REACHABILITY GAP. BG_WINDOW_COLOR was derived ONLY from
      SettingsActivity.onCreate() and onMediaPicked(). An upgrade install
      followed by going straight into Settings never opens the module UI, so
      the key never exists, windowColorOf() falls back to WINDOW_BASE_COLOR
      and the white->wallpaper jump remains EVEN WITH THE NEW LOADED DEX.
      Fix: derive it lazily in ModuleProvider.query() -- the first config
      read by ANY host process (including ConfigBus.warmUpAsync()'s early
      query, i.e. before the first Activity attaches).

  L2  DIAGNOSABILITY GAP. All p77 criteria (window colour sample, ACCOUNT*,
      BGUNIFY) are gated by perf.debugLog which defaults to false, so an
      empty log cannot distinguish "old dex" from "fix did nothing".
      Fix: an always-visible `mg-state ` prefix plus one non-frame state
      line on force/slot change and a throttled fade-keep line.

  NO change to the three p77 fixes, to BackdropLayer, or to the frozen L1
  render trio. Checks: A version, B lazy derivation, C always-on state,
  D L1 freeze, E p77 fixes untouched, F no new frame-path work,
  G dead-end / criterion regressions.
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
    """Brace-balanced body (including outer braces) of one method."""
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
    return read(os.path.join(ARC, name[:-5] + '-p77-current.java'))


GE = f('GlassEntry.java')
MP = f('ModuleProvider.java')
RC = f('RenderController.java')
BD = f('BackdropLayer.java')
CC = f('ConfigContract.java')
CS = f('ConfigStore.java')
SA = f('SettingsActivity.java')
PROP_TXT = read(PROP)
ALL_CODE = code_only(''.join(f(n) for n in sorted(os.listdir(SRC))
                             if n.endswith('.java')))

q = method_body(MP, 'public Cursor query(')
startup = method_body(GE, 'private static boolean startupLine(')
say = method_body(GE, 'static void say(')
refresh = method_body(RC, 'private void refresh(boolean force)')
chrome = method_body(RC, 'private void stripRowsIn(')
pre = method_body(RC, 'private boolean onPreDraw()')

# ---- A. version truth -----------------------------------------------------
ck('A01 GlassEntry.BUILD == 0.5.0-p78',
   'public static final String BUILD = "0.5.0-p78";' in GE)
build_line = [l for l in GE.splitlines() if 'static final String BUILD' in l]
ck('A02 BUILD line has no p77 token',
   len(build_line) == 1 and 'p77' not in build_line[0])
ck('A03 module.prop version == 0.5.0-p78', 'version=0.5.0-p78\n' in PROP_TXT
   or 'version=0.5.0-p78' in PROP_TXT)
ck('A04 module.prop versionCode == 106', 'versionCode=106' in PROP_TXT)
ck('A05 p77 marker no longer in module.prop', '0.5.0-p77' not in PROP_TXT)
ck('A06 BUILD and module.prop agree', '0.5.0-p78' in GE and '0.5.0-p78' in PROP_TXT)

# ---- B. lazy window-colour derivation (L1 reachability gap) --------------
ck('B01 provider query() body is non-empty', bool(q))
ck('B02 query() calls ConfigStore.ensureWindowColor',
   'ConfigStore.ensureWindowColor(getContext());' in q)
ck('B03 derivation is gated on the key being ABSENT',
   'all.get(k) == null || all.get(k).length() == 0' in q)
ck('B04 key is the GLOBAL BG_WINDOW_COLOR key',
   'ConfigContract.Slot.GLOBAL' in q
   and 'ConfigContract.Param.BG_WINDOW_COLOR' in q)
ck('B05 query() re-reads config AFTER deriving',
   'all = ConfigStore.all(getContext());' in q)
ck('B06 Cursor is built from the re-read map, not a second all()',
   'for (Map.Entry<String, String> e : all.entrySet())' in q)
ck('B07 derivation state is emitted on the always-on channel',
   'GlassEntry.say("mg-state windowColour derived=' in q)
ck('B08 provider stays READ-ONLY',
   'public Uri insert(Uri uri, ContentValues values) {\n        return null;' in MP
   and 'return 0;' in method_body(MP, 'public int update('))
ck('B09 ensureWindowColor() is idempotent (one Map lookup afterwards)',
   'if (all.get(g) != null && all.get(g).length() > 0) return false;'
   in method_body(CS, 'public static boolean ensureWindowColor('))
ck('B10 p77 SettingsActivity migration path is preserved',
   'ConfigStore.ensureWindowColor(this);' in SA)
ck('B11 derivation still happens only in the MODULE process',
   'ConfigStore.ensureWindowColor' not in code_only(BD)
   and 'ConfigStore.ensureWindowColor' not in code_only(RC))
ck('B12 no extra broadcast from query() itself',
   'notifyChanged' not in q)

# ---- C. always-visible state lines (L2 diagnosability gap) ---------------
ck('C01 startupLine() admits the mg-state prefix',
   'msg.startsWith("mg-state ")' in startup)
ck('C02 startupLine() still admits mg-build',
   'msg.startsWith("mg-build ")' in startup)
ck('C03 say() still gates everything else on sVerbose',
   'if (!sVerbose && !startupLine(msg)) return;' in say)
ck('C04 refresh() emits a default-visible build/slot state line',
   'GlassEntry.say("mg-state build=" + GlassEntry.BUILD' in refresh)
ck('C05 state line is inside the force/slot-change gate',
   refresh.find('if (slotChanged || force)') >= 0
   and refresh.find('mg-state build=') > refresh.find('if (slotChanged || force)'))
ck('C06 state line carries win / winKey / slot / accountGate',
   all(t in refresh for t in (' win=', ' winKey=', ' slot=', ' accountGate=')))
ck('C07 fade-keep decision is visible without debugLog',
   'mg-state ACCOUNTFADE keep=' in RC)
ck('C08 fade-keep line is throttled (never per frame)',
   'now - lastAccountFadeLogMs > 2000L' in RC
   and 'lastAccountFadeLogMs = now;' in RC)
ck('C09 fade-keep line sits in the CHROME else (keep) branch',
   chrome.find('if (!accountChrome || !isFadeWhiteLayer(v))') >= 0
   and chrome.find('mg-state ACCOUNTFADE keep=')
       > chrome.find('if (!accountChrome || !isFadeWhiteLayer(v))'))
ck('C10 p77 BGUNIFY verbose criterion is preserved',
   'if (GlassEntry.verbose() && (slotChanged || force))' in refresh
   and 'log("BGUNIFY slot=' in refresh)

# ---- D. L1 freeze / unchanged files --------------------------------------
for name in ('MaterialBridge.java', 'LiquidGlassView.java', 'LiquidShader.java'):
    ck('D01 %s == p30 baseline (bytes)' % name,
       open(os.path.join(SRC, name), 'rb').read()
       == open(os.path.join(BASE, name), 'rb').read())
ck('D02 BackdropLayer.java whole file == p77 archive', BD == arc('BackdropLayer.java'))
ck('D03 EdgeFade.java == p77 archive', f('EdgeFade.java') == arc('EdgeFade.java'))
ck('D04 ConfigContract.java == p77 archive', CC == arc('ConfigContract.java'))
ck('D05 ConfigStore.java == p77 archive', CS == arc('ConfigStore.java'))
ck('D06 SettingsActivity.java == p77 archive', SA == arc('SettingsActivity.java'))
ck('D07 ScaleMode.java == p77 archive', f('ScaleMode.java') == arc('ScaleMode.java'))
for name, sig in [('drawRegion()', 'public boolean drawRegion(Canvas c,'),
                  ('snapshot()', 'private Bitmap snapshot()'),
                  ('recycle()', 'private void recycle()'),
                  ('ensurePlateSnapshot()', 'private void ensurePlateSnapshot()'),
                  ('drawLiveVideoRegion()', 'private boolean drawLiveVideoRegion(')]:
    a, b = method_body(BD, sig), method_body(arc('BackdropLayer.java'), sig)
    ck('D08 %s == p77 and non-vacuous' % name, bool(a and b) and a == b)
diff = []
for n in sorted(os.listdir(SRC)):
    if n.endswith('.java'):
        p = os.path.join(ARC, n[:-5] + '-p77-current.java')
        if not os.path.isfile(p) or f(n) != read(p):
            diff.append(n)
ck('D09 exactly 3 sources differ from p77',
   diff == ['GlassEntry.java', 'ModuleProvider.java', 'RenderController.java'],
   )
ck('D10 all 18 p77 source snapshots exist',
   len([n for n in os.listdir(ARC) if n.endswith('-p77-current.java')]) == 18)

# ---- E. the three p77 fixes are byte-for-byte untouched ------------------
for name, sig in [('accountIdGate()', 'private boolean accountIdGate()'),
                  ('isFadeWhiteLayer()', 'private static boolean isFadeWhiteLayer('),
                  ('extWhiteGate()', 'private boolean extWhiteGate()'),
                  ('isOpaqueSolidFill()',
                   'private static boolean isOpaqueSolidFill(Drawable d)')]:
    a, b = method_body(RC, sig), method_body(arc('RenderController.java'), sig)
    ck('E01 %s == p77 and non-vacuous' % name, bool(a and b) and a == b)
ck('E02 EXT_ID is still the only added account slot',
   'return genericEnabled && slot == ConfigContract.Slot.EXT_ID;'
   in method_body(RC, 'private boolean accountIdGate()'))
ck('E03 transparent is still NOT a fade keep-word',
   'n.contains("transparent")' not in method_body(RC, 'private static boolean isFadeWhiteLayer('))
ck('E04 read-path bg.* inheritance is intact',
   'boolean backgroundOnly = p.key.startsWith("bg.") && slot != Slot.L1;' in CC)
ck('E05 one-shot sampler is intact',
   'public static String deriveWindowColor(File f, String kind) {' in CS)
ck('E06 zero-alloc plate comparison is intact',
   'styleKey == plateBakedStyle' in BD or 'plateBakedKey' in BD)
ck('E07 ACCOUNTCHROME / ACCOUNTWHITE criteria still present',
   'ACCOUNTCHROME ' in RC and 'ACCOUNTWHITE ' in RC)

# ---- F. no new frame-path work -------------------------------------------
ck('F01 no mg-state line inside onPreDraw()', 'mg-state ' not in pre)
ck('F02 no mg-state line inside drawRegion()/snapshot()/plate',
   all('mg-state ' not in method_body(BD, s)
       for s in ('public boolean drawRegion(Canvas c,',
                 'private Bitmap snapshot()',
                 'private void ensurePlateSnapshot()')))
ck('F03 no timer/post/Thread added by p78',
   all(t not in code_only(MP) for t in ('postDelayed(', 'new Thread(', 'Timer(')))
ck('F04 only one new field in RenderController',
   'private long lastAccountFadeLogMs;' in RC
   and RC.count('private long lastAccountFadeLogMs;') == 1)
ck('F05 state line is built only on force/slot change, never per frame',
   refresh.count('mg-state build=') == 1
   and 'mg-state build=' not in pre)

# ---- G. no dead-end / criterion regressions ------------------------------
ck('G01 CPU video capture dead end stays absent',
   'captureVideoFrame' not in ALL_CODE
   and 'VIDEO_CAPTURE_INTERVAL_MS' not in ALL_CODE)
ck('G02 p74/p75 one-shot transition frame preserved',
   'TRANSITION snap=once' in BD
   and 'video.getTransform(null)' in BD)
ck('G03 p73 GPU live layer preserved',
   'drawLiveVideoRegion' in BD and 'drawRenderNode' in BD)
ck('G04 mg-build startup signature preserved',
   'say("mg-build " + BUILD + " loaded in " + pkg);' in GE)
ck('G05 video hard gate preserved',
   'if (videoMode && !released)' in BD)

print('\n%d checks, %d failures' % (CHECKS[0], len(FAILS)))
if FAILS:
    print('FAILED:')
    for x in FAILS:
        print('  - ' + x)
    sys.exit(1)
print('ALL_GREEN')
sys.exit(0)
