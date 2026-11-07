"""Assemble Android Studio for Windows ARM64 from Google's release and JetBrains' ARM64 natives.

usage: assemble_studio.py <studio> <idea> <layoutlib_dir> <out> [--label TEXT]

  <studio>         Google's Android Studio for Windows, extracted (the folder holding product-info.json)
  <idea>           IntelliJ IDEA for Windows ARM64 on the same platform build, extracted
  <layoutlib_dir>  folder with ARM64 layoutlib_jni.dll and libandroid_runtime.dll
  <out>            output folder, replaced

Google's Java code is used unchanged: it is the same on every platform. Only the parts that load
into the IDE's own process have to be ARM64: the Java runtime, the launcher and its helpers, and the
JNI libraries. Each one is taken from IntelliJ only after checking that IntelliJ is on the same
platform build, so the native and Java halves match. Programs Studio starts as separate processes
(LLDB, simpleperf, trace processor, clangd, llama-server) stay x64 and run under emulation.
"""
import argparse
import ctypes
import json
import os
import shutil
import struct
import sys
import zipfile
from ctypes import wintypes

ap = argparse.ArgumentParser()
ap.add_argument('studio')
ap.add_argument('idea')
ap.add_argument('layoutlib')
ap.add_argument('out')
ap.add_argument('--label', default='Unofficial Windows ARM64 build')
a = ap.parse_args()


def info(root):
    with open(os.path.join(root, 'product-info.json'), encoding='utf8') as f:
        return json.load(f)


def machine(p):
    with open(p, 'rb') as f:
        d = f.read(1024)
        if d[:2] != b'MZ':
            return None
        f.seek(struct.unpack_from('<I', d, 0x3c)[0] + 4)
        return struct.unpack('<H', f.read(2))[0]


ARM64 = 0xAA64

# 1. Same platform build, or the natives would not match the Java code calling them.
s_info, i_info = info(a.studio), info(a.idea)
platform = s_info['buildNumber'].split('.')[:3]
if i_info['buildNumber'].split('.')[:3] != platform:
    sys.exit('platform mismatch: Studio %s, IntelliJ %s' % (s_info['buildNumber'], i_info['buildNumber']))


def release(root):
    r = {}
    for line in open(os.path.join(root, 'jbr', 'release'), encoding='utf8'):
        if '=' in line:
            k, v = line.split('=', 1)
            r[k] = v.strip().strip('"')
    return r


if release(a.studio)['JAVA_VERSION'] != release(a.idea)['JAVA_VERSION']:
    sys.exit('runtime mismatch: %s vs %s' % (release(a.studio)['JAVA_VERSION'], release(a.idea)['JAVA_VERSION']))
print('Studio', s_info['version'], '| IntelliJ', i_info['buildNumber'], '| runtime', release(a.idea)['JAVA_VERSION'])

# 2. Start from Google's tree.
if os.path.exists(a.out):
    shutil.rmtree(a.out)
shutil.copytree(a.studio, a.out)
S, I, O = a.studio, a.idea, a.out


def put(src, dst, need_arm64=True):
    if need_arm64 and machine(src) not in (ARM64, None):
        sys.exit('not ARM64: ' + src)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    shutil.copy2(src, dst)


# 3. Java runtime.
shutil.rmtree(os.path.join(O, 'jbr'))
shutil.copytree(os.path.join(I, 'jbr'), os.path.join(O, 'jbr'))

# 4. Launcher helpers.
for f in ('WinProcessListHelper.exe', 'WinShellIntegrationBridge.dll', 'elevator.exe', 'fsnotifier.exe',
          'launcher.exe', 'restarter.exe'):
    put(os.path.join(I, 'bin', f), os.path.join(O, 'bin', f))

