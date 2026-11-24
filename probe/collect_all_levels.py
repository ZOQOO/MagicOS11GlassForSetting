#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Read-only multi-page collector: whole Settings scope, not one menu.

For each page we capture, from the LSPosed log only:
  - mg-state build=... slot=... kind=... (which level/slot actually resolved)
  - COLORCAND (measured Drawable of every content-band candidate, p118)
  - CONTENTNODE / CONTENTRECTS (content probe, p119 budget fixed)
  - UNIFIED (strip/placeholder/revived/rects counters)
No screenshots, no recording, no visual model. One local .txt output.
"""
import io
import os
import re
import subprocess
import time

S = 'A4UF6R6116003189'
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'probe', 'probe-report')


def sh(*args):
    return subprocess.run(['adb', '-s', S, 'shell'] + list(args),
                          capture_output=True).stdout.decode('utf-8', 'ignore')


def remote(cmd):
    return subprocess.run(['adb', '-s', S, 'shell', cmd],
                          capture_output=True).stdout.decode('utf-8', 'ignore')


def logfile():
    return remote("su -c 'ls -t /data/adb/lspd/log/verbose_* | head -1'").strip().splitlines()[0]


def count():
    out = remote("su -c 'wc -l < " + LOG + "'")
    try:
        return int(out.strip().splitlines()[0])
    except (ValueError, IndexError):
        return 0


def since(n):
    return remote("su -c 'tail -n +" + str(n + 1) + " " + LOG + "'").splitlines()


def clean(lines):
    return [re.sub(r'^\[[^\]]*\][^\]]*\]\s*', '', x) for x in lines if x.strip()]


def top():
    o = sh('dumpsys', 'activity', 'activities')
    return o.split('topResumedActivity=')[1].split('\n')[0].strip()[:120] if 'topResumedActivity=' in o else '?'


def start(component=None, action=None):
    if component:
        sh('am', 'start', '-n', component)
    elif action:
        sh('am', 'start', '-a', action)


PAGES = [
    ('L1_settings_home', 'com.android.settings/.HWSettings', None),
    ('L2_wlan', 'com.android.settings/.Settings$WifiSettingsActivity', None),
    ('L2_bluetooth', 'com.android.settings/.Settings$BluetoothSettingsActivity', None),
    ('L2_display', 'com.android.settings/.Settings$DisplaySettingsActivity', None),
    ('L2_battery', 'com.android.settings/.Settings$PowerUsageSummaryActivity', None),
    ('L3_app_details', None, 'android.settings.APPLICATION_DETAILS_SETTINGS'),
    ('external_systemmanager', 'com.hihonor.systemmanager/com.hihonor.netassistant.ui.NetAssistantMainActivity', None),
]


def main():
    global LOG
    LOG = logfile()
    all_out = ['p119 whole-scope read-only collector\nlog=' + LOG + '\n']
    for name, comp, act in PAGES:
        try:
            if comp:
                start(component=comp)
            elif act:
                start(action=act)
            time.sleep(2.0)
            n0 = count()
            time.sleep(1.5)
            lines = since(n0)
            at = top()
            block = ['\n===== %s =====' % name, 'activity: ' + at]
            for key, needle in (
                    ('build', 'mg-state build='),
                    ('colorcand', 'COLORCAND'),
                    ('node', 'CONTENTNODE'),
                    ('rects', 'CONTENTRECTS'),
                    ('unified', 'mg-state UNIFIED')):
                v = clean([x for x in lines if needle in x])
                block.append('%s: %d' % (key, len(v)))
                for line in v[:12]:
                    block.append('  ' + line[:250])
            all_out.append('\n'.join(block))
        except Exception as e:
            all_out.append('\n===== %s ===== ERROR %s' % (name, e))
    path = os.path.join(OUT, 'p119_all_levels_2026-11-18.txt')
    io.open(path, 'w', encoding='utf-8', newline='\n').write('\n'.join(all_out) + '\n')
    print(path)


if __name__ == '__main__':
    main()
