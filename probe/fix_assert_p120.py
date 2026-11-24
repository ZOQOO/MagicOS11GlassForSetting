#!/usr/bin/env python
# -*- coding: utf-8 -*-
import io
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
p = os.path.join(ROOT, 'probe', 'assert_p120.py')
s = io.open(p, encoding='utf-8').read()

s = s.replace("'isAccountSquareCard' in SC and", "'isAccountSquareCard' in CS and")
s = s.replace('"w < 500 || w > 600"', "'w < 500 || w > 600'")
s = s.replace('"h < 450 || h > 560"', "'h < 450 || h > 560'")
s = s.replace('"sSquareCardLines >= 8"', "'sSquareCardLines >= 8'")
s = s.replace('"SQUARECARD"', "'SQUARECARD'")
# D08 already checks CardScanner; make sure no ConfigContract token remains.
s = s.replace("for t in ('ConfigContract', 'CardScanner',", "for t in ('CardScanner',")

io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('fixed', len(s))
