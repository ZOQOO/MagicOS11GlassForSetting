#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""p127 账号安全四卡玻璃定因采集。READ ONLY：只读日志、不改配置、不视觉判断。

目标：确认账号安全页 y=2951 行的"安全中心/更改密码"两卡是否被
isAccountSquareCard 门控命中（SQUARECARD added=1），以及最终玻璃矩形
CONTENTRECTS 是否覆盖 y>=2390。

判据（确定性、可数）：
  - SQUARECARD 每行含 bounds=l,t,r,b 与 added=1；
    若出现 t≈2951 的行 => 下两卡被门控命中（问题在矩形/几何，不在门控）；
    若只有 t≈2390 两行 => 下两卡没进门控（问题在 isAccountSquareCard）。
  - CONTENTRECTS 的 n 与坐标：是否出现 bottom>=2800 的矩形。
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


def remote(cmd):
    return subprocess.run(['adb', '-s', S, 'shell', cmd],
                          capture_output=True).stdout.decode('utf-8', 'ignore')


def ex(*a):
    return subprocess.run(['adb', '-s', S, 'exec-out'] + list(a),
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
    return o.split('topResumedActivity=')[1].split('\n')[0].strip()[:120] if 'topResumedActivity=' in o else '?'


def snap(label):
    """采一段日志里的 SQUARECARD / CONTENTRECTS，按 t 分组去重。"""
    n0 = wc()
    time.sleep(1.2)
    lines = since(n0)
    sq = []
    cr = []
    seen = set()
    for x in lines:
        if 'SQUARECARD' in x:
            body = x[x.find('mg-state'):] if 'mg-state' in x else x
            if body not in seen:
                seen.add(body)
                sq.append(body[:260])
        if 'CONTENTRECTS' in x:
            body = x[x.find('mg-state'):] if 'mg-state' in x else x
            if body not in cr:
                cr.append(body[:260])
    return ['----- %s -----' % label, 'activity: ' + top(),
            'SQUARECARD lines: %d' % len(sq)] + ['  ' + s for s in sq[:16]] + \
           ['CONTENTRECTS lines: %d' % len(cr)] + ['  ' + c for c in cr[:8]]


def enter_security():
    sh('am', 'start', '-n', 'com.android.settings/.HWSettings')
    time.sleep(1.5)
    sh('input', 'tap', '636', '1000')  # 荣耀账号入口
    time.sleep(2.5)
    # 滚到"账号安全"入口
    for _ in range(9):
        sh('input', 'swipe', '636', '2300', '636', '900', '240')
        time.sleep(0.2)
    time.sleep(0.5)
    return tap('账号安全')


def main():
    global LOG
    LOG = remote("su -c 'ls -t /data/adb/lspd/log/verbose_* | head -1'").strip().splitlines()[0]
    result = ['p127 账号安全四卡定因采集 -- READ ONLY\nlog=' + LOG + '\n']

    ok = enter_security()
    result.append('enter_security tap=%s' % ok)
    time.sleep(2.0)

    # 顶部：四卡区刚进入视野
    result.extend(snap('账号安全-顶部'))

    # 向下滑，让 y=2951 行（下两卡）进入可见/已布局
    for k in range(4):
        sh('input', 'swipe', '636', '2300', '636', '1500', '300')
        time.sleep(0.6)
        result.extend(snap('账号安全-下滑%d' % (k + 1)))

    # 再滑回顶部，触发一次重新 discover
    for k in range(4):
        sh('input', 'swipe', '636', '900', '636', '2300', '300')
        time.sleep(0.4)
    result.extend(snap('账号安全-滑回顶部'))

    path = os.path.join(OUT, 'p127_squarecards_2026-11-21.txt')
    io.open(path, 'w', encoding='utf-8', newline='\n').write('\n'.join(result) + '\n')
    print(path)
    print('\n'.join(result[:60]))


if __name__ == '__main__':
    main()
