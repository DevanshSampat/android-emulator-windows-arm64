#!/usr/bin/env python3
"""
vendor_extract.py - read stock vendor binaries straight from an AVD's
vendor.img on the host (no guest, no adb), for any Android version.

    vendor.img = GPT disk -> "vendor" partition -> ext4 (API <= 34, Wear, ...)
                                                 or erofs + lz4 (API 35+)

Pure Python (+ the `lz4` module that prepare_whpx_initrd.py already needs).

Usage:
    python vendor_extract.py <vendor.img> <out_dir> [--all]
        Extracts ELF files under lib64/, lib/, bin/ that reference the goldfish
        pipe / address-space devices (the only files gfx_autopatch can change).
        --all extracts every regular file under those directories.
"""
import os
import struct
import sys

ROOTS = ("lib64", "lib", "bin")
MARKERS = (b"goldfish_address_space", b"qemu_pipe", b"goldfish_pipe", b"opengles")


class Part:
    """Read-only window onto one partition of the disk image."""

    def __init__(self, path):
        self.f = open(path, "rb")
        self.base = self._find_partition()

    def _find_partition(self):
        self.f.seek(0)
        head = self.f.read(1 << 20)
        if head[512:520] != b"EFI PART":
            return 0  # bare filesystem image
        lba, n, esz = struct.unpack_from("<QII", head, 512 + 72)
        self.f.seek(lba * 512)
        table = self.f.read(n * esz)
        first_part = None
        for i in range(n):
            e = table[i * esz:(i + 1) * esz]
            if e[:16] == b"\0" * 16:
                continue
            start = struct.unpack_from("<Q", e, 32)[0] * 512
            name = e[56:128].decode("utf-16le", "ignore").rstrip("\0")
            if name == "vendor":
                return start
            if first_part is None:
                first_part = start
        if first_part is None:
            raise ValueError("GPT has no partitions")
        return first_part

    def read(self, off, n):
        self.f.seek(self.base + off)
        return self.f.read(n)


