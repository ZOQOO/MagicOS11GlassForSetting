#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Read-only reproduction of the L2/L3 pull-down glass lag. Log only."""
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


def wc():
    o = remote("su -c 'wc -l < " + LOG + "'")
    try:
        return int(o.strip().splitlines()[0])
    except (ValueError, IndexError):
        return 0


def tail(n):
    return remote("su -c 'tail -n +" + str(n + 1) + " " + LOG + "'").splitlines()


def clean(lines):
    return [re.sub(r'^\[[^\]]*\][^\]]*\]\s*', '', x) for x in lines if x.strip()]


LOG = logfile()
result = ['p119 L2/L3 pull-down lag reproduction -- READ ONLY\nlog=' + LOG + '\n']

# Phase 1: settle an L2 page (move network = a real L2 with a list).
sh('am', 'start', '-n', 'com.android.settings/.HWSettings')
time.sleep(1.5)
sh('input', 'tap', '636', '1650')   # 移动网络
time.sleep(2.5)
n0 = wc()
time.sleep(1.5)
lines = clean(tail(n0))
result.append('\n===== IDLE (settled L2) =====')
for x in lines:
    if 'frames:' in x or 'scan: rects' in x or 'backgrounds cleared' in x:
        result.append('  ' + x[:230])

# Phase 2: burst of downward pulls.
n1 = wc()
for _ in range(6):
    sh('input', 'swipe', '640', '600', '640', '2200', '260')
    time.sleep(0.12)
time.sleep(0.5)
lines = clean(tail(n1))
result.append('\n===== PULLING (6 swipes) =====')
frames = [x for x in lines if 'frames:' in x]
scans = [x for x in lines if 'scan: rects' in x]
result.append('frames lines: %d / scan lines: %d' % (len(frames), len(scans)))
for x in frames[-20:]:
    result.append('  ' + x[x.find('frames:'):][:200])
for x in scans[-8:]:
    result.append('  SCAN ' + x[x.find('scan:'):][:220])

# Phase 3: immediately after release, before tail expires.
n2 = wc()
time.sleep(0.08)
lines = clean(tail(n2))
result.append('\n===== JUST RELEASED (~80ms) =====')
for x in lines:
    if 'frames:' in x or 'scan: rects' in x:
        result.append('  ' + x[x.find('frames:') if 'frames:' in x else x.find('scan:'):][:200])

# Phase 4: fully idle, 1s later.
n3 = wc()
time.sleep(1.2)
lines = clean(tail(n3))
result.append('\n===== SETTLED (+1.2s) =====')
for x in lines:
    if 'frames:' in x or 'scan: rects' in x:
        result.append('  ' + x[x.find('frames:') if 'frames:' in x else x.find('scan:'):][:200])

path = os.path.join(OUT, 'p119_scroll_lag_repro_2026-11-18.txt')
io.open(path, 'w', encoding='utf-8', newline='\n').write('\n'.join(result) + '\n')
print(path)
