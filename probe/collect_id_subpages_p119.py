#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""p119 account-center subpages content probe. READ ONLY, no visual judgement.

Now that 账号中心 main process runs p119 (fixed probe budget), collect the
content-area measured Drawable for the three reported symptoms:
  ① 账号安全 half-screen white block
  ② 四个方形卡 no glass background
  ③ 隐私中心 lower white band
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


def sh(*a):
    return subprocess.run(['adb', '-s', S, 'shell'] + list(a),
                          capture_output=True).stdout.decode('utf-8', 'ignore')


def ex(*a):
    return subprocess.run(['adb', '-s', S, 'exec-out'] + list(a),
                          capture_output=True).stdout.decode('utf-8', 'ignore')


def remote(cmd):
    return subprocess.run(['adb', '-s', S, 'shell', cmd],
                          capture_output=True).stdout.decode('utf-8', 'ignore')


def dump():
    for _ in range(6):
        s = ex('uiautomator', 'dump', '/dev/stdout')
        a = s.find('<?xml'); b = s.find('</hierarchy>')
        if a >= 0 and b > a:
            return ET.fromstring(s[a:b + len('</hierarchy>')])
        time.sleep(1)
    return None


def tap(t):
    r = dump()
    if r is None:
        return False
    for n in r.iter('node'):
        if (n.get('text', '') or '').strip() == t:
            z = [int(v) for v in re.findall(r'\d+', n.get('bounds', ''))]
            if len(z) >= 4 and z[2] <= 1272 and z[3] <= 2800:
                sh('input', 'tap', str((z[0] + z[2]) // 2), str((z[1] + z[3]) // 2))
                return True
    return False


def wc():
    o = remote("su -c 'wc -l < " + LOG + "'")
    try:
        return int(o.strip().splitlines()[0])
    except (ValueError, IndexError):
        return 0


def since(n):
    return remote("su -c 'tail -n +" + str(n + 1) + " " + LOG + "'").splitlines()


def clean(lines):
    return [re.sub(r'^\[[^\]]*\][^\]]*\]\s*', '', x) for x in lines if x.strip()]


def top():
    o = sh('dumpsys', 'activity', 'activities')
    return o.split('topResumedActivity=')[1].split('\n')[0].strip()[:120] if 'topResumedActivity=' in o else '?'


def enter_center():
    sh('am', 'start', '-n', 'com.android.settings/.HWSettings')
    time.sleep(1.5)
    sh('input', 'tap', '636', '1000')  # 荣耀账号入口
    time.sleep(2.5)


def collect(name):
    n0 = wc()
    time.sleep(2.0)
    lines = clean(since(n0))
    out = ['\n===== %s =====' % name, 'activity: ' + top()]
    for key, needle in (('build', 'mg-state build='),
                        ('node', 'CONTENTNODE'),
                        ('rects', 'CONTENTRECTS'),
                        ('colorcand', 'COLORCAND'),
                        ('unified', 'mg-state UNIFIED')):
        v = [x for x in lines if needle in x]
        out.append('%s: %d' % (key, len(v)))
        # dedupe by full body for node/colorcand
        if key in ('node', 'colorcand'):
            seen = set()
            uniq = []
            for x in v:
                body = x[x.find('mg-state'):] if 'mg-state' in x else x
                if body in seen:
                    continue
                seen.add(body)
                uniq.append(x)
            v = uniq
            out.append('  (unique: %d)' % len(v))
        for x in v[:40]:
            out.append('  ' + x[:280])
    return '\n'.join(out)


def main():
    global LOG
    LOG = remote("su -c 'ls -t /data/adb/lspd/log/verbose_* | head -1'").strip().splitlines()[0]
    result = ['p119 account-center subpages -- READ ONLY, log only\nlog=' + LOG + '\n']

    # baseline: current page
    result.append(collect('CURRENT'))

    for entry in ('账号安全', '隐私中心', '帮助与客服'):
        enter_center()
        for _ in range(9):
            sh('input', 'swipe', '636', '2300', '636', '900', '240')
            time.sleep(0.25)
        time.sleep(0.6)
        ok = tap(entry)
        result.append(collect('%s (tap=%s)' % (entry, ok)))

    path = os.path.join(OUT, 'p119_id_subpages_2026-11-18.txt')
    io.open(path, 'w', encoding='utf-8', newline='\n').write('\n'.join(result) + '\n')
    print(path)


if __name__ == '__main__':
    main()
