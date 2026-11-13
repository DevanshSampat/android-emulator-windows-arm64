#!/usr/bin/env python3
"""Fetch MSYS2 clangarm64 qemu-system-aarch64.exe and exactly the DLLs it imports (transitively).

Usage (WSL; needs curl, zstd, llvm-readobj): python3 fetch_qemu.py <workdir> <outdir>
Then replace qemu-system-aarch64.exe and the *.dll files in <sdk>\emulator\qemu\windows-aarch64 with <outdir>;
keep its share folder (the bridge passes it to QEMU with -L).
Uses the repo's .files database to map DLL names to packages; verifies package SHA256s.
"""
import hashlib, io, os, re, subprocess, sys, tarfile

MIRROR = 'https://mirror.msys2.org/mingw/clangarm64/'
work, out = sys.argv[1], sys.argv[2]
os.makedirs(work, exist_ok=True); os.makedirs(out, exist_ok=True)

def get(url, path):
    if not os.path.exists(path):
        subprocess.run(['curl', '-fsSL', '--retry', '6', '--retry-all-errors', '-A', 'pkg-fetch',
                        '-o', path + '.part', url], check=True)
        os.rename(path + '.part', path)
    return path

def untar_zst(path):
    data = subprocess.run(['zstd', '-dc', path], check=True, capture_output=True).stdout
    return tarfile.open(fileobj=io.BytesIO(data))

# repo db with file lists
db = untar_zst(get(MIRROR + 'clangarm64.files', os.path.join(work, 'clangarm64.files.tar.zst')))
pkgs = {}      # name -> {filename, sha256}
dll_owner = {} # lower dll name in bin/ -> package name
for m in db.getmembers():
    if not m.isfile(): continue
    d, kind = os.path.split(m.name)
    text = db.extractfile(m).read().decode()
    fields = dict(re.findall(r'%([A-Z0-9]+)%\n(.*?)(?:\n\n|\Z)', text, re.S))
    if kind == 'desc':
        pkgs.setdefault(d, {}).update(name=fields['NAME'], filename=fields['FILENAME'], sha=fields.get('SHA256SUM'), ver=fields['VERSION'])
    elif kind == 'files':
        for f in fields.get('FILES', '').split('\n'):
            if re.fullmatch(r'clangarm64/bin/[^/]+\.dll', f, re.I):
                dll_owner[os.path.basename(f).lower()] = d
byname = {v['name']: k for k, v in pkgs.items()}

extracted = set()
def extract(dkey):
    if dkey in extracted: return
    p = pkgs[dkey]
    path = get(MIRROR + p['filename'], os.path.join(work, p['filename']))
    if p['sha'] and hashlib.sha256(open(path, 'rb').read()).hexdigest() != p['sha']:
        raise SystemExit('sha256 mismatch ' + p['filename'])
    t = untar_zst(path)
    for m in t.getmembers():
        if m.isfile() and m.name.startswith('clangarm64/bin/') and m.name.lower().endswith(('.dll', '.exe')):
            dst = os.path.join(work, 'bin', os.path.basename(m.name))
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            open(dst, 'wb').write(t.extractfile(m).read())
    extracted.add(dkey)
    print('package', p['name'], p['ver'])

def imports(path):
    r = subprocess.run(['llvm-readobj', '--coff-imports', path], capture_output=True, text=True).stdout
    return re.findall(r'Name: (\S+\.dll)', r, re.I)

extract(byname['mingw-w64-clang-aarch64-qemu'])
# SDL2.dll is sdl2-compat, which loads SDL3.dll at run time rather than importing it.
need, seen, system = ['qemu-system-aarch64.exe', 'SDL3.dll'], set(), set()
while need:
    f = need.pop()
    if f.lower() in seen: continue
    seen.add(f.lower())
    if f.lower() != 'qemu-system-aarch64.exe':
        owner = dll_owner.get(f.lower())
        if not owner or f.lower() in ('opengl32.dll',):   # Windows system DLL (mesa also ships one)
            system.add(f); continue
        extract(owner)
    binfiles = {n.lower(): n for n in os.listdir(os.path.join(work, 'bin'))}
    p = os.path.join(work, 'bin', binfiles[f.lower()])
    subprocess.run(['cp', p, out], check=True)
    need += imports(p)
print('copied', len(os.listdir(out)), 'files; system DLLs:', ', '.join(sorted(system)))