# 5. JNI libraries loaded into the IDE. Studio's launch configuration points JNA at lib/jna/amd64,
# so the ARM64 library goes there as well as under aarch64.
put(os.path.join(I, 'lib', 'jna', 'aarch64', 'jnidispatch.dll'), os.path.join(O, 'lib', 'jna', 'amd64', 'jnidispatch.dll'))
put(os.path.join(I, 'lib', 'jna', 'aarch64', 'jnidispatch.dll'), os.path.join(O, 'lib', 'jna', 'aarch64', 'jnidispatch.dll'))
put(os.path.join(I, 'lib', 'native', 'win-aarch64', 'sqliteij.dll'), os.path.join(O, 'lib', 'native', 'win-aarch64', 'sqliteij.dll'))
for f in os.listdir(os.path.join(I, 'lib', 'pty4j', 'win', 'aarch64')):
    put(os.path.join(I, 'lib', 'pty4j', 'win', 'aarch64', f), os.path.join(O, 'lib', 'pty4j', 'win', 'aarch64', f))
for f in os.listdir(os.path.join(I, 'lib', 'skiko-awt-runtime-all')):
    if 'arm64' in f:
        put(os.path.join(I, 'lib', 'skiko-awt-runtime-all', f), os.path.join(O, 'lib', 'skiko-awt-runtime-all', f))
lib64 = os.path.join(O, 'plugins', 'design-tools', 'resources', 'layoutlib', 'data', 'win', 'lib64')
for f in ('layoutlib_jni.dll', 'libandroid_runtime.dll'):
    put(os.path.join(a.layoutlib, f), os.path.join(lib64, f))

# 6. Launcher: IntelliJ's ARM64 launcher with Studio's icons and version resource.
RT_ICON, RT_GROUP_ICON, RT_VERSION = 3, 14, 16
k32 = ctypes.WinDLL('kernel32', use_last_error=True)
k32.LoadLibraryExW.restype = wintypes.HMODULE
k32.LoadLibraryExW.argtypes = [wintypes.LPCWSTR, wintypes.HANDLE, wintypes.DWORD]
k32.FindResourceExW.restype = wintypes.HRSRC
k32.FindResourceExW.argtypes = [wintypes.HMODULE, ctypes.c_void_p, ctypes.c_void_p, wintypes.WORD]
k32.LoadResource.restype = wintypes.HGLOBAL
k32.LoadResource.argtypes = [wintypes.HMODULE, wintypes.HRSRC]
k32.LockResource.restype = ctypes.c_void_p
k32.LockResource.argtypes = [wintypes.HGLOBAL]
k32.SizeofResource.restype = wintypes.DWORD
k32.SizeofResource.argtypes = [wintypes.HMODULE, wintypes.HRSRC]
k32.BeginUpdateResourceW.restype = wintypes.HANDLE
k32.BeginUpdateResourceW.argtypes = [wintypes.LPCWSTR, wintypes.BOOL]
k32.UpdateResourceW.restype = wintypes.BOOL
k32.UpdateResourceW.argtypes = [wintypes.HANDLE, ctypes.c_void_p, ctypes.c_void_p, wintypes.WORD, ctypes.c_void_p, wintypes.DWORD]
k32.EndUpdateResourceW.restype = wintypes.BOOL
k32.EndUpdateResourceW.argtypes = [wintypes.HANDLE, wintypes.BOOL]
k32.FreeLibrary.argtypes = [wintypes.HMODULE]
EnumNameProc = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HMODULE, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p)
EnumLangProc = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HMODULE, ctypes.c_void_p, ctypes.c_void_p, wintypes.WORD, ctypes.c_void_p)
k32.EnumResourceNamesW.argtypes = [wintypes.HMODULE, ctypes.c_void_p, EnumNameProc, ctypes.c_void_p]
k32.EnumResourceLanguagesW.argtypes = [wintypes.HMODULE, ctypes.c_void_p, ctypes.c_void_p, EnumLangProc, ctypes.c_void_p]


def resources(path, types):
    """[(type, name id, language, bytes)] for integer-named resources of the given types."""
    h = k32.LoadLibraryExW(path, None, 0x2 | 0x20)  # as data file and image resource
    if not h:
        sys.exit('cannot read resources of %s (%d)' % (path, ctypes.get_last_error()))
    out = []
    for t in types:
        names = []
        k32.EnumResourceNamesW(h, t, EnumNameProc(lambda m, ty, n, p: names.append(n) or True), None)
        for n in names:
            if n is None or n >> 16:
                continue  # string names are not used by these resource types here
            langs = []
            k32.EnumResourceLanguagesW(h, t, n, EnumLangProc(lambda m, ty, nn, lang, p: langs.append(lang) or True), None)
            for lang in langs:
                r = k32.FindResourceExW(h, t, n, lang)
                size = k32.SizeofResource(h, r)
                out.append((t, n, lang, ctypes.string_at(k32.LockResource(k32.LoadResource(h, r)), size)))
    k32.FreeLibrary(h)
    return out


