#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Derive assert_p127.py from assert_p126.py + p127 timing-fix assertions."""
import io
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
s = io.open(os.path.join(ROOT, 'probe', 'assert_p126.py'), encoding='utf-8').read()

s = s.replace('assert_p126.py', 'assert_p127.py')
s = s.replace('0.5.0-p126', '0.5.0-p127')
s = s.replace('versionCode=154', 'versionCode=155')
s = s.replace('p126 signed APK', 'p127 signed APK')
s = s.replace('fingerprint is p126', 'fingerprint is p127')
s = s.replace('version is p126', 'version is p127')
s = s.replace('carries p126', 'carries p127')
s = s.replace("'p126' in n", "'p127' in n")
s = s.replace("b'0.5.0-p125' not in dex", "b'0.5.0-p126' not in dex")
s = s.replace('no p125 fingerprint', 'no p126 fingerprint')
s = s.replace('no p125', 'no p126')

extra = '''
# ---- p127: static-card discovery timing fix (findings §121/§122) ------------
SC127 = rd(os.path.join(SRC, 'CardScanner.java'))
ck('P01 staticStructureSig folds in list childCount (late inflate detected)',
   'staticStructureSig' in SC127
   and '((ViewGroup) v).getChildCount()' in SC127)
ck('P02 timing fix located inside staticStructureSig, not in frame path',
   (lambda t: t.find('((ViewGroup) v).getChildCount()') > t.find('staticStructureSig(View decor)'))(SC127))
ck('P03 square-card gate criteria unchanged from p120',
   'w < 500 || w > 600' in SC127
   and 'h < 450 || h > 560' in SC127
   and '"account_center_list_other"' in SC127)
ck('P04 no leftover p127 diagnostic (SQREJ) markers',
   not any(k in SC127 for k in ('SQREJ', 'logSquareReject', 'sSqRejLines',
                                'hasAccountSquareParent', 'diagnoseSquareReject')))
ck('P05 SQUARECARD diagnostic still bounded',
   'sSquareCardLines >= 8' in SC127 and 'SQUARECARD' in SC127)
'''

marker = "print('\\n%d checks, %d failures' % (CHECKS[0], len(FAILS)))"
if marker not in s:
    raise SystemExit('summary marker not found')
s = s.replace(marker, extra + '\n' + marker, 1)

out = os.path.join(ROOT, 'probe', 'assert_p127.py')
io.open(out, 'w', encoding='utf-8', newline='\n').write(s)
print('written', out)