# --------------------------------------------------------------------------- ext4
class Ext4:
    S_IFMT, S_IFDIR, S_IFREG = 0o170000, 0o040000, 0o100000

    def __init__(self, part):
        self.p = part
        sb = part.read(1024, 1024)
        if struct.unpack_from("<H", sb, 56)[0] != 0xEF53:
            raise ValueError("not ext4")
        self.bs = 1024 << struct.unpack_from("<I", sb, 24)[0]
        self.ipg = struct.unpack_from("<I", sb, 40)[0]
        self.isz = struct.unpack_from("<H", sb, 88)[0]
        first_data = struct.unpack_from("<I", sb, 20)[0]
        incompat = struct.unpack_from("<I", sb, 96)[0]
        self.is64 = bool(incompat & 0x80)
        self.dsz = struct.unpack_from("<H", sb, 254)[0] if self.is64 else 32
        self.gdt = (first_data + 1) * self.bs
        self._gd = {}

    def _inode_table(self, g):
        if g not in self._gd:
            d = self.p.read(self.gdt + g * self.dsz, self.dsz)
            lo = struct.unpack_from("<I", d, 8)[0]
            hi = struct.unpack_from("<I", d, 0x28)[0] if self.is64 and self.dsz >= 64 else 0
            self._gd[g] = (hi << 32) | lo
        return self._gd[g]

    def inode(self, n):
        g, i = divmod(n - 1, self.ipg)
        return self.p.read(self._inode_table(g) * self.bs + i * self.isz, self.isz)

    def _extents(self, node, out):
        magic, entries, _, depth = struct.unpack_from("<HHHH", node, 0)
        if magic != 0xF30A:
            raise ValueError("bad extent header")
        for k in range(entries):
            e = node[12 + 12 * k:24 + 12 * k]
            if depth == 0:
                lblk, ln, hi, lo = struct.unpack_from("<IHHI", e)
                uninit = ln > 32768
                out.append((lblk, ln - 32768 if uninit else ln, (hi << 32) | lo, uninit))
            else:
                lblk, lo, hi = struct.unpack_from("<IIH", e)
                self._extents(self.p.read(((hi << 32) | lo) * self.bs, self.bs), out)

    def _blockmap(self, ino):
        ptrs = list(struct.unpack_from("<15I", ino, 0x28))
        blocks = ptrs[:12]
        per = self.bs // 4

        def walk(b, level):
            if not b:
                return [0] * (per ** level)
            ids = struct.unpack(f"<{per}I", self.p.read(b * self.bs, self.bs))
            if level == 1:
                return list(ids)
            res = []
            for x in ids:
                res += walk(x, level - 1)
            return res
        for lvl, b in ((1, ptrs[12]), (2, ptrs[13]), (3, ptrs[14])):
            if b:
                blocks += walk(b, lvl)
        return [(i, 1, b, b == 0) for i, b in enumerate(blocks)]

    def data(self, n):
        ino = self.inode(n)
        size = struct.unpack_from("<I", ino, 4)[0] | (struct.unpack_from("<I", ino, 0x6C)[0] << 32)
        flags = struct.unpack_from("<I", ino, 0x20)[0]
        if flags & 0x10000000:  # inline data
            return bytes(ino[0x28:0x28 + 60])[:size]
        runs = []
        if flags & 0x80000:
            self._extents(ino[0x28:0x28 + 60], runs)
        else:
            runs = self._blockmap(ino)
        buf = bytearray(size)
        for lblk, ln, pblk, hole in runs:
            off = lblk * self.bs
            if off >= size or hole:
                continue
            chunk = self.p.read(pblk * self.bs, ln * self.bs)
            buf[off:off + len(chunk)] = chunk[:size - off]
        return bytes(buf)

    def mode(self, n):
        return struct.unpack_from("<H", self.inode(n), 0)[0]

    def listdir(self, n):
        d = self.data(n)
        pos = 0
        while pos + 8 <= len(d):
            ino, rec, nlen = struct.unpack_from("<IHB", d, pos)
            if rec < 8:
                break
            if ino:
                name = d[pos + 8:pos + 8 + nlen].decode("utf-8", "replace")
                if name not in (".", ".."):
                    yield name, ino
            pos += rec

    root = 2


