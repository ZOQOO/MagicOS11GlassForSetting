#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Build assert_p119.py from the p118 template + p119 probe-budget checks."""
import io
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
src = os.path.join(ROOT, 'probe', 'assert_p118.py')
dst = os.path.join(ROOT, 'probe', 'assert_p119.py')

s = io.open(src, encoding='utf-8').read()
s = s.replace('assert_p118.py', 'assert_p119.py')
s = s.replace('0.5.0-p118', '0.5.0-p119')
s = s.replace('p118 signed APK', 'p119 signed APK')
s = s.replace('p118 fingerprint', 'p119 fingerprint')
s = s.replace('versionCode=146', 'versionCode=147')
s = s.replace('versionCode=145', 'versionCode=146')
s = s.replace("b'0.5.0-p117' not in dex", "b'0.5.0-p118' not in dex")
s = s.replace("'no p117 fingerprint'", "'no p118 fingerprint'")
s = s.replace('archived p118 source snapshots', 'archived p119 source snapshots')

extra = '''ck('P01 content probe has its OWN line budget field',
   'private int contentNodeLines;' in RC and 'private int contentRectLines;' in RC)
ck('P02 content node budget independent from top-band budget',
   'contentNodeLines < 60' in RC)
ck('P03 content rects have a separate small budget',
   'contentRectLines < 8' in RC)
ck('P04 content node log dedupes by id|top|size',
   'contentSeen.add(logKey)' in RC)
ck('P05 all probe budgets reset on detach',
   all(k in RC[RC.find('public void detach()'):
               RC.find('public void detach()') + 2400]
       for k in ('contentLogLines = 0', 'contentNodeLines = 0',
                 'contentRectLines = 0', 'contentSeen.clear()')))

'''
marker = "print('\\n%d checks, %d failures'"
if marker not in s:
    raise SystemExit('marker not found')
s = s.replace(marker, extra + marker, 1)
io.open(dst, 'w', encoding='utf-8', newline='\n').write(s)
print('assert_p119.py written:', len(s))
