#!/usr/bin/env python
# -*- coding: utf-8 -*-
import io
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
s = io.open(os.path.join(ROOT, 'probe', 'assert_p119.py'), encoding='utf-8').read()
s = s.replace('assert_p119.py', 'assert_p120.py')
s = s.replace('0.5.0-p119', '0.5.0-p120')
s = s.replace('versionCode=147', 'versionCode=148')
s = s.replace('versionCode=146', 'versionCode=147')
s = s.replace('p119 signed APK', 'p120 signed APK')
s = s.replace('fingerprint is p119', 'fingerprint is p120')
s = s.replace('version is p119', 'version is p120')
s = s.replace('carries p119', 'carries p120')
s = s.replace("'p119' in n", "'p120' in n")
s = s.replace("b'0.5.0-p118' not in dex", "b'0.5.0-p119' not in dex")
s = s.replace('no p118 fingerprint', 'no p119 fingerprint')

extra = '''ck('Q01 square-card gate exists with measured parent id',
   'isAccountSquareCard' in SC and '"account_center_list_other"' in SC)
ck('Q02 gate is an independent entry, not inside insideRecyclerView',
   (lambda t: t.find('boolean squareCard = isAccountSquareCard(v)') >= 0
    and t.find('boolean squareCard = isAccountSquareCard(v)')
        < t.find('} else if (hasBg || isCardName(v))'))(SC))
ck('Q03 square-card size window matches measured cards',
   'w < 500 || w > 600' in SC and 'h < 450 || h > 560' in SC)
ck('Q04 square-card diagnostic is bounded',
   'sSquareCardLines >= 8' in SC and 'SQUARECARD' in SC)

'''
# p119 assert defines SC? check; if not, define it before extra
if 'SC = io.open' not in s and 'SC = rd' not in s:
    extra = ("SC = io.open(os.path.join(SRC, 'CardScanner.java'), "
             "encoding='utf-8').read()\n" + extra)

marker = "print('\\n%d checks, %d failures'"
if marker not in s:
    # find the actual summary line
    for line in s.splitlines():
        if 'checks, %d failures' in line:
            marker = line
            break
if marker not in s:
    raise SystemExit('marker not found')
s = s.replace(marker, extra + marker, 1)
out = os.path.join(ROOT, 'probe', 'assert_p120.py')
io.open(out, 'w', encoding='utf-8', newline='\n').write(s)
print('written', len(s))
