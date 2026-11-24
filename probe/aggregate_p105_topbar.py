#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Aggregate the p105 TOPBAR evidence from the live LSPosed log.

Usage (device must be connected, read-only):
    python probe/aggregate_p105_topbar.py [minutes]

The user rebuilds an external app's process (swipe it out of Recents, then
open its L2 page and pull up once). This script then reads the default-visible
`mg-state TOPBAR` lines and prints, per slot:
    total      candidates seen
    strip      candidates whose Drawable MEASURED opaque near-white and were
               stripped (the white block)
    fade       candidates kept because they are translucent (the fading layer)
    nohit      candidates measured neither opaque nor white (left alone)

Acceptance is a property of the log, not of an opinion:
    white block removed  -> at least one strip=1 on the affected slot
    fading layer kept    -> no fade=1 line has strip=1

It also prints which processes actually loaded 0.5.0-p105, because a slot
with zero TOPBAR lines and no p105 load is a LOADING-layer gap, not a failed
predicate. Never restarts or force-stops a process.
"""

import collections
import re
import subprocess
import sys

SERIAL = 'A4UF6R6116003189'
MINUTES = int(sys.argv[1]) if len(sys.argv) > 1 else 15


def su(inner):
    """One shell argument, so the device sh sees a complete `su -c '...'`.

    Passing ('su', '-c', inner) as three adb arguments made the device shell
    split the inner command, which silently produced an EMPTY grep result --
    a false negative (the log did contain p105 lines). Keep the inner regex
    in double quotes so the outer single quotes stay balanced.
    """
    cmd = "su -c '" + inner + "'"
    return subprocess.run(['adb', '-s', SERIAL, 'shell', cmd],
                          capture_output=True).stdout.decode('utf-8', 'ignore')


def main():
    listing = su('ls -t /data/adb/lspd/log/verbose_* | head -1').strip()
    if not listing:
        print('NO LOG: cannot read /data/adb/lspd/log')
        return 1
    print('log:', listing)
    raw = su('grep -a -E "mg-state TOPBAR|mg-state TOPLAYER|mg-state TOPCONTAINER|mg-build 0.5.0-p10" ' + listing)

    loads = []
    rows = []
    lines = raw.splitlines()
    for line in raw.splitlines():
        if 'mg-build 0.5.0-p10' in line:
            m = re.search(r'\(([^)]+)\)\[com\.magicglass', line)
            loads.append(m.group(1) if m else '?')
        elif 'mg-state TOPBAR' in line:
            rows.append(line)

    print('\n== processes that loaded a p10x build ==')
    for p in sorted(set(loads)):
        print('  ', p)
    if not loads:
        print('   (none)')

    stat = collections.defaultdict(lambda: collections.Counter())
    fade_leak = []
    for line in rows:
        slot = (re.search(r'slot=(\S+)', line) or [None, '?'])[1]
        strip = int((re.search(r'strip=(\d+)', line) or [None, '0'])[1])
        fade = int((re.search(r'fade=(\d+)', line) or [None, '0'])[1])
        solid = int((re.search(r'solid=(\d+)', line) or [None, '0'])[1])
        measured = int((re.search(r'measured=(\d+)', line) or [None, '0'])[1])
        s = stat[slot]
        s['total'] += 1
        if strip:
            s['strip'] += 1
            if fade:
                fade_leak.append(line)
        elif fade:
            s['fade'] += 1
        else:
            s['nohit'] += 1
        if solid:
            s['solid'] += 1
        if measured:
            s['measured'] += 1

    print('\n== TOPBAR candidates by slot (last %d minutes of log) ==' % MINUTES)
    if not rows:
        print('  NONE. If no p105 process above is an external L2 page, this is')
        print('  a LOADING-layer gap: the target app process has not been rebuilt.')
    else:
        print('  %-24s %6s %6s %6s %7s %7s %9s' %
              ('slot', 'total', 'strip', 'fade', 'nohit', 'solid', 'measured'))
        for slot in sorted(stat):
            s = stat[slot]
            print('  %-24s %6d %6d %6d %7d %7d %9d' %
                  (slot, s['total'], s['strip'], s['fade'], s['nohit'],
                   s['solid'], s['measured']))
        print('\n  fading layer leaked (fade=1 AND strip=1): %d' % len(fade_leak))
        for line in fade_leak[:5]:
            print('   ', line.strip())

    print('\n== TOPLAYER / TOPCONTAINER decisions (p106/p107, last 40) ==')
    layers = [l for l in lines if 'mg-state TOPLAYER' in l
                 or 'mg-state TOPCONTAINER' in l]
    if not layers:
        print('   (none)')
    else:
        kinds = collections.Counter()
        for line in layers:
            m = re.search(r'kind=(\S+)', line)
            kinds[m.group(1) if m else '?'] += 1
        print('   kind counts:', dict(kinds))
        for line in layers[-40:]:
            print('  ', line.strip())

    print('\n== raw TOPBAR lines (last 40) ==')
    for line in rows[-40:]:
        print('  ', line.strip())
    return 0


if __name__ == '__main__':
    sys.exit(main())
