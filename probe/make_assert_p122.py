import io

src = io.open('probe/assert_p121.py', encoding='utf-8').read()

# version migration p121 -> p122 (order matters: 0.5.0-p121 first)
src = src.replace('0.5.0-p121', '0.5.0-p122')
src = src.replace('p121', 'p122')
src = src.replace('versionCode is 149', 'versionCode is 150')
src = src.replace("'versionCode=149'", "'versionCode=150'")
src = src.replace('assert_p121.py', 'assert_p122.py')
src = src.replace('no p120 fingerprint', 'no p121 fingerprint')
src = src.replace("b'0.5.0-p120' not in dex", "b'0.5.0-p121' not in dex")
src = src.replace('D06 dex has no p120', 'D06 dex has no p121')

# Insert visual-editor assertions right before the final summary print.
anchor = "print('\\n%d checks, %d failures' % (CHECKS[0], len(FAILS)))"
extra = '''

# ---- p122 visual-edit mode ------------------------------------------------
ck('X01 visual wireframe view class present',
   'VisualWireframeView' in SA and 'extends View' in SA)
ck('X02 wireframe draws the phone screen regions',
   all(k in SA for k in ('drawRoundRect', 'drawRect', 'drawCircle')))
ck('X03 wireframe has selectable state + hit test',
   all(k in SA for k in ('selectedNo', 'onTouchEvent', 'invalidate()')))
ck('X04 visual edit title shown',
   '界面元素' in SA and ('视觉编辑' in SA or '点亮' in SA))
ck('X05 plain-language explanations cover all ten visual numbers',
   all(k in SA for k in ('顶栏标题', '毛玻璃', '最底层背景', '白卡',
                         '分组标题', '主标题', '摘要', '图标',
                         '动态', '分隔线')))
ck('X06 keep items (②⑧) shown as locked, no config key',
   '保留不可改' in SA and 'keep()' in SA)
ck('X07 wireframe is read-only (no config writes in browser region)',
   (lambda t: not any(x in t for x in ('ConfigStore.write', 'writeNow(',
                                      'writeLive(', 'notifyChanged(')))(SA[SA.find('openElementBrowser'):]))
ck('X08 no new source file (visual view is an inner class)',
   not os.path.isfile(os.path.join(SRC, 'VisualWireframeView.java')))
'''
assert anchor in src, 'anchor missing'
src = src.replace(anchor, extra + anchor, 1)

io.open('probe/assert_p122.py', 'w', encoding='utf-8', newline='\n').write(src)
print('assert_p122.py generated', len(src))
