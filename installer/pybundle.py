"""Copy a minimal Python (stdlib, lz4) into whpx_bridge\\python."""
import os
import shutil
import sys

# Run with an ARM64 Python 3.12 that has lz4 and capstone installed.
src = sys.base_prefix
dst = sys.argv[1]
shutil.rmtree(dst, ignore_errors=True)
os.makedirs(dst)
for f in ('python.exe', 'python3.dll', 'python312.dll', 'vcruntime140.dll', 'vcruntime140_1.dll', 'LICENSE.txt'):
    shutil.copy2(os.path.join(src, f), dst)
skip_dirs = {'test', 'tests', 'tkinter', 'idlelib', 'ensurepip', 'lib2to3', 'turtledemo', '__pycache__', 'venv',
             'pydoc_data', 'unittest', 'distutils', 'wsgiref', 'xmlrpc', 'curses', 'dbm', 'sqlite3', 'site-packages'}


def copy_tree(a, b):
    os.makedirs(b, exist_ok=True)
    for n in os.listdir(a):
        p = os.path.join(a, n)
        if os.path.isdir(p):
            if n not in skip_dirs:
                copy_tree(p, os.path.join(b, n))
        elif not n.endswith(('.pyc', '.chm')):
            shutil.copy2(p, b)


copy_tree(os.path.join(src, 'Lib'), os.path.join(dst, 'Lib'))
os.makedirs(os.path.join(dst, 'Lib', 'site-packages'))
shutil.copytree(os.path.join(src, 'Lib', 'site-packages', 'lz4'), os.path.join(dst, 'Lib', 'site-packages', 'lz4'),
                ignore=shutil.ignore_patterns('__pycache__'))
shutil.copytree(os.path.join(src, 'Lib', 'site-packages', 'capstone'), os.path.join(dst, 'Lib', 'site-packages', 'capstone'),
                ignore=shutil.ignore_patterns('__pycache__', 'include', '*.lib', '*.a'))
os.makedirs(os.path.join(dst, 'DLLs'))
for n in os.listdir(os.path.join(src, 'DLLs')):
    if n.endswith(('.pyd', '.dll')) and not n.startswith(('_tkinter', 'tcl', 'tk', '_test', '_sqlite', 'sqlite', '_ctypes_test', 'winsound')):
        shutil.copy2(os.path.join(src, 'DLLs', n), os.path.join(dst, 'DLLs'))
total = sum(os.path.getsize(os.path.join(r, f)) for r, _, fs in os.walk(dst) for f in fs)
print('python bundle MB', total // 1048576)
