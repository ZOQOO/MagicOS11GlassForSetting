#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""p118 read-only diagnostic collector -- NO visual judgement.

Collects, for one page:
  - mg-build <ver> load proof (which process actually runs the new dex)
  - mg-state build / slot line (live config: diag / strip switches)
  - COLORCAND lines (measured Drawable for every content-band candidate:
    class, id, bounds, bg/fg type, getOpacity, 3x3 alpha distribution,
    fade verdict, family word, tier1/tier2 verdict, parent, slot)
  - CONTENTNODE lines (p117 content-area probe, same measured fields)
  - CONTENTRECTS (CardScanner rects, to see whether a card is a candidate)
  - UNIFIED summary (strip/placeholder/revived/rects counters)

Everything is read-only: dumpsys, log reads, uiautomator dump. No screenshots,
no screen recording, no visual model. Writes ONE local .txt report.
"""
import io
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

S = 'A4UF6R6116003189'
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'probe', 'probe-report')
LOG = ''
BASE_LINES = 0


def sh(args):
    return subprocess.run(['adb', '-s', S, 'shell'] + list(args),
                          capture_output=True).stdout.decode('utf-8', 'ignore')


def remote(cmd):
    # Pass the WHOLE remote command as ONE adb-shell argument.
    return subprocess.run(['adb', '-s', S, 'shell', cmd],
                          capture_output=True).stdout.decode('utf-8', 'ignore')


def ex(args):
    return subprocess.run(['adb', '-s', S, 'exec-out'] + list(args),
                          capture_output=True).stdout.decode('utf-8', 'ignore')


def resolve_log():
    raw = remote("su -c 'ls -t /data/adb/lspd/log/verbose_* | head -1'")
    return raw.strip().splitlines()[0] if raw.strip() else ''


def line_count():
    out = remote("su -c 'wc -l < " + LOG + "'")
    try:
        return int(out.strip().splitlines()[0])
    except (ValueError, IndexError):
        return 0


def tail_since(n):
    out = remote("su -c 'tail -n +" + str(n + 1) + " " + LOG + "'")
    return out.splitlines()


def filt(lines, needle):
    return [re.sub(r'^\[[^\]]*\][^\]]*\]\s*', '', x)
            for x in lines if needle in x]


def top_activity():
    out = sh(['dumpsys', 'activity', 'activities'])
    if 'topResumedActivity=' not in out:
        return 'unknown'
    return out.split('topResumedActivity=')[1].split('\n')[0].strip()[:140]


def dump_tree():
    for _ in range(6):
        s = ex(['uiautomator', 'dump', '/dev/stdout'])
        a = s.find('<?xml')
        b = s.find('</hierarchy>')
        if a >= 0 and b > a:
            return ET.fromstring(s[a:b + len('</hierarchy>')])
        time.sleep(1)
    return None


def collect_page(name, wait_s=2.0):
    global BASE_LINES
    time.sleep(wait_s)
    at = top_activity()
    n0 = BASE_LINES
    BASE_LINES = line_count()
    lines = tail_since(n0)
    groups = {
        'build': filt(lines, 'mg-build 0.5.0-p118'),
        'state': filt(lines, 'mg-state build='),
        'colorcand': filt(lines, 'CONTENTNODE') + filt(lines, 'COLORCAND'),
        'rects': filt(lines, 'CONTENTRECTS'),
        'unified': filt(lines, 'mg-state UNIFIED'),
    }
    tree = dump_tree()
    nodes = []
    if tree is not None:
        for node in tree.iter('node'):
            z = [int(v) for v in re.findall(r'\d+', node.get('bounds', ''))]
            if len(z) < 4:
                continue
            if z[3] - z[1] < 60 and z[2] - z[0] < 60:
                continue
            nodes.append('  %-22s %-26s %s' % (
                node.get('class', '').split('.')[-1],
                node.get('resource-id', '').split('/')[-1][:24],
                node.get('bounds', '')))
    out = []
    out.append('\n===== %s =====' % name)
    out.append('activity: ' + at)
    for k in ('build', 'state', 'colorcand', 'rects', 'unified'):
        v = groups[k]
        out.append('%s: %d' % (k, len(v)))
        for line in v[:80]:
            out.append('  ' + line[:260])
    out.append('view tree (nodes >=60px): %d' % len(nodes))
    out.extend(nodes[:80])
    return '\n'.join(out)


def main():
    global LOG, BASE_LINES
    LOG = resolve_log()
    if not LOG:
        print('NO_LSPD_LOG')
        return 2
    BASE_LINES = line_count()
    pages = []
    pages.append(collect_page('CURRENT_PAGE', 0.5))
    report = ('p118 diagnostic -- READ ONLY, no visual judgement\n'
              'log=' + LOG + '\nbase_line=' + str(BASE_LINES) + '\n'
              + '\n'.join(pages) + '\n')
    path = os.path.join(OUT, 'p118_diag_2026-11-18.txt')
    io.open(path, 'w', encoding='utf-8', newline='\n').write(report)
    print(path)
    return 0


if __name__ == '__main__':
    sys.exit(main())
