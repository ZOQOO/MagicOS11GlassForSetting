#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""p128 换码后验收。READ ONLY：只读日志 + view tree，不视觉判断。

判据（确定性、可数）：
  - mg-build 0.5.0-p128 计数 > 0 且 com.hihonor.id 主进程 build=p128
  - 账号安全页 HALFWHITE 行数：应为 5（5 行 hwlistpattern_parent 被 suppress）
  - 首次进入 SQUARECARD added 行数：目标 4（四卡首进即出，无需下拉）
  - CONTENTRECTS 是否出现覆盖 y>=2358 的矩形（四卡区）
"""
import io
import os
import re
import subprocess
import time

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
    import xml.etree.ElementTree as ET
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
    raw = remote("su -c 'tail -n +" + str(n + 1) + " " + LOG + "'").splitlines()
    return [re.sub(r'^\[[^\]]*\][^\]]*\]\s*', '', x) for x in raw if x.strip()]


def top():
    o = sh('dumpsys', 'activity', 'activities')
    return o.split('topResumedActivity=')[1].split('\n')[0].strip()[:130] if 'topResumedActivity=' in o else '?'


def snap(label):
    """采一段日志里的 HALFWHITE / SQUARECARD / CONTENTRECTS / build，去重。"""
    n0 = wc()
    time.sleep(1.5)
    lines = since(n0)
    out = ['----- %s -----' % label, 'activity: ' + top()]
    for key, needle in (('HALFWHITE', 'mg-state HALFWHITE'),
                        ('SQUARECARD', 'mg-state SQUARECARD'),
                        ('CONTENTRECTS', 'mg-state CONTENTRECTS'),
                        ('build', 'mg-state build=')):
        v = []
        seen = set()
        for x in lines:
            if needle in x:
                body = x[x.find('mg-state'):]
                if body not in seen:
                    seen.add(body); v.append(body[:250])
        out.append('%s unique: %d' % (key, len(v)))
        for x in v[:12]:
            out.append('  ' + x)
    return '\n'.join(out)


def main():
    global LOG
    LOG = remote("su -c 'ls -t /data/adb/lspd/log/verbose_* | head -1'").strip().splitlines()[0]
    result = ['p128 换码后验收 -- READ ONLY\nlog=' + LOG + '\n']

    # 加载层
    n_p128 = remote("su -c 'grep -a -c \"mg-build 0.5.0-p128\" " + LOG + "'").strip()
    result.append('mg-build p128 count = ' + n_p128)

    # 已在账号安全页则直接采；否则导航进去
    if 'accountsecurity' not in top():
        sh('am', 'start', '-n', 'com.android.settings/.HWSettings'); time.sleep(1.5)
        sh('input', 'tap', '636', '1000'); time.sleep(2.5)
        for _ in range(9):
            sh('input', 'swipe', '636', '2300', '636', '900', '240'); time.sleep(0.2)
        tap('账号安全'); time.sleep(2.0)

    # 首次进入（不动滚动）：看四卡是否首进即出、5 行是否 suppress
    result.append(snap('账号安全-首进(无下拉)'))

    # 滚动一次：看 late 补扫是否补齐
    for k in range(2):
        sh('input', 'swipe', '636', '2300', '636', '1200', '300'); time.sleep(0.8)
        result.append(snap('账号安全-下滑%d' % (k + 1)))

    path = os.path.join(OUT, 'p128_verify_2026-11-21.txt')
    io.open(path, 'w', encoding='utf-8', newline='\n').write('\n'.join(result) + '\n')
    print(path)
    print('\n'.join(result[:50]))


if __name__ == '__main__':
    main()
