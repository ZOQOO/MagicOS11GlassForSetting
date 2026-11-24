import io

src = io.open('probe/assert_p122.py', encoding='utf-8').read()
src = src.replace('0.5.0-p122', '0.5.0-p123')
src = src.replace('p122', 'p123')
src = src.replace('versionCode is 150', 'versionCode is 151')
src = src.replace("'versionCode=150'", "'versionCode=151'")
src = src.replace('assert_p122.py', 'assert_p123.py')
src = src.replace('no p121 fingerprint', 'no p122 fingerprint')
src = src.replace("b'0.5.0-p121' not in dex", "b'0.5.0-p122' not in dex")
src = src.replace('D06 dex has no p121', 'D06 dex has no p122')

ER_PATH = "os.path.join(SRC, 'ElementRegistry.java')"
anchor = "print('\\n%d checks, %d failures' % (CHECKS[0], len(FAILS)))"
extra = '''

# ---- p123 real L1/L2 settings navigation -----------------------------------
ER_TXT = rd(os.path.join(SRC, 'ElementRegistry.java'))
ck('N01 real navigation tables present',
   all(k in ER_TXT for k in ('NAV_L1', 'NAV_L2', 'navL1()', 'navL2(')))
ck('N02 L1 navigation has 53 entries',
   (lambda t: t.count('|') == 53)(
      __import__('re').search(r'NAV_L1 = new String\\[\\] \\{(.*?)\\};',
                             ER_TXT, __import__('re').S).group(1))
      if __import__('re').search(r'NAV_L1 = new String\\[\\] \\{(.*?)\\};',
                                 ER_TXT, __import__('re').S) else False)
ck('N03 L2 navigation has 193 valid entries',
   (lambda t: t.count('|') == 193 * 2)(
      __import__('re').search(r'NAV_L2 = new String\\[\\] \\{(.*?)\\};',
                             ER_TXT, __import__('re').S).group(1))
      if __import__('re').search(r'NAV_L2 = new String\\[\\] \\{(.*?)\\};',
                                 ER_TXT, __import__('re').S) else False)
ck('N04 label lookup APIs present',
   'navL1Label(' in ER_TXT and 'navL2Label(' in ER_TXT)
ck('N05 visual editor uses real L1/L2 navigation',
   'navL1()' in SA and 'navL2(' in SA)
ck('N06 technical domain chips removed from visual editor',
   'ElementRegistry.domains()' not in SA[SA.find('openElementBrowser'):])
'''
assert anchor in src
src = src.replace(anchor, extra + anchor, 1)
io.open('probe/assert_p123.py', 'w', encoding='utf-8', newline='\n').write(src)
print('assert_p123.py generated', len(src))
