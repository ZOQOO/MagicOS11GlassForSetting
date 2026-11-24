import io

src = io.open('probe/assert_p123.py', encoding='utf-8').read()
src = src.replace('0.5.0-p123', '0.5.0-p124')
src = src.replace('p123', 'p124')
src = src.replace('versionCode is 151', 'versionCode is 152')
src = src.replace("'versionCode=151'", "'versionCode=152'")
src = src.replace('assert_p123.py', 'assert_p124.py')
src = src.replace('no p122 fingerprint', 'no p123 fingerprint')
src = src.replace("b'0.5.0-p122' not in dex", "b'0.5.0-p123' not in dex")
src = src.replace('D06 dex has no p122', 'D06 dex has no p123')

anchor = "print('\\n%d checks, %d failures' % (CHECKS[0], len(FAILS)))"
extra = '''

# ---- p124 ID-hit diagnostic hook -------------------------------------------
GE_TXT = rd(os.path.join(SRC, 'GlassEntry.java'))
ER_TXT2 = rd(os.path.join(SRC, 'ElementRegistry.java'))
ck('H01 PreferenceBindHooker present',
   'PreferenceBindHooker' in GE_TXT and 'implements XposedInterface.Hooker' in GE_TXT)
ck('H02 PREFBIND diagnostic log emitted',
   'PREFBIND' in GE_TXT and 'mg-state PREFBIND' in GE_TXT)
ck('H03 hook uses getKey via reflection (no androidx import)',
   'androidx.preference' not in GE_TXT and 'getKey' in GE_TXT)
ck('H04 last activity class tracked for screen reverse lookup',
   'sLastActivityClass' in GE_TXT and 'screenForActivity' in GE_TXT)
ck('H05 KEY_INDEX + lookup APIs present',
   'KEY_INDEX' in ER_TXT2 and 'globalIdFor(' in ER_TXT2 and 'dimsFor(' in ER_TXT2)
ck('H06 hook body is logging only (no render/config writes)',
   (lambda t: not any(x in t for x in ('ConfigStore.write', 'writeNow(',
                                      'writeLive(', 'notifyChanged(')))(
       GE_TXT[GE_TXT.find('class PreferenceBindHooker'):
              GE_TXT.find('class PreferenceBindHooker') + 3500]))
'''
assert anchor in src
src = src.replace(anchor, extra + anchor, 1)
io.open('probe/assert_p124.py', 'w', encoding='utf-8', newline='\n').write(src)
print('assert_p124.py generated', len(src))
