#!/usr/bin/env python3
"""
make_legacy_kernel.py - build kernel_p7_legacy from kernel_p7_permissive.

Android 8 to 11 boot the same 5.15 kernel as Android 12 and 13, but their
dynamic linker does not know the DT_AARCH64_BTI_PLT tag (0x70000001) in the
kernel's vDSO. It then prints, in every process:

    WARNING: linker: "[vdso]" unused DT entry: type 0x70000001 arg 0x0

That lands in adb shell output, and Android Studio's installer fails on it
("Expecting MAGIC_NUMBER but read 'WARNING:'"). This rewrites the tag to
DT_DEBUG, which those linkers accept and ignore for a read-only dynamic
section. Nothing else changes. The output is an uncompressed arm64 Image,
which QEMU boots as it does the gzipped original.

Usage:
    python make_legacy_kernel.py <kernel_p7_permissive> <kernel_p7_legacy>
"""
import gzip
import struct
import sys

DT_AARCH64_BTI_PLT = 0x70000001
DT_DEBUG = 0x15


def find_vdso(image):
    """Offsets of AArch64 ELF images (the vDSO) embedded in a kernel Image."""
    i = 0
    while True:
        i = image.find(b"\x7fELF\x02\x01\x01", i + 1)
        if i < 0:
            return
        if struct.unpack_from("<H", image, i + 18)[0] == 183:
            yield i


def patch(image):
    n = 0
    for base in find_vdso(image):
        phoff = struct.unpack_from("<Q", image, base + 32)[0]
        phnum = struct.unpack_from("<H", image, base + 56)[0]
        if phnum > 16:
            continue
        for k in range(phnum):
            ptype, _, off, _, _, filesz, _, _ = struct.unpack_from("<IIQQQQQQ", image, base + phoff + k * 56)
            if ptype != 2:  # PT_DYNAMIC
                continue
            for e in range(filesz // 16):
                o = base + off + e * 16
                tag = struct.unpack_from("<q", image, o)[0]
                if tag == 0:
                    break
                if tag == DT_AARCH64_BTI_PLT:
                    struct.pack_into("<q", image, o, DT_DEBUG)
                    n += 1
    return n


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    raw = open(sys.argv[1], "rb").read()
    image = bytearray(gzip.decompress(raw) if raw[:2] == b"\x1f\x8b" else raw)
    n = patch(image)
    if n == 0:
        print("no DT_AARCH64_BTI_PLT entry found in the vDSO; nothing written")
        return 1
    open(sys.argv[2], "wb").write(image)
    print(f"patched {n} vDSO dynamic entr{'y' if n == 1 else 'ies'}; wrote {len(image)} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
