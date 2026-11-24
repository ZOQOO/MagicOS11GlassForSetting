#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Targeted LIVEGLASS backdrop probe during a pull-down. READ ONLY."""
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


LOG = remote("su -c 'ls -t /data/adb/lspd/log/verbose_* | head -1'").strip().splitlines()[0]


def live():
    o = remote("su -c 'grep -a \"backdrop LIVEGLASS\" " + LOG + "'")
    rows = []
    for x in o.splitlines():
        m = re.search(r'node=(\d+) draw=(\d+) fail=(\d+) total=(\d+)', x)
        if m:
            rows.append(tuple(int(v) for v in m.groups()))
    return rows


def wc():
    o = remote("su -c 'wc -l < " + LOG + "'")
    try:
        return int(o.strip().splitlines()[0])
    except (ValueError, IndexError):
        return 0


result = ['Targeted LIVEGLASS probe during pull-down -- READ ONLY\nlog=' + LOG + '\n']

# L2 with a list: 移动网络. Ensure a video background is active (it is, per earlier logs).
sh('am', 'start', '-n', 'com.android.settings/.HWSettings')
time.sleep(1.5)
sh('input', 'tap', '636', '1650')
time.sleep(2.5)

base = live()
result.append('BASE settled: %d LIVEGLASS lines; last 6:' % len(base))
for r in base[-6:]:
    result.append('  node=%d draw=%d fail=%d total=%d' % r)

n0 = wc()
for _ in range(6):
    sh('input', 'swipe', '640', '600', '640', '2200', '240')
    time.sleep(0.1)
time.sleep(0.2)
during = live()[len(base):]
result.append('\nDURING 6 swipes: %d new LIVEGLASS lines' % len(during))
for r in during[-15:]:
    result.append('  node=%d draw=%d fail=%d total=%d' % r)

n1 = wc()
time.sleep(0.15)
just = live()[len(base) + len(during):]
result.append('\nJUST RELEASED: %d lines' % len(just))
for r in just[-10:]:
    result.append('  node=%d draw=%d fail=%d total=%d' % r)

time.sleep(1.2)
settled = live()[len(base) + len(during) + len(just):]
result.append('\nSETTLED +1.2s: %d lines' % len(settled))
for r in settled[-10:]:
    result.append('  node=%d draw=%d fail=%d total=%d' % r)

nodes = [r[0] for r in base + during + just + settled]
result.append('\nSUMMARY: node min=%d max=%d distinct=%d; total-fail-sum=%d; draw-sum=%d'
              % (min(nodes), max(nodes), len(set(nodes)),
                 sum(r[2] + r[3] for r in during + just + settled),
                 sum(r[1] for r in during + just + settled)))

path = os.path.join(OUT, 'p119_video_backdrop_drag_2026-11-18.txt')
io.open(path, 'w', encoding='utf-8', newline='\n').write('\n'.join(result) + '\n')
print(path)
