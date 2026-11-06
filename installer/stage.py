"""Assemble the distributable emulator from a whitelist, so nothing personal or stale ships."""
import fnmatch
import os
import re
import shutil
import sys

# usage: stage.py <output dir> [<sdk>\\emulator]
dst = sys.argv[1]
src = sys.argv[2] if len(sys.argv) > 2 else os.path.expandvars(r'%LOCALAPPDATA%\Android\Sdk\emulator')
shutil.rmtree(dst, ignore_errors=True)
os.makedirs(dst)
BAD = ('*.bak', '*.bak*', '*.old', '*.orig', '*.log', '*.tmp', '*.running')


def bad(n):
    return any(fnmatch.fnmatch(n, p) for p in BAD)


# Emulator root: stock runtime DLLs and tools, plus the launcher as emulator.exe.
for n in os.listdir(src):
    p = os.path.join(src, n)
    if os.path.isfile(p) and not bad(n) and n not in ('emulator_test.exe', 'whpx_frame.dat'):
        shutil.copy2(p, dst)
for d in ('bin64', 'lib', 'lib64', 'resources'):
    shutil.copytree(os.path.join(src, d), os.path.join(dst, d),
                    ignore=lambda _, names: [x for x in names if bad(x)])

# QEMU: the one ARM64 target, its DLLs and option ROMs. No firmware: boots with -kernel.
q = os.path.join(src, 'qemu', 'windows-aarch64')
qd = os.path.join(dst, 'qemu', 'windows-aarch64')
os.makedirs(qd)
for n in os.listdir(q):
    p = os.path.join(q, n)
    if os.path.isfile(p) and (n.lower().endswith('.dll') or n in ('qemu-system-aarch64.exe', 'qemu-img.exe', 'COPYING', 'COPYING.LIB', 'README.rst')):
        shutil.copy2(p, qd)
shutil.copytree(os.path.join(q, 'share'), os.path.join(qd, 'share'),
                ignore=lambda _, names: [x for x in names if x.startswith(('edk2', 'doc', 'icons', 'applications'))])

# Bridge.
b = os.path.join(src, 'whpx_bridge')
bd = os.path.join(dst, 'whpx_bridge')
os.makedirs(bd)
files = """GfxstreamPipeServer.exe WhpxLauncher.exe
prepare_whpx_initrd.py vendor_extract.py gfx_autopatch.py auto_minute_maid.py
start_android_ui.sh whpx_loop.sh run_input_daemon.sh run_net_agent.sh
default_net_agent.jar set_wallpaper.jar whpx_input.jar framework_patched.jar
gfx_forwarder whpx_daemon whpx_init kernel_p7_permissive kernel_whpx_universal
ramdisk_whpx_base.img boot_payload.tar mapper3.so default_wallpaper.png
aapm.xml empty_perm.xml handheld_core_hardware.xml""".split()
for f in files:
    p = os.path.join(b, f)
    if os.path.isfile(p):
        shutil.copy2(p, bd)
    else:
        print('MISSING', f)
for n in os.listdir(b):
    if re.fullmatch(r'Whpx(EmulatorBridge|ExtendedControls)(\$.*)?\.class', n):
        shutil.copy2(os.path.join(b, n), bd)
for d in ('com', 'patched', 'patched_p33', 'python', 'modules_whpx_5_15', 'modules_whpx_6_1'):
    if os.path.isdir(os.path.join(b, d)):
        shutil.copytree(os.path.join(b, d), os.path.join(bd, d), ignore=shutil.ignore_patterns('__pycache__', '*.bak*'))
    else:
        print('MISSING DIR', d)

# Version Google's SDK repository will never reach, so Studio never offers to replace this.
p = os.path.join(dst, 'package.xml')
s = open(p, encoding='utf8').read()
s, n = re.subn(r'<major>37</major>\s*<minor>2</minor>\s*<micro>13</micro>', '<major>99</major><minor>0</minor><micro>0</micro>', s, count=1)
assert n == 1
s, n = re.subn(r'<display-name>[^<]*</display-name>', '<display-name>Android Emulator (Windows ARM64, WHPX)</display-name>', s, count=1)
assert n == 1
open(p, 'w', encoding='utf8').write(s)
p = os.path.join(dst, 'source.properties')
s = open(p, encoding='utf8').read()
s, n = re.subn(r'Pkg\.Revision=.*', 'Pkg.Revision=99.0.0', s)
assert n == 1
open(p, 'w', encoding='utf8').write(s)

# Personal data check over the whole tree's text files.
hits = 0
for r, _, fs in os.walk(dst):
    for f in fs:
        if f.endswith(('.py', '.sh', '.xml', '.txt', '.properties', '.ini', '.cfg', '.json')) and os.path.getsize(os.path.join(r, f)) < 2000000:
            t = open(os.path.join(r, f), 'rb').read()
            if os.environ['USERNAME'].encode() in t:
                hits += 1
                print('PERSONAL', os.path.join(r, f)[len(dst):])
print('personal hits', hits)
size = sum(os.path.getsize(os.path.join(r, f)) for r, _, fs in os.walk(dst) for f in fs)
print('stage MB', size // 1048576)
