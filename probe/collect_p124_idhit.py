"""Collect p124 PREFBIND (custom-ID hit) diagnostic logs, read-only.

Usage: python probe/collect_p124_idhit.py
Enters several real Settings L2 pages by text tap (from the view tree),
then reads the newest lspd log for `mg-state PREFBIND` lines and aggregates:
 - total lines / distinct screens / distinct keys / gid hit count
 - bounded sample per screen
Writes probe/probe-report/p124_idhit_2026-11-20.txt (standalone readable txt).
No screenshots, no recording, no visual model. Never writes to the phone.
"""
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from collections import Counter

S = 'A4UF6R6116003189'
OUT = 'probe/probe-report/p124_idhit_2026-11-20.txt'


def sh(*args):
    return subprocess.run(['adb', '-s', S, 'shell'] + list(args),
                          capture_output=True).stdout.decode('utf-8', 'ignore')


def root():
    for _ in range(6):
        s = subprocess.run(['adb', '-s', S, 'exec-out', 'uiautomator', 'dump',
                            '/dev/stdout'], capture_output=True).stdout.decode(
                                'utf-8', 'ignore')
        a, b = s.find('<?xml'), s.find('</hierarchy>')
        if a >= 0 and b > a:
            return ET.fromstring(s[a:b + len('</hierarchy>')])
        time.sleep(1)
    return None


def tap_text(label):
    r = root()
    if r is None:
        return False
    for n in r.iter('node'):
        if (n.get('text') or '').strip() == label:
            z = [int(x) for x in re.findall(r'\d+', n.get('bounds', ''))]
            if len(z) >= 4:
                sh('input', 'tap', str((z[0] + z[2]) // 2),
                   str((z[1] + z[3]) // 2))
                return True
    return False


LOG = sh("su -c 'ls -t /data/adb/lspd/log/verbose_* | head -1'").strip()
sh('am', 'start', '-n', 'com.android.settings/.HWSettings')
time.sleep(2)
pages = ['WLAN', '蓝牙', '显示', '声音', '应用和通知']
for label in pages:
    sh('am', 'start', '-n', 'com.android.settings/.HWSettings')
    time.sleep(1.5)
    tap_text(label)
    time.sleep(2.0)

raw = sh('su -c', 'grep -a "PREFBIND" ' + LOG)
lines = [x for x in raw.splitlines() if 'PREFBIND' in x]
rows = []
for x in lines:
    d = dict(re.findall(r'(\w+)=([^ ]+)', x))
    rows.append(d)

screens = Counter(r.get('screen', '-') for r in rows)
gids = [r for r in rows if r.get('gid') not in (None, '', '-')]
keys = Counter(r.get('key', '-') for r in rows)

out = []
out.append('p124 自定义 ID 命中诊断（只读日志，无视觉判断）')
out.append('=' * 60)
out.append('日志: ' + LOG)
out.append('mg-build p124 计数: ' + sh('su -c',
           'grep -a -c "mg-build 0.5.0-p124" ' + LOG).strip())
out.append('PREFBIND 总行数: %d | 有 gid 命中: %d' % (len(rows), len(gids)))
out.append('不同 screen: %d | 不同 key: %d' % (len(screens), len(keys)))
out.append('')
out.append('按 screen 分布:')
for k, v in screens.most_common():
    out.append('  %-30s %d' % (k, v))
out.append('')
out.append('命中样本（gid 非空，最多 20 条）:')
for r in gids[:20]:
    out.append('  screen=%-24s key=%-38s gid=%-42s dims=%-12s %s' % (
        r.get('screen', '-'), r.get('key', '-'), r.get('gid', '-'),
        r.get('dims', '-'), r.get('bounds', '-')))
out.append('')
out.append('若总行数为 0：检查 mg-build p124 计数（0=宿主进程未换码）或 hook 未注册日志。')
out.append('若行数>0 但 gid 全空：说明 screen 或 key 对不上，需看 screen/key 分布换桥。')

txt = '\n'.join(out)
with open(OUT, 'w', encoding='utf-8') as f:
    f.write(txt + '\n')
print(txt)