exe = os.path.join(O, 'bin', 'studio64.exe')
shutil.copy2(os.path.join(I, 'bin', 'idea64.exe'), exe)
old = resources(exe, (RT_ICON, RT_GROUP_ICON, RT_VERSION))
new = resources(os.path.join(S, 'bin', 'studio64.exe'), (RT_ICON, RT_GROUP_ICON, RT_VERSION))
hu = k32.BeginUpdateResourceW(exe, False)
for t, n, lang, _ in old:
    k32.UpdateResourceW(hu, t, n, lang, None, 0)
for t, n, lang, data in new:
    buf = ctypes.create_string_buffer(data, len(data))
    if not k32.UpdateResourceW(hu, t, n, lang, buf, len(data)):
        sys.exit('UpdateResource failed (%d)' % ctypes.get_last_error())
if not k32.EndUpdateResourceW(hu, False):
    sys.exit('EndUpdateResource failed (%d)' % ctypes.get_last_error())
if machine(exe) != ARM64:
    sys.exit('launcher is not ARM64 after patching')
print('launcher: %d resources replaced by %d from Studio' % (len(old), len(new)))

# 7. About screen label, so the build is not mistaken for Google's.
jar = os.path.join(O, 'lib', 'resources.jar')
entry = 'idea/AndroidStudioApplicationInfo.xml'
src = zipfile.ZipFile(os.path.join(S, 'lib', 'resources.jar'))
xml = src.read(entry).decode('utf8')
start = xml.index('full="') + len('full="')
end = xml.index('"', start)
if a.label not in xml[start:end]:
    xml = xml[:start] + xml[start:end] + ' (' + a.label + ')' + xml[end:]
tmp = jar + '.tmp'
with zipfile.ZipFile(tmp, 'w') as dst:
    for zi in src.infolist():
        data = xml.encode('utf8') if zi.filename == entry else src.read(zi.filename)
        dst.writestr(zi, data, compress_type=zi.compress_type)
src.close()
os.replace(tmp, jar)
print('About label:', xml[start - 6:xml.index('"', start) + 1])

# 8. Nothing that loads into the IDE process may be x64 unless listed here as unavailable.
UNAVAILABLE = {  # no ARM64 build exists; the feature is missing, as in earlier releases
    'plugins/android/resources/native/image_converter.dll',
    'plugins/android/resources/native/jni_object_tagger.dll',
    'plugins/android/resources/native/video_decoder.dll',
    'plugins/android/resources/simpleperf/windows-x86_64/libsimpleperf_report.dll',
    'plugins/webp/lib/libwebp/win/webp_jni.dll',
}
problems = []
for d, _, fs in os.walk(O):
    rel = os.path.relpath(d, O).replace(os.sep, '/')
    for f in fs:
        p = os.path.join(d, f)
        r = (rel + '/' + f).lstrip('./')
        if not f.lower().endswith('.dll') or machine(p) in (ARM64, None):
            continue
        if r.startswith(('jbr/', 'lib/jna/amd64', 'lib/native/win-x86_64', 'lib/pty4j/win/x86-64', 'lib/skiko-awt-runtime-all/skiko-windows-x64')):
            continue  # unused on ARM64: the ARM64 copy sits beside it
        if r.startswith(('plugins/android-ndk/resources/lldb/', 'plugins/gemini/resources/llamacpp/',
                         'plugins/android/resources/simpleperf/windows-x86_64/libwinpthread')):
            continue  # loaded by x64 helper processes, not by the IDE
        if r in UNAVAILABLE:
            continue
        problems.append(r)
if problems:
    sys.exit('x64 DLLs that would load into the IDE:\n  ' + '\n  '.join(problems))
print('done:', O)
