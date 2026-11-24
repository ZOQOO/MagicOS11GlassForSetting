import io

src = io.open('probe/assert_p125.py', encoding='utf-8').read()
src = src.replace('0.5.0-p125', '0.5.0-p126')
src = src.replace('p125', 'p126')
src = src.replace('versionCode is 153', 'versionCode is 154')
src = src.replace("'versionCode=153'", "'versionCode=154'")
src = src.replace('assert_p125.py', 'assert_p126.py')
src = src.replace('no p124 fingerprint', 'no p125 fingerprint')
src = src.replace("b'0.5.0-p124' not in dex", "b'0.5.0-p125' not in dex")
src = src.replace('D06 dex has no p124', 'D06 dex has no p125')

anchor = "print('\\n%d checks, %d failures' % (CHECKS[0], len(FAILS)))"
extra = '''

# ---- p126 per-global-id config + font application --------------------------
CC2 = rd(os.path.join(SRC, 'ConfigContract.java'))
CS2 = rd(os.path.join(SRC, 'ConfigStore.java'))
GE3 = rd(os.path.join(SRC, 'GlassEntry.java'))
SA3 = rd(os.path.join(SRC, 'SettingsActivity.java'))
ck('R01 EL_RULES key present with empty default',
   'EL_RULES("el.rules", "")' in CC2)
ck('R02 rule parse/set APIs present',
   all(k in CC2 for k in ('elRuleGet(', 'elRuleSet(', 'elRuleConfigurable(')))
ck('R03 writeElRule API writes via existing chain',
   'writeElRule(' in CS2 and 'ConfigStore.write' in CS2)
ck('R04 hook applies font by gid',
   'applyElFont(' in GE3 and 'elRuleGet(' in GE3)
ck('R05 ELFONT diagnostic log emitted',
   'ELFONT' in GE3 and 'mg-state ELFONT' in GE3)
ck('R06 title/summary ids targeted',
   '0x1020016' in GE3 and '0x1020010' in GE3)
ck('R07 UI per-element font editor present',
   'fontColor' in SA3 and 'fontScale' in SA3 and 'writeElRule' in SA3)
ck('R08 keep (②⑧) blocks editing in UI',
   'configurable(' in SA3 and '保留不可改' in SA3)
ck('R09 no render-chain files touched by this feature',
   not any(k in GE3 for k in ('setBackground(', 'setVisibility(',
                              'setAlpha(', 'ConfigStore.write')))
'''
assert anchor in src
src = src.replace(anchor, extra + anchor, 1)
io.open('probe/assert_p126.py', 'w', encoding='utf-8', newline='\n').write(src)
print('assert_p126.py generated', len(src))
