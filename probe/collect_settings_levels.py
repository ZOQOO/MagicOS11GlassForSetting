#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Read-only multi-level Settings collector -- taps UI by text, logs only."""
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


def sh(*args):
    return subprocess.run(['adb', '-s', S, 'shell'] + list(args),
                          capture_output=True).stdout.decode('utf-8', 'ignore')


def ex(*args):
    return subprocess.run(['adb', '-s', S, 'exec-out'] + list(args),
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


def tap_text(text, root=None):
    r = root if root is not None else dump()
    if r is None:
        return False
    for n in r.iter('node'):
        if (n.get('text', '') or '').strip() == text:
            z = [int(v) for v in re.findall(r'\d+', n.get('bounds', ''))]
            if len(z) >= 4 and z[2] <= 1272 and z[3] <= 2800:
                sh('input', 'tap', str((z[0] + z[2]) // 2), str((z[1] + z[3]) // 2))
                return True
    return False


def home():
    sh('am', 'start', '-n', 'com.android.settings/.HWSettings')
    time.sleep(1.8)
    return dump()


def lines_count():
    o = remote("su -c 'wc -l < " + LOG + "'")
    try:
        return int(o.strip().splitlines()[0])
    except (ValueError, IndexError):
        return 0


def since(n):
    return remote("su -c 'tail -n +" + str(n + 1) + " " + LOG + "'").splitlines()


def top():
    o = sh('dumpsys', 'activity', 'activities')
    return o.split('topResumedActivity=')[1].split('\n')[0].strip()[:115] if 'topResumedActivity=' in o else '?'


def wrap(name, n0, at):
    lines = since(n0)
    out = ['\n===== %s =====' % name, 'activity: ' + at]
    for key, needle in (('build', 'mg-state build='),
                        ('colorcand', 'COLORCAND'),
                        ('node', 'CONTENTNODE'),
                        ('rects', 'CONTENTRECTS'),
                        ('unified', 'mg-state UNIFIED')):
        v = [re.sub(r'^\[[^\]]*\][^\]]*\]\s*', '', x)
             for x in lines if needle in x]
        out.append('%s: %d' % (key, len(v)))
        for x in v[:10]:
            out.append('  ' + x[:240])
    return '\n'.join(out)


def main():
    global LOG
    LOG = remote("su -c 'ls -t /data/adb/lspd/log/verbose_* | head -1'").strip().splitlines()[0]
    result = ['p119 multi-level read-only collector\nlog=' + LOG + '\n']

    root = home()
    n0 = lines_count(); time.sleep(2)
    result.append(wrap('L1_settings_home', n0, top()))

    for entry in ('WLAN', '蓝牙', '移动网络', '荣耀互联', '桌面和个性化', '显示和亮度'):
        root = home()
        ok = tap_text(entry, root)
        time.sleep(2.5)
        n0 = lines_count(); time.sleep(1.5)
        result.append(wrap('L2_' + entry + ('_tapped' if ok else '_NOT_FOUND'), n0, top()))

    # One L3 detail: from WLAN page tap the connected network name.
    root = home()
    if tap_text('WLAN', root):
        time.sleep(2.5)
        r = dump()
        tapped = False
        if r is not None:
            for n in r.iter('node'):
                t = (n.get('text', '') or '').strip()
                if 'TP-LINK' in t:
                    z = [int(v) for v in re.findall(r'\d+', n.get('bounds', ''))]
                    if len(z) >= 4:
                        sh('input', 'tap', str((z[0] + z[2]) // 2), str((z[1] + z[3]) // 2))
                        tapped = True
                        break
        time.sleep(2.5)
        n0 = lines_count(); time.sleep(1.5)
        result.append(wrap('L3_wlan_detail' + ('_tapped' if tapped else '_no_network_node'), n0, top()))

    path = os.path.join(OUT, 'p119_settings_levels_2026-11-18.txt')
    io.open(path, 'w', encoding='utf-8', newline='\n').write('\n'.join(result) + '\n')
    print(path)


if __name__ == '__main__':
    main()
