#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""p133 流量管理下拉玻璃卡顿/延迟 —— 只读采集。

当前前台 com.hihonor.systemmanager NetAssistantMainActivity (slot ext.systemmanager)。
按时间片记录 scroll 中/停止后的 scan(rects/blocks/rows) 与 frames(motion/tail/stall)
状态，判定"延迟出现"是 rect 掉块还是对齐滞后。不截图/不录屏/不改配置。
"""
import io
import os
import re
import subprocess
import time

S = 'A4UF6R6116003189'
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'probe', 'probe-report')


def sh(*a):
    return subprocess.run(['adb', '-s', S, 'shell'] + list(a),
                          capture_output=True).stdout.decode('utf-8', 'ignore')


def remote(cmd):
    return subprocess.run(['adb', '-s', S, 'shell', cmd],
                          capture_output=True).stdout.decode('utf-8', 'ignore')


def logfile():
    return remote("su -c 'ls -t /data/adb/lspd/log/verbose_* | head -1'").strip().splitlines()[0]


LOG = logfile()


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


def slice_report(label, n):
    lines = since(n)
    scans = [x[x.find('scan:'):][:200] for x in lines if 'scan:' in x]
    frames = [x[x.find('frames:'):][:170] for x in lines if 'frames:' in x]
    # 去重保序
    def uniq(a):
        out = []
        for x in a:
            if x not in out:
                out.append(x)
        return out
    scans, frames = uniq(scans), uniq(frames)
    return ['--- %s ---' % label,
            '  scan=%d frames=%d' % (len(scans), len(frames)),
            ] + ['  S ' + x for x in scans[-4:]] + ['  F ' + x for x in frames[-4:]]


def main():
    result = ['p133 流量管理下拉玻璃卡顿只读采集\nlog=%s\n' % LOG,
              'activity: ' + top()]
    # 页面已在流量管理；若不是则导航（只读手势）
    if 'NetAssistant' not in top():
        sh('am', 'start', '-n', 'com.hihonor.systemmanager/com.hihonor.netassistant.ui.NetAssistantMainActivity')
        time.sleep(2)

    n0 = wc(); time.sleep(1.0)
    result += slice_report('IDLE 静止', n0)

    # 连续下滑（下拉加载更多），每 200ms 采一片
    for i in range(5):
        n = wc()
        sh('input', 'swipe', '640', '600', '640', '2300', '300')
        time.sleep(0.2)
        result += slice_report('PULL-%d (下拉中+200ms)' % (i + 1), n)

    # 松手后按时间序列
    for ms, label in ((100, 'RELEASE+100ms'), (300, 'RELEASE+400ms'),
                      (600, 'RELEASE+1000ms'), (1000, 'RELEASE+2000ms')):
        n = wc(); time.sleep(ms / 1000.0)
        result += slice_report(label, n)

    path = os.path.join(OUT, 'p133_flow_lag_2026-11-21.txt')
    io.open(path, 'w', encoding='utf-8', newline='\n').write('\n'.join(result) + '\n')
    print(path)
    print('\n'.join(result[:70]))


if __name__ == '__main__':
    main()
