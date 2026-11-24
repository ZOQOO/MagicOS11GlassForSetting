import io

src = io.open('probe/assert_p124.py', encoding='utf-8').read()
src = src.replace('0.5.0-p124', '0.5.0-p125')
src = src.replace('p124', 'p125')
src = src.replace('versionCode is 152', 'versionCode is 153')
src = src.replace("'versionCode=152'", "'versionCode=153'")
src = src.replace('assert_p124.py', 'assert_p125.py')
src = src.replace('no p123 fingerprint', 'no p124 fingerprint')
src = src.replace("b'0.5.0-p123' not in dex", "b'0.5.0-p124' not in dex")
src = src.replace('D06 dex has no p123', 'D06 dex has no p124')

anchor = "print('\\n%d checks, %d failures' % (CHECKS[0], len(FAILS)))"
extra = '''

# ---- p125 screen-independent key hit fix -----------------------------------
GE2 = rd(os.path.join(SRC, 'GlassEntry.java'))
ER3 = rd(os.path.join(SRC, 'ElementRegistry.java'))
ck('K01 global key APIs present',
   all(k in ER3 for k in ('globalIdByKey(', 'dimsByKey(', 'keyAmbiguity(',
                          'keyRows(')))
ck('K02 hook falls back to global key when screen missing',
   'globalIdByKey(key)' in GE2 and 'keyAmbiguity(key)' in GE2)
ck('K03 strict screen+key path kept when screen known',
   (lambda t: t.find('globalIdFor(screen, key)') >= 0
              and t.find('screen != null') >= 0)(GE2))
ck('K04 amb field emitted in PREFBIND log',
   '" amb=" + amb' in GE2 and 'int amb' in GE2)
ck('K05 logPrefBind signature carries amb',
   'String gid, String dims, int amb, View v' in GE2)
ck('K06 still logging-only (no render/config writes)',
   not any(x in GE2[GE2.find('class PreferenceBindHooker'):
                    GE2.find('class PreferenceBindHooker') + 4000]
           for x in ('ConfigStore.write', 'writeNow(', 'writeLive(',
                     'notifyChanged(', 'setBackground(', 'setVisibility(')))
'''
assert anchor in src
src = src.replace(anchor, extra + anchor, 1)
io.open('probe/assert_p125.py', 'w', encoding='utf-8', newline='\n').write(src)
print('assert_p125.py generated', len(src))
