#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""p119: fix the p117 content-area probe budget starvation. LOG ONLY."""
import io
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RC = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'magicglass',
                  'RenderController.java')
GE = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'magicglass',
                  'GlassEntry.java')
PROP = os.path.join(ROOT, 'app', 'src', 'main', 'resources', 'META-INF',
                    'xposed', 'module.prop')

s = io.open(RC, encoding='utf-8').read()
assert '0.5.0-p119' not in s, 'already applied'

old = '    private int contentLogLines;'
new = '''    /** 0.5.0-p119: the content probe has its OWN budget. In p117 the
     *  top-band probe consumed the shared 60-line budget with 104 identical
     *  rows, so the content band (the three white blocks + four cards) was
     *  never measured. Log-only change: no strip predicate is touched. */
    private int contentLogLines;
    private int contentNodeLines;
    private int contentRectLines;
    private java.util.HashSet<String> contentSeen;'''
assert s.count(old) == 1
s = s.replace(old, new, 1)

old = '        contentLogLines = 0;'
new = '''        contentLogLines = 0;
        contentNodeLines = 0;
        contentRectLines = 0;
        if (contentSeen != null) contentSeen.clear();'''
assert s.count(old) == 1
s = s.replace(old, new, 1)

old = '''                if (contentLogLines < 60
                        && (now - lastContentLogMs > 150L || solid || measured)) {
                    lastContentLogMs = now;
                    contentLogLines++;
                    diagContentNodes++;'''
new = '''                // 0.5.0-p119: independent budget + per-id dedupe. The top
                // band can no longer starve the content measurement.
                String logKey = rid + "|" + topBarLoc[1] + "|" + w + "x" + h;
                if (contentSeen == null) {
                    contentSeen = new java.util.HashSet<String>();
                }
                boolean fresh = contentSeen.add(logKey);
                if (contentNodeLines < 60 && fresh) {
                    lastContentLogMs = now;
                    contentNodeLines++;
                    diagContentNodes++;'''
assert s.count(old) == 1
s = s.replace(old, new, 1)

old = '                if (contentLogLines < 60) probeContentRects();'
new = '''                if (contentRectLines < 8) {
                    contentRectLines++;
                    probeContentRects();
                }'''
assert s.count(old) == 1
s = s.replace(old, new, 1)

io.open(RC, 'w', encoding='utf-8', newline='\n').write(s)
print('RenderController probe budget fixed')

g = io.open(GE, encoding='utf-8').read()
assert 'BUILD = "0.5.0-p118";' in g
g = g.replace('BUILD = "0.5.0-p118";', 'BUILD = "0.5.0-p119";', 1)
io.open(GE, 'w', encoding='utf-8', newline='\n').write(g)

p = io.open(PROP, encoding='utf-8').read()
assert 'version=0.5.0-p118' in p
p = p.replace('version=0.5.0-p118', 'version=0.5.0-p119', 1)
p = p.replace('versionCode=146', 'versionCode=147', 1)
io.open(PROP, 'w', encoding='utf-8', newline='\n').write(p)
print('version p119 / code 147')
