#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Read-only collector for the p117 content-band probe.

Walks into the three com.hihonor.id sub-pages through the normal UI and
collects mg-build / CONTENTNODE / CONTENTRECTS lines from the LSPosed log.
Never writes to the phone, never strips anything, never restarts a process.
"""
import io
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

S = 'A4UF6R6116003189'
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'probe', 'probe-report')
LOG = ''


def ex(args):
    return subprocess.run(['adb', '-s', S, 'exec-out'] + args,
                          capture_output=True).stdout.decode('utf-8', 'ignore')


def sh(args):
    return subprocess.run(['adb', '-s', S, 'shell'] + args,
                          capture_output=True).stdout.decode('utf-8', 'ignore')


def dump():
    for _ in range(6):
        s = ex(['uiautomator', 'dump', '/dev/stdout'])
        a = s.find('<?xml')
        b = s.find('</hierarchy>')
        if a >= 0 and b > a:
            return ET.fromstring(s[a:b + len('</hierarchy>')])
        time.sleep(1)
    return None


def bounds(n):
    z = [int(v) for v in re.findall(r'\d+', n.get('bounds', ''))]
    return z if len(z) >= 4 else None


def tap_text(text):
    r = dump()
    if r is None:
        return False
    for n in r.iter('node'):
        if (n.get('text', '') or '').strip() == text:
            z = bounds(n)
            if z and z[3] < 2800:
                sh(['input', 'tap', str((z[0] + z[2]) // 2),
                    str((z[1] + z[3]) // 2)])
                return True
    return False


def logfile():
    return sh(['su', '-c', 'ls -t /data/adb/lspd/log/verbose_* | head -1']).strip()


def grep(pattern):
    # adb shell re-joins list args with spaces, which breaks "su -c '...'".
    # Pass the WHOLE remote command as ONE argument instead, and use -F so
    # dots in version strings are literal rather than regex wildcards.
    full = "su -c 'grep -aF \"" + pattern + "\" " + LOG + "'"
    out = subprocess.run(['adb', '-s', S, 'shell', full],
                         capture_output=True).stdout.decode('utf-8', 'ignore')
    return [re.sub(r'^\[[^\]]*\][^\]]*\]\s*', '', x)
            for x in out.splitlines() if x.strip()]


def enter_home():
    sh(['am', 'start', '-n', 'com.android.settings/.HWSettings'])
    time.sleep(1.3)
    sh(['input', 'tap', '636', '1000'])
    time.sleep(2.6)
    for _ in range(9):
        sh(['input', 'swipe', '636', '2300', '636', '900', '250'])
        time.sleep(0.28)
    time.sleep(0.8)


LOG = logfile()
print('LOG =', LOG)
print('=== p117 loaded processes BEFORE ===')
for line in grep('mg-build 0.5.0-p117')[-6:]:
    print(' ', line[:180])

result = []
for page in ('账号安全', '隐私中心', '帮助与客服'):
    enter_home()
    ok = tap_text(page)
    time.sleep(3.0)
    top = sh(['dumpsys', 'activity', 'activities']).split(
        'topResumedActivity=')[-1].split('\n')[0][:105]
    print('\n=== %s tap=%s top=%s ===' % (page, ok, top))
    nodes = grep('CONTENTNODE')
    rects = grep('CONTENTRECTS')
    builds = grep('mg-build 0.5.0-p117')
    print('  CONTENTNODE=%d CONTENTRECTS=%d p117build=%d'
          % (len(nodes), len(rects), len(builds)))
    for x in nodes[-8:]:
        print('   ', x[:200])
    for x in rects[-3:]:
        print('   ', x[:200])
    result.append('\n### ' + page + ' top=' + top + '\n'
                  + '\n'.join(nodes[-30:]) + '\n'
                  + '\n'.join(rects[-6:]) + '\n')

print('\n=== p117 loaded processes AFTER ===')
for line in grep('mg-build 0.5.0-p117')[-8:]:
    print(' ', line[:180])

with io.open(os.path.join(OUT, 'p117_content_probe_2026-11-16.txt'),
             'w', encoding='utf-8', newline='\n') as f:
    f.write('p117 content-band probe (read-only)\nLOG=' + LOG + '\n'
            + ''.join(result))
print('saved')
