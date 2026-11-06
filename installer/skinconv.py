"""Rewrite .webp skin images as PNG data, keeping the file names the skin layouts refer to.

Studio decodes WebP through image_converter.dll, which is x64 and cannot load into its ARM64
runtime. ImageIO picks the decoder from the file contents, so PNG data under the old name works.
usage: skinconv.py <dir>
"""
import os
import sys

from PIL import Image

root = sys.argv[1]
n = skipped = 0
before = after = 0
for r, _, fs in os.walk(root):
    for f in fs:
        if not f.lower().endswith('.webp'):
            continue
        p = os.path.join(r, f)
        with open(p, 'rb') as h:
            head = h.read(12)
        if head[:4] != b'RIFF':
            skipped += 1
            continue
        before += os.path.getsize(p)
        im = Image.open(p)
        im.load()
        size = im.size
        tmp = p + '.tmp'
        im.save(tmp, format='PNG', optimize=True)
        im.close()
        chk = Image.open(tmp)
        assert chk.size == size
        chk.close()
        os.replace(tmp, p)
        after += os.path.getsize(p)
        n += 1
print('converted', n, 'skipped', skipped, 'MB', before // 1048576, '->', after // 1048576)