# --------------------------------------------------------------------------- erofs
class Erofs:
    """Enough of EROFS for stock Android images: plain/inline/chunk layouts and
    lz4 compression with legacy (full) or compact lcluster indexes."""

    def __init__(self, part):
        self.p = part
        sb = part.read(1024, 128)
        if struct.unpack_from("<I", sb, 0)[0] != 0xE0F5E1E2:
            raise ValueError("not erofs")
        self.blkbits = sb[12]
        self.bs = 1 << self.blkbits
        self.root = struct.unpack_from("<H", sb, 14)[0]
        self.meta = struct.unpack_from("<I", sb, 40)[0] * self.bs
        self.incompat = struct.unpack_from("<I", sb, 80)[0]
        self.zero_padding = bool(self.incompat & 0x1)
        if self.incompat & ~0x7:
            # 0x8 device table, 0x10 ztailpacking, 0x20 fragments/dedupe, ...
            unsupported = self.incompat & ~0x7
            self.warn = f"erofs incompat features 0x{unsupported:x} not fully supported"
        else:
            self.warn = None

    def _ino(self, nid):
        off = self.meta + nid * 32
        h = self.p.read(off, 64)
        fmt = struct.unpack_from("<H", h, 0)[0]
        ext = fmt & 1
        layout = (fmt >> 1) & 7
        xcnt = struct.unpack_from("<H", h, 2)[0]
        mode = struct.unpack_from("<H", h, 4)[0]
        if ext:
            size = struct.unpack_from("<Q", h, 8)[0]
            iu = struct.unpack_from("<I", h, 16)[0]
            isz = 64
        else:
            size = struct.unpack_from("<I", h, 8)[0]
            iu = struct.unpack_from("<I", h, 16)[0]
            isz = 32
        xsz = 12 + (xcnt - 1) * 4 if xcnt else 0
        return dict(off=off, layout=layout, mode=mode, size=size, iu=iu, isz=isz, xsz=xsz)

    def mode(self, nid):
        return self._ino(nid)["mode"]

    def data(self, nid):
        i = self._ino(nid)
        size, layout = i["size"], i["layout"]
        tail_off = i["off"] + i["isz"] + i["xsz"]
        if layout == 0:  # FLAT_PLAIN
            return self.p.read(i["iu"] * self.bs, size)
        if layout == 2:  # FLAT_INLINE
            full = size // self.bs * self.bs
            head = self.p.read(i["iu"] * self.bs, full) if full else b""
            return head + self.p.read(tail_off, size - full)
        if layout == 4:  # CHUNK_BASED
            return self._chunked(i, tail_off)
        if layout in (1, 3):
            return self._compressed(i, tail_off)
        raise ValueError(f"erofs layout {layout} unsupported")

    def _chunked(self, i, tail_off):
        fmt = i["iu"]
        chunkbits = self.blkbits + (fmt & 0x1f)
        indexes = bool(fmt & 0x20)
        n = (i["size"] + (1 << chunkbits) - 1) >> chunkbits
        out = bytearray()
        if indexes:
            tbl = self.p.read((tail_off + 7) & ~7, n * 8)
            addrs = [struct.unpack_from("<I", tbl, 8 * k + 4)[0] for k in range(n)]
        else:
            tbl = self.p.read(tail_off, n * 4)
            addrs = [struct.unpack_from("<I", tbl, 4 * k)[0] for k in range(n)]
        for a in addrs:
            cs = 1 << chunkbits
            out += b"\0" * cs if a == 0xFFFFFFFF else self.p.read(a * self.bs, cs)
        return bytes(out[:i["size"]])

    # -- compressed files ----------------------------------------------------
    def _compressed(self, i, tail_off):
        import lz4.block
        size = i["size"]
        if size == 0:
            return b""
        mh_off = (tail_off + 7) & ~7
        mh = self.p.read(mh_off, 8)
        advise = struct.unpack_from("<H", mh, 4)[0]
        algs = mh[6]
        clusterbits = self.blkbits + (mh[7] & 7)
        if advise & (0x8 | 0x20):  # INLINE_PCLUSTER / FRAGMENT_PCLUSTER
            raise ValueError("erofs ztailpacking/fragments unsupported")
        if algs & 0xff not in (0, 0x00):  # lz4 is algorithm 0 for both heads
            raise ValueError(f"erofs compression algorithm 0x{algs:x} unsupported")
        lcs = 1 << clusterbits
        total = (size + lcs - 1) >> clusterbits
        if i["layout"] == 1:
            idx = self._legacy_index(mh_off, total)
        else:
            idx = self._compact_index(mh_off, total, advise, clusterbits)
        big1 = bool(advise & 0x2)
        big2 = bool(advise & 0x4)
        heads = []
        for lcn, (typ, cofs, pblk, d0, cblks) in enumerate(idx):
            if typ == 2:  # NONHEAD
                continue
            lstart = lcn * lcs + cofs
            nblks = 1
            if (typ == 1 and big1) or (typ == 3 and big2) or (typ == 0 and big1):
                if lcn + 1 < total and idx[lcn + 1][0] == 2 and idx[lcn + 1][4]:
                    nblks = idx[lcn + 1][4]
            heads.append((lstart, typ, pblk, nblks))
        heads.sort()
        out = bytearray(size)
        for k, (lstart, typ, pblk, nblks) in enumerate(heads):
            if lstart >= size:
                continue
            lend = heads[k + 1][0] if k + 1 < len(heads) else size
            lend = min(lend, size)
            need = lend - lstart
            if need <= 0:
                continue
            raw = self.p.read(pblk * self.bs, nblks * self.bs)
            if typ == 0:  # PLAIN (stored)
                if advise & 0x10:  # interlaced
                    sh = lstart & (self.bs - 1)
                    raw = raw[sh:] + raw[:sh]
                piece = raw[:need]
            else:
                src = raw
                if self.zero_padding:
                    m = 0
                    while m < len(src) and src[m] == 0:
                        m += 1
                    src = src[m:]
                try:
                    piece = lz4.block.decompress(src, uncompressed_size=need)
                except Exception:
                    piece = self._lz4_partial(src, need)
            out[lstart:lstart + len(piece)] = piece[:need]
        return bytes(out)

    @staticmethod
    def _lz4_partial(src, need):
        """LZ4 block decode that stops after `need` output bytes."""
        out = bytearray()
        i, n = 0, len(src)
        while i < n and len(out) < need:
            tok = src[i]; i += 1
            lit = tok >> 4
            if lit == 15:
                while True:
                    b = src[i]; i += 1; lit += b
                    if b != 255:
                        break
            out += src[i:i + lit]; i += lit
            if i >= n or len(out) >= need:
                break
            off = src[i] | (src[i + 1] << 8); i += 2
            ml = tok & 15
            if ml == 15:
                while True:
                    b = src[i]; i += 1; ml += b
                    if b != 255:
                        break
            ml += 4
            st = len(out) - off
            if st < 0:
                raise ValueError("lz4: bad offset")
            for k in range(ml):
                out.append(out[st + k])
        if len(out) < need:
            raise ValueError("lz4: short output")
        return bytes(out[:need])

    def _legacy_index(self, mh_off, total):
        base = mh_off + 8
        tbl = self.p.read(base, total * 8)
        idx = []
        for k in range(total):
            adv, cofs, u = struct.unpack_from("<HHI", tbl, 8 * k)
            typ = adv & 3
            if typ == 2:
                d0 = u & 0xffff
                cblks = d0 & 0x7ff if d0 & 0x800 else 0
                idx.append((typ, 0, 0, d0, cblks))
            else:
                idx.append((typ, cofs, u, 0, 0))
        return idx

    def _compact_index(self, mh_off, total, advise, lclusterbits):
        ebase = mh_off + 8
        c4 = (32 - ebase % 32) // 4
        if c4 == 8:
            c4 = 0
        if (advise & 0x1) and c4 < total:
            c2 = (total - c4) // 16 * 16
        else:
            c2 = 0
        region = self.p.read(ebase, c4 * 4 + c2 * 2 + (total - c4 - c2) * 4 + 64)
        big = bool(advise & 0x2)
        lobits = max(lclusterbits, 12)
        idx = []
        for lcn in range(total):
            if lcn < c4:
                ash, pos = 2, lcn * 4
            elif lcn - c4 < c2:
                ash, pos = 1, c4 * 4 + (lcn - c4) * 2
            else:
                ash, pos = 2, c4 * 4 + c2 * 2 + (lcn - c4 - c2) * 4
            idx.append(self._unpack(region, ebase, pos, ash, lobits, lclusterbits, big))
        return idx

    @staticmethod
    def _bits(pack, lobits, bitpos):
        v = int.from_bytes(pack[bitpos // 8:bitpos // 8 + 4].ljust(4, b"\0"), "little") >> (bitpos & 7)
        return v & ((1 << lobits) - 1), (v >> lobits) & 3

    def _unpack(self, region, ebase, pos, ash, lobits, lclusterbits, big):
        if ash == 2:
            vcnt = 2
        elif ash == 1 and lclusterbits <= 12:
            vcnt = 16
        else:
            raise ValueError("erofs compact index unsupported")
        packsz = vcnt << ash
        # packs are aligned in absolute file positions
        abspos = ebase + pos
        packstart = abspos - (abspos % packsz) - ebase
        pack = region[packstart:packstart + packsz]
        encodebits = (packsz - 4) * 8 // vcnt
        i = (abspos % packsz) >> ash
        lo, typ = self._bits(pack, lobits, encodebits * i)
        if typ == 2:  # NONHEAD
            cblks = lo & 0x7ff if lo & 0x800 else 0
            return (typ, 0, 0, lo, cblks)
        clusterofs = lo
        if not big:
            nblk = 1
            j = i
            while j > 0:
                j -= 1
                l2, t2 = self._bits(pack, lobits, encodebits * j)
                if t2 == 2:
                    j -= l2
                if j >= 0:
                    nblk += 1
        else:
            nblk = 0
            j = i
            while j > 0:
                j -= 1
                l2, t2 = self._bits(pack, lobits, encodebits * j)
                if t2 == 2:
                    if l2 & 0x800:
                        j -= 1
                        nblk += l2 & 0x7ff
                        continue
                    if l2 <= 1:
                        raise ValueError("erofs: corrupt compact index")
                    j -= l2 - 2
                    continue
                nblk += 1
        base_blk = struct.unpack_from("<I", pack, packsz - 4)[0]
        return (typ, clusterofs, base_blk + nblk, 0, 0)

    def listdir(self, nid):
        d = self.data(nid)
        for blk in range(0, len(d), self.bs):
            b = d[blk:blk + self.bs]
            if len(b) < 12:
                break
            first_nameoff = struct.unpack_from("<H", b, 8)[0]
            cnt = first_nameoff // 12
            for k in range(cnt):
                cnid, nameoff, _ = struct.unpack_from("<QHB", b, 12 * k)
                end = struct.unpack_from("<H", b, 12 * (k + 1) + 8)[0] if k + 1 < cnt else len(b)
                name = b[nameoff:end].split(b"\0", 1)[0].decode("utf-8", "replace")
                if name not in (".", ".."):
                    yield name, cnid


def open_fs(path):
    part = Part(path)
    magic = part.read(1024, 64)
    if struct.unpack_from("<I", magic, 0)[0] == 0xE0F5E1E2:
        return Erofs(part)
    if struct.unpack_from("<H", magic, 56)[0] == 0xEF53:
        return Ext4(part)
    raise ValueError("vendor partition is neither ext4 nor erofs")


def walk(fs, nid, prefix):
    for name, child in fs.listdir(nid):
        m = fs.mode(child) & 0o170000
        rel = f"{prefix}/{name}" if prefix else name
        if m == 0o040000:
            yield from walk(fs, child, rel)
        elif m == 0o100000:
            yield rel, child


def extract(img, out_dir, everything=False):
    fs = open_fs(img)
    top = dict(fs.listdir(fs.root))
    n = 0
    for r in ROOTS:
        if r not in top or (fs.mode(top[r]) & 0o170000) != 0o040000:
            continue
        for rel, nid in walk(fs, top[r], r):
            data = fs.data(nid)
            always = "/hw/vulkan." in "/" + rel  # Vulkan HAL: overlay policy decides
            if not everything and not always:
                if data[:4] != b"\x7fELF" or not any(m in data for m in MARKERS):
                    continue
            dst = os.path.join(out_dir, *rel.split("/"))
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            with open(dst, "wb") as f:
                f.write(data)
            n += 1
    return n, type(fs).__name__, getattr(fs, "warn", None)


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    n, kind, warn = extract(sys.argv[1], sys.argv[2], "--all" in sys.argv[3:])
    if warn:
        print(f"[vendor_extract] WARNING: {warn}")
    print(f"[vendor_extract] {kind}: extracted {n} file(s)")
    return 0 if n else 1


if __name__ == "__main__":
    sys.exit(main())
