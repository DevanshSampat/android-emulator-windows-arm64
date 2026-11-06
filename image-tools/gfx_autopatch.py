#!/usr/bin/env python3
"""
gfx_autopatch.py - Generic, pattern-based graphics transport patcher.

Works on ANY Android image's stock vendor binaries (no fixed offsets, no
per-image patch sets). It locates AOSP's `qemu_pipe_open_ns()` by its
instruction signature and redirects the pipe transport so that, with no
/dev/goldfish_pipe device present (Windows ARM64 WHPX), every qemu pipe
("opengles", "GLProcessPipe", "qemud:*", ...) is opened as an abstract AF_UNIX
connection to "@whpx_pipe" (gfx_forwarder -> host GfxServer), while the
"refcount" pipe connects to "@whpx_refs" (gfx_forwarder tells the host when the
last copy of the fd closes). AF_UNIX, not TCP: netd's
eBPF socket-create hook rejects AF_INET for app uids, so TCP only worked for
system processes (SurfaceFlinger rendered, every app's EGL init failed).

It also emulates the goldfish address-space device (/dev/goldfish_address_space,
used by the ranchu gralloc mapper/allocator, libOpenglSystemCommon and
vulkan.ranchu) with memfds; see find_address_space_sites() and gas_stubs.S.

Signature (identical in API 33..37+ builds of qemu_pipe_open_ns):
    bl   strcmp ; cbz w0, L      (x3, one per special pipe name)
    movz w0, #5000               <- vsock port
    mov  w1, w21
    bl   open_verbose_vsock      <- retargeted to pipe_open (gas_stubs.S)

All replacement code lives in one cave appended to the R-X PT_LOAD; stock
functions are never overwritten, only `bl` targets change.

Usage:
    python gfx_autopatch.py <stock_root> <out_root>
        Walks <stock_root> recursively, patches every ELF that contains the
        signature and writes it to the same relative path under <out_root>.
        Writes <out_root>/manifest.txt listing patched relative paths.
Exit code 0 if at least one file was patched.
"""
import os
import struct
import sys

MOVZ_W0_5000 = 0x52827100
NOP = 0xd503201f


def u32(data, off):
    return struct.unpack_from("<I", data, off)[0]


def is_cbz_w(insn):
    return (insn & 0xff00001f) == 0x34000000 and (insn & 0x80000000) == 0


def is_bl(insn):
    return (insn >> 26) == 0x25


def bl_target(data, off):
    imm = u32(data, off) & 0x03ffffff
    if imm & 0x02000000:
        imm -= 0x04000000
    return off + imm * 4


def adrp_add_string(data, off):
    """Decode 'adrp x1, P ; add x1, x1, #imm' at off and return the C string."""
    try:
        adrp, add = u32(data, off), u32(data, off + 4)
    except struct.error:
        return None
    if (adrp & 0x9f000000) != 0x90000000 or (add & 0xffc00000) != 0x91000000:
        return None
    immlo = (adrp >> 29) & 3
    immhi = (adrp >> 5) & 0x7ffff
    imm = (immhi << 2) | immlo
    if imm & (1 << 20):
        imm -= 1 << 21
    page = (off & ~0xfff) + (imm << 12)
    addr = page + ((add >> 10) & 0xfff)
    if not (0 <= addr < len(data)):
        return None
    end = data.find(b"\x00", addr)
    return bytes(data[addr:end]) if end > addr else None


def x1_string_before(data, bl_off):
    """String loaded into x1 by the `adrp x1 ; add x1, x1, #imm` pair preceding
    the call at bl_off. The compiler may interleave other instructions
    (e.g. `mov x0, x19`), so search a short window instead of fixed slots."""
    for add_off in range(bl_off - 4, max(bl_off - 24, 0), -4):
        add = u32(data, add_off)
        if (add & 0xffc003ff) != 0x91000021:  # add x1, x1, #imm
            continue
        for adrp_off in range(add_off - 4, max(add_off - 16, 0), -4):
            adrp = u32(data, adrp_off)
            if (adrp & 0x9f00001f) != 0x90000001:  # adrp x1, ...
                continue
            immlo = (adrp >> 29) & 3
            immhi = (adrp >> 5) & 0x7ffff
            imm = (immhi << 2) | immlo
            if imm & (1 << 20):
                imm -= 1 << 21
            addr = (adrp_off & ~0xfff) + (imm << 12) + ((add >> 10) & 0xfff)
            if not (0 <= addr < len(data)):
                return None
            end = data.find(b"\x00", addr)
            return bytes(data[addr:end]) if end > addr else None
        return None
    return None


def file_to_vaddr_identity(data):
    """These vendor ELFs map text with file offset == vaddr. Verify via PT_LOAD."""
    if data[:4] != b"\x7fELF" or data[4] != 2:
        return False
    phoff = struct.unpack_from("<Q", data, 0x20)[0]
    phentsize, phnum = struct.unpack_from("<HH", data, 0x36)
    for i in range(phnum):
        o = phoff + i * phentsize
        p_type, p_flags = struct.unpack_from("<II", data, o)
        p_offset, p_vaddr = struct.unpack_from("<QQ", data, o + 8)
        if p_type == 1 and (p_flags & 1) and p_offset != p_vaddr:
            return False
    return True


def is_mov_w1_wn(insn):
    """mov w1, wN (orr w1, wzr, wN) - the flags register differs per build
    (w21 on API 35+, w22 on API 33/34)."""
    return (insn & 0xffe0ffff) == 0x2a0003e1


# First words of the old in-place TCP stub that earlier tooling wrote over
# open_verbose_vsock (it NOPed the movz/cbz's too). Recognised so images that
# were modified in place still get the current shim.
LEGACY_INPLACE_STUB = struct.pack("<4I", 0x35000100, 0xf81f0fff, 0x910003e0, 0xd2800001)


def _legacy_inplace_sites(data):
    sites = []
    p = data.find(LEGACY_INPLACE_STUB)
    targets = set()
    while p >= 0:
        targets.add(p)
        p = data.find(LEGACY_INPLACE_STUB, p + 4)
    if not targets:
        return sites
    for q in range(0x40, len(data) - 12, 4):
        insn = u32(data, q + 8)
        if not is_bl(insn) or bl_target(data, q + 8) not in targets:
            continue
        if u32(data, q) == NOP and is_mov_w1_wn(u32(data, q + 4)):
            sites.append((q, [], bl_target(data, q + 8)))
    return sites


def find_sites(data):
    """Return list of (movz_off, [cbz_offs], vsock_fn_off) matching the full signature."""
    sites = []
    needle = struct.pack("<I", MOVZ_W0_5000)
    start = 0
    while True:
        p = data.find(needle, start)
        if p < 0:
            break
        start = p + 4
        if p % 4 or p < 0x40:
            continue
        if not is_mov_w1_wn(u32(data, p + 4)) or not is_bl(u32(data, p + 8)):
            continue
        # Expect the 3 'bl strcmp ; cbz w0' pairs immediately before.
        cbzs = []
        for back in range(4, 0x48, 4):
            insn = u32(data, p - back)
            if is_cbz_w(insn) and is_bl(u32(data, p - back - 4)):
                cbzs.append(p - back)
        if len(cbzs) < 3:
            continue
        target = bl_target(data, p + 8)
        if target <= 0 or target >= len(data):
            continue
        # The stub relies on w0 == strcmp(name, "refcount") from the compare
        # nearest the movz. Verify that string via its adrp/add pair.
        if x1_string_before(data, cbzs[0] - 4) != b"refcount":
            continue
        sites.append((p, cbzs[:3], target))
    return sites or _legacy_inplace_sites(data)


def patch_bytes(data):
    data = bytearray(data)
    if data[:4] != b"ELF" or data[4] != 2 or struct.unpack_from("<H", data, 0x12)[0] != 183:
        return None, "not an AArch64 ELF64"
    if data.find(GAS_BLOB[:GAS_SPLIT]) >= 0 or data.find(GAS_BLOB[GAS_SPLIT:]) >= 0:
        return None, "already patched"
    notes, nops, retarget = [], [], []
    if file_to_vaddr_identity(data):
        sites = find_sites(data)
        if len(sites) > 1:
            return None, f"ambiguous: {len(sites)} pipe signature sites"
        if sites:
            p, cbzs, _ = sites[0]
            nops += cbzs + [p]          # keep w0 = strcmp(name, "refcount")
            retarget.append((p + 8, "pipe_open"))
            notes.append(f"pipe->@whpx_pipe site=0x{p:x}")
    gas = find_address_space_sites(data)
    if gas:
        retarget += gas
        notes.append("address-space->memfd open=%d ioctl=%d" % (
            sum(e == "gas_open" for _, e in gas), sum(e == "gas_ioctl" for _, e in gas)))
    rewrites = find_read_color_buffer_dma_sites(data)
    if rewrites:
        notes.append(f"readColorBufferDMA->rcReadColorBuffer sites={len(rewrites) // 2}")
    updates = find_update_color_buffer_dma_sites(data)
    if updates:
        notes.append(f"updateColorBufferDMA->rc_update sites={len(updates)}")
        retarget += [(va, "rc_update") for va in updates]
    yuv_reads = find_read_color_buffer_yuv_calls(data)
    if yuv_reads:
        notes.append(f"YUV readback dropped sites={len(yuv_reads)}")
        rewrites = rewrites + [(va, NOP) for va in yuv_reads]
    if not retarget and not rewrites:
        return None, "no patchable code"
    if not retarget:
        loads = [ph for ph in _phdrs(data) if ph["type"] == 1]
        for va, insn in rewrites:
            struct.pack_into("<I", data, _va2off(loads, va), insn)
        return bytes(data), "patched " + "; ".join(notes)
    for va, insn in rewrites:
        if insn == NOP:
            nops.append(va)
    # Copy only the stubs this file calls: [pipe_open | gas_open, gas_ioctl].
    # Both halves are position-independent and self-contained.
    use_pipe = any(e == "pipe_open" for _, e in retarget)
    use_gas = any(e != "pipe_open" for _, e in retarget)
    lo = 0 if use_pipe else GAS_SPLIT
    hi = len(GAS_BLOB) if use_gas else GAS_SPLIT
    blob = GAS_BLOB[lo:hi]
    cave = _place_cave(data, len(blob))
    if cave is None:
        return None, f"no code cave ({len(blob)} bytes) in R-X segment padding"
    cave_va, cave_off = cave
    data[cave_off:cave_off + len(blob)] = blob
    loads = [ph for ph in _phdrs(data) if ph["type"] == 1]
    for va in nops:
        struct.pack_into("<I", data, _va2off(loads, va), NOP)
    for va, entry in retarget:
        rel = (cave_va + GAS_ENTRY[entry] - lo - va) >> 2
        struct.pack_into("<I", data, _va2off(loads, va), 0x94000000 | (rel & 0x03ffffff))
    for va, insn in rewrites:
        if insn != NOP:
            struct.pack_into("<I", data, _va2off(loads, va), insn)
    return bytes(data), "patched " + "; ".join(notes) + f"; cave=0x{cave_va:x}"


# ---------------------------------------------------------------------------
# CPU read-back of GPU buffers without DMA.
#
# Newer ranchu mappers (API 36+) only read color buffers back with
# rcReadColorBufferDMA (the host writes straight into the guest buffer) and
# abort otherwise:  LOG_ALWAYS_FATAL_IF(!rcEnc->featureInfo()->hasReadColorBufferDma)
# There is no host-visible guest memory on WHPX, so DMA is impossible. The
# streaming call rcReadColorBuffer takes the same arguments (minus the trailing
# size, which is ignored) and writes the pixels into the same pointer, which is
# exactly what older mappers do. So: drop the guard and call slot
# OP_rcReadColorBuffer instead of OP_rcReadColorBufferDMA. The slots come from
# the stable renderControl opcode numbering, but the encoder struct starts with
# its `self` pointer, so vtable slot = opcode - 10000 + 1.
# ---------------------------------------------------------------------------
RCB_DMA_ASSERT = b"!rcEnc->featureInfo()->hasReadColorBufferDma\x00"
RC_SLOT_READ_COLOR_BUFFER = 10023 - 10000 + 1
RC_SLOT_READ_COLOR_BUFFER_DMA = 10064 - 10000 + 1
RC_SLOT_UPDATE_COLOR_BUFFER = 10024 - 10000 + 1
RC_SLOT_UPDATE_COLOR_BUFFER_DMA = 10034 - 10000 + 1
MAPPER_MARKERS = (b"bindDmaDirectly", b"hasReadColorBufferDma")
RC_SLOT_READ_COLOR_BUFFER_YUV = 10046 - 10000 + 1


def find_update_color_buffer_dma_sites(data):
    """Same problem in the upload direction: every ranchu mapper (API 33+) sends
    CPU-drawn pixels with rcUpdateColorBufferDMA, which passes only a guest address
    the host cannot read, so software-drawn windows (permission dialogs, ...) stay
    empty. rcUpdateColorBuffer streams the pixels from the same pointer through
    the pipe, but it derives the length from w, h and format, so the rc_update stub
    (gas_stubs.S) carries the real size for YUV frames.
    Other libraries keep other meanings for this slot, so mappers only, and only
    a call that loads the format/type pair (ldp w6, w7) just before it."""
    if not any(m in data for m in MAPPER_MARKERS):
        return []
    out = []
    loads = [p for p in _phdrs(data) if p["type"] == 1]
    for p in loads:
        if not (p["flags"] & 1):
            continue
        base, end = p["vaddr"], p["vaddr"] + p["filesz"]
        for q in range(base, end - 4, 4):
            li = u32(data, _va2off(loads, q))
            if (li & 0xffc00000) != 0xf9400000 or (li >> 10) & 0xfff != RC_SLOT_UPDATE_COLOR_BUFFER_DMA:
                continue
            blr = 0xd63f0000 | ((li & 0x1f) << 5)
            near = range(max(q - 0x30, base), min(q + 0x34, end - 4), 4)
            words = [u32(data, _va2off(loads, r)) for r in near]
            if blr not in words:
                continue
            if not any((w & 0xffc07c1f) == (0x29400000 | (7 << 10) | 6) for w in words):
                continue
            call = next(r for r in near if u32(data, _va2off(loads, r)) == blr)
            out.append(call)
    return out


def find_read_color_buffer_yuv_calls(data):
    """Locking a YUV buffer for reading copies the whole frame back from the host. These
    buffers are written by the CPU (video decoders) and the guest already holds the
    bytes, so on a decoder, which locks every frame read+write, that is one frame of
    useless traffic per frame: half of what the socket carries, and the frame rate limit.
    Returns the blr of each such call; the call returns nothing, so it can be dropped."""
    if not any(m in data for m in MAPPER_MARKERS):
        return []
    out = []
    loads = [p for p in _phdrs(data) if p["type"] == 1]
    for p in loads:
        if not (p["flags"] & 1):
            continue
        base, end = p["vaddr"], p["vaddr"] + p["filesz"]
        for q in range(base, end - 4, 4):
            li = u32(data, _va2off(loads, q))
            if (li & 0xffc00000) != 0xf9400000 or (li >> 10) & 0xfff != RC_SLOT_READ_COLOR_BUFFER_YUV:
                continue
            blr = 0xd63f0000 | ((li & 0x1f) << 5)
            near = range(q + 4, min(q + 0x34, end - 4), 4)
            call = next((r for r in near if u32(data, _va2off(loads, r)) == blr), None)
            if call is not None:
                out.append(call)
    return out if len(out) <= 2 else []


def find_read_color_buffer_dma_sites(data):
    """[(vaddr, new_instruction_word)] or [] if the pattern isn't present."""
    s = data.find(RCB_DMA_ASSERT)
    if s < 0:
        return []
    loads = [p for p in _phdrs(data) if p["type"] == 1]
    str_va = _off2va(loads, s)
    if str_va is None:
        return []
    out = []
    for p in loads:
        if not (p["flags"] & 1):
            continue
        base, end = p["vaddr"], p["vaddr"] + p["filesz"]
        for va in range(base, end - 8, 4):
            off = _va2off(loads, va)
            adrp, add = u32(data, off), u32(data, off + 4)
            if (adrp & 0x9f00001f) != 0x90000000 or (add & 0xffc003ff) != 0x91000000:
                continue  # want: adrp x0, P ; add x0, x0, #imm
            imm = (((adrp >> 5) & 0x7ffff) << 2) | ((adrp >> 29) & 3)
            if imm & (1 << 20):
                imm -= 1 << 21
            if (va & ~0xfff) + (imm << 12) + ((add >> 10) & 0xfff) != str_va:
                continue
            assert_va = va
            # The guard: tbz wN, #0, assert_va within the preceding 0x100 bytes
            for g in range(assert_va - 4, max(assert_va - 0x100, base), -4):
                insn = u32(data, _va2off(loads, g))
                if (insn & 0xfff80000) != 0x36000000:  # tbz w*, #0, label
                    continue
                imm14 = (insn >> 5) & 0x3fff
                if imm14 & 0x2000:
                    imm14 -= 0x4000
                if g + imm14 * 4 != assert_va:
                    continue
                # The DMA call that follows: ldr xR, [xM, #slot*8] ; ... ; blr xR
                for q in range(g + 4, min(g + 0x80, end - 4), 4):
                    li = u32(data, _va2off(loads, q))
                    if (li & 0xffc00000) != 0xf9400000:  # ldr x, [x, #imm] (64-bit)
                        continue
                    if (li >> 10) & 0xfff != RC_SLOT_READ_COLOR_BUFFER_DMA:
                        continue
                    rt = li & 0x1f
                    blr = 0xd63f0000 | (rt << 5)
                    if not any(u32(data, _va2off(loads, r)) == blr
                               for r in range(q + 4, min(q + 0x40, end), 4)):
                        continue
                    new_ldr = (li & ~(0xfff << 10)) | (RC_SLOT_READ_COLOR_BUFFER << 10)
                    out += [(g, NOP), (q, new_ldr)]
                    break
                break
    return out


# ---------------------------------------------------------------------------
# Goldfish address-space device emulation (/dev/goldfish_address_space).
#
# Every ranchu gralloc mapper/allocator, libOpenglSystemCommon and vulkan.ranchu
# statically links GoldfishAddressSpaceBlockProvider & co, which call
# __open_2()/ioctl() directly (they do NOT go through the goldfish_address_space_*
# C API). Without the device, the provider's fd is -1 and the mapper aborts:
#   GoldfishMapper: '!host_memory_allocator.is_opened()'
#
# We retarget exactly those call sites, identified by version-stable facts only:
#   * bl __open_2/open   whose x0 is the address of "/dev/goldfish_address_space"
#   * bl ioctl           whose w1 is a GOLDFISH_ADDRESS_SPACE_IOCTL_* request
# to gas_open/gas_ioctl (gas_stubs.S) placed in the tail padding of the R-X
# PT_LOAD, which is grown to cover it. No symbols or offsets are assumed.
# The stubs use the real device when it exists, so patched files stay correct
# on a real emulator. The goldfish_address_space_* C API (ASG transport) uses a
# tail call `b __open_2` and is deliberately left untouched.
#
# Regenerate GAS_BLOB and GAS_ENTRY (from `aarch64-linux-gnu-nm gas.o`) after
# editing gas_stubs.S:
#   aarch64-linux-gnu-as gas_stubs.S -o gas.o
#   aarch64-linux-gnu-objcopy -O binary -j .text gas.o gas.bin
#   python gfx_autopatch.py --blob gas.bin
# ---------------------------------------------------------------------------
GAS_BLOB = bytes.fromhex(
    "ed03002a200080d2210080d20101a0f2020080d2c81880d2010000d44003f8b7e90300aa2a0080520ae0ae720b0d8e52"
    "0befab720c2e8d520caeac726d0000354cae8c52cc6cae72ff4300d1ea2f0029ec0b00b9e00309aae1030091820180d2"
    "681980d2010000d4ff430091c001f8b6ea0300aae00309aa280780d2010000d4e0030aaaed000035ff0f1ff8e0030091"
    "010080d2e82280d2010000d4ff430091c0035fd6e00309aac0035fd6e90300aaea03012a600c8092e10309aae2030a2a"
    "030080d2080780d2010000d40002f8b6400d0010010080d2e82280d2010000d48001f8b7e90300aa0120c0d2c80580d2"
    "010000d4e00309aa2100a0d2020080d2c80780d2010000d4e00309aac0035fd600008012c0035fd6e90300aaea0301aa"
    "eb0302aaa80380d2010000d41f6400b1800000544000f8b600008012c0035fd6e00309aa418180d2280380d2010000d4"
    "0002f8b74c3d08539f1d0171a10100544c1d00129f29007143010054600100549f2d0071400200549f31007120020054"
    "9f3900716800005400008052c0035fd600008012c0035fd66d0140f9ef031eaa16000094fe030faa4effffb76d0100f9"
    "6e0500f96e0900f9f4ffff17f3ffff176c0940f99f0500f1410100546c0140f90c0100b56d0540f9ef031eaa07000094"
    "fe030faa6efdffb76e0100f96d0500f97f0900f9e5ffff17ad414091ad0500d1adbd7092e00309aae1030daa220080d2"
    "c80780d2010000d4e000f8b70c20c0d21f000ceb680000540e000dcbc0035fd660018092ee0300aac0035fd6e90b40b9"
    "8a7ca59b4af57ed30b218352df000b6b010100543f010aebc2000054e203042ae303052ae403092a2500805226218352"
    "106440f900021fd6676f6c64666973685f616464726573735f73706163650000"
)
GAS_ENTRY = {"pipe_open": 0x0, "gas_open": 0xac, "gas_ioctl": 0x118, "rc_update": 0x23c}  # from `nm gas.o`
GAS_SPLIT = GAS_ENTRY["gas_open"]  # [0, split) pipe stub, [split, end) address-space stubs
GAS_PATH = b"/dev/goldfish_address_space\x00"
GAS_IOCTLS = {0xc018470a, 0xc008470b, 0xc028470c, 0xc010470d, 0xc008470e}


def _phdrs(data):
    phoff = struct.unpack_from("<Q", data, 0x20)[0]
    phentsize, phnum = struct.unpack_from("<HH", data, 0x36)
    out = []
    for i in range(phnum):
        o = phoff + i * phentsize
        p_type, p_flags = struct.unpack_from("<II", data, o)
        p_offset, p_vaddr, _, p_filesz, p_memsz = struct.unpack_from("<QQQQQ", data, o + 8)
        out.append(dict(hdr=o, type=p_type, flags=p_flags, offset=p_offset,
                        vaddr=p_vaddr, filesz=p_filesz, memsz=p_memsz))
    return out


def _va2off(loads, va):
    for p in loads:
        if p["vaddr"] <= va < p["vaddr"] + p["filesz"]:
            return va - p["vaddr"] + p["offset"]
    return None


def _off2va(loads, off):
    for p in loads:
        if p["offset"] <= off < p["offset"] + p["filesz"]:
            return off - p["offset"] + p["vaddr"]
    return None


def _sections(data):
    shoff = struct.unpack_from("<Q", data, 0x28)[0]
    shentsize, shnum, shstrndx = struct.unpack_from("<HHH", data, 0x3a)
    if not shoff or shstrndx >= shnum:
        return []
    secs = []
    for i in range(shnum):
        o = shoff + i * shentsize
        name, typ = struct.unpack_from("<II", data, o)
        _, addr, off, size = struct.unpack_from("<QQQQ", data, o + 8)
        link = struct.unpack_from("<I", data, o + 0x28)[0]
        secs.append(dict(name=name, type=typ, addr=addr, off=off, size=size, link=link))
    stro = secs[shstrndx]["off"]
    for s in secs:
        end = data.find(b"\x00", stro + s["name"])
        s["n"] = data[stro + s["name"]:end].decode(errors="replace")
    return secs


def _plt_targets(data, secs, md, loads):
    """Map PLT stub address -> imported symbol name by decoding each stub's GOT
    load (adrp x16 ; ldr x17, [x16, #off]) and matching .rela.plt slots."""
    dynsym = next((s for s in secs if s["type"] == 11), None)
    if dynsym is None:
        return {}
    strtab = secs[dynsym["link"]]["off"]
    got2name = {}
    for s in secs:
        if s["type"] != 4 or not s["n"].startswith(".rela"):  # SHT_RELA
            continue
        for j in range(s["size"] // 24):
            r_off, r_info, _ = struct.unpack_from("<QQq", data, s["off"] + j * 24)
            if (r_info & 0xffffffff) not in (1026, 1025):  # JUMP_SLOT, GLOB_DAT
                continue
            e = dynsym["off"] + (r_info >> 32) * 24
            nm = struct.unpack_from("<I", data, e)[0]
            end = data.find(b"\x00", strtab + nm)
            got2name[r_off] = data[strtab + nm:end].decode(errors="replace")
    out = {}
    for s in secs:
        if s["n"] not in (".plt", ".plt.sec", ".iplt"):
            continue
        insns = list(md.disasm(bytes(data[s["off"]:s["off"] + s["size"]]), s["addr"]))
        for k in range(len(insns) - 1):
            a, b = insns[k], insns[k + 1]
            if a.mnemonic != "adrp" or not a.op_str.startswith("x16,"):
                continue
            if b.mnemonic != "ldr" or not b.op_str.startswith("x17, [x16"):
                continue
            page = _imm(a.op_str.split(",")[-1])
            mem = b.op_str.split("[", 1)[1].rstrip("]!").split(",")
            got = page + (_imm(mem[1]) if len(mem) > 1 else 0)
            name = got2name.get(got)
            if name:
                start = a.address
                if k and insns[k - 1].mnemonic in ("bti", "hint"):
                    start = insns[k - 1].address
                out[start] = name
                out[a.address] = name
    return out




def _reg(name):
    """'w1'/'x1' -> (1, bits); anything else -> None."""
    if len(name) >= 2 and name[0] in "wx" and name[1:].isdigit():
        return int(name[1:]), (32 if name[0] == "w" else 64)
    return None


def _imm(tok):
    tok = tok.strip().lstrip("#")
    return int(tok, 0)


def find_gas_call_sites(data):
    """Return (open_sites, ioctl_sites) as lists of vaddrs of `bl` instructions."""
    try:
        import capstone
    except ImportError:
        raise RuntimeError("python capstone module missing (pip install capstone)")
    arch = getattr(capstone, "CS_ARCH_AARCH64", None)
    if arch is None:
        arch = capstone.CS_ARCH_ARM64
    md = capstone.Cs(arch, capstone.CS_MODE_ARM)
    md.detail = True
    md.skipdata = True
    secs = _sections(data)
    loads = [p for p in _phdrs(data) if p["type"] == 1]
    path_vas = set()
    p = data.find(GAS_PATH)
    while p >= 0:
        va = _off2va(loads, p)
        if va is not None:
            path_vas.add(va)
        p = data.find(GAS_PATH, p + 1)
    if not path_vas:
        return [], []
    plt = _plt_targets(data, secs, md, loads)
    open_plt = {a for a, n in plt.items() if n in ("__open_2", "open", "open64")}
    ioctl_plt = {a for a, n in plt.items() if n == "ioctl"}
    if not open_plt and not ioctl_plt:
        return [], []
    texts = [s for s in secs if s["n"] == ".text"] or [
        dict(addr=p["vaddr"], off=p["offset"], size=p["filesz"]) for p in loads if p["flags"] & 1]
    open_sites, ioctl_sites = [], []
    for t in texts:
        insns = [i for i in md.disasm(bytes(data[t["off"]:t["off"] + t["size"]]), t["addr"])
                 if i.id != 0]
        targets = set()
        for i in insns:
            if i.mnemonic in ("b", "cbz", "cbnz", "tbz", "tbnz") or i.mnemonic.startswith("b."):
                tok = i.op_str.split(",")[-1].strip()
                if tok.startswith("#"):
                    try:
                        targets.add(int(tok[1:], 0))
                    except ValueError:
                        pass
        regs = {}
        for i in insns:
            m = i.mnemonic
            if i.address in targets:
                regs = {}
            if m == "bl":
                tgt = _imm(i.op_str)
                if tgt in open_plt and regs.get(0) in path_vas:
                    open_sites.append(i.address)
                elif tgt in ioctl_plt and (regs.get(1, -1) & 0xffffffff) in GAS_IOCTLS:
                    ioctl_sites.append(i.address)
                regs = {k: v for k, v in regs.items() if k > 18 and k != 30}
                continue
            if m in ("b", "br", "ret", "blr", "braa", "brab", "retaa", "retab", "eret") \
                    or m.startswith("blra"):
                regs = {}
                continue
            ops = [o.strip() for o in i.op_str.split(",")]
            d = _reg(ops[0]) if ops else None
            handled = False
            try:
                if d and m in ("mov", "movz", "movn") and len(ops) == 2 and ops[1].startswith("#"):
                    v = _imm(ops[1])
                    regs[d[0]] = v & ((1 << d[1]) - 1)
                    handled = True
                elif d and m == "movk" and len(ops) >= 2:
                    sh = _imm(ops[2].split()[-1]) if len(ops) > 2 else 0
                    if d[0] in regs:
                        v = regs[d[0]] & ~(0xffff << sh) | (_imm(ops[1]) << sh)
                        regs[d[0]] = v & ((1 << d[1]) - 1)
                    handled = True
                elif d and m in ("adr", "adrp") and len(ops) == 2:
                    regs[d[0]] = _imm(ops[1])
                    handled = True
                elif d and m == "add" and len(ops) >= 3 and ops[2].startswith("#"):
                    n = _reg(ops[1])
                    sh = _imm(ops[3].split()[-1]) if len(ops) > 3 else 0
                    if n and n[0] in regs:
                        regs[d[0]] = (regs[n[0]] + (_imm(ops[2]) << sh)) & ((1 << d[1]) - 1)
                    else:
                        regs.pop(d[0], None)
                    handled = True
            except (ValueError, IndexError):
                handled = False
            if handled:
                continue
            try:
                _, written = i.regs_access()
            except Exception:
                regs = {}
                continue
            for r in written:
                rr = _reg(i.reg_name(r) or "")
                if rr:
                    regs.pop(rr[0], None)
    return open_sites, ioctl_sites


def _place_cave(data, size):
    """Grow the R-X PT_LOAD over its tail padding; return cave vaddr or None."""
    ph = _phdrs(data)
    loads = [p for p in ph if p["type"] == 1]
    for p in loads:
        if not (p["flags"] & 1) or p["filesz"] != p["memsz"]:
            continue
        end_va = p["vaddr"] + p["filesz"]
        cave_va = (end_va + 15) & ~15
        cave_off = cave_va - p["vaddr"] + p["offset"]
        later_va = [q["vaddr"] for q in loads if q["vaddr"] > p["vaddr"]]
        later_off = [q["offset"] for q in loads if q["offset"] > p["offset"]]
        lim_va = min(later_va) if later_va else None
        lim_off = min(later_off) if later_off else len(data)
        if lim_va is None or cave_va + size > lim_va or cave_off + size > lim_off \
                or cave_off + size > len(data):
            continue
        if any(data[p["offset"] + p["filesz"]:cave_off + size]):
            continue  # padding is not free
        new_sz = cave_va + size - p["vaddr"]
        struct.pack_into("<QQ", data, p["hdr"] + 0x20, new_sz, new_sz)
        return cave_va, cave_off
    return _add_cave_segment(data, size)


def _add_cave_segment(data, size):
    """Padding is luck: a small binary can have too little, and skipping it can be
    fatal (an unpatched allocator aborts and takes the whole display stack down).
    So append the stub as a new R-X PT_LOAD at the end of the file. The program
    header table cannot grow in place, so the new entry replaces PT_NOTE, which is
    only a build id and unused at runtime. Bionic expects PT_LOADs in ascending
    vaddr order, so the entry goes straight after the last one."""
    ph = _phdrs(data)
    note = next((p for p in ph if p["type"] == 4), None)
    loads = [p for p in ph if p["type"] == 1]
    if note is None or not loads:
        return None
    align = max(max(struct.unpack_from("<Q", data, p["hdr"] + 0x30)[0] for p in loads), 0x4000)
    va = (max(p["vaddr"] + p["memsz"] for p in loads) + align - 1) & ~(align - 1)
    off = (len(data) + align - 1) & ~(align - 1)
    data.extend(b"\0" * (off + size - len(data)))
    phoff = struct.unpack_from("<Q", data, 0x20)[0]
    entsize = struct.unpack_from("<H", data, 0x36)[0]
    entries = [bytes(data[p["hdr"]:p["hdr"] + entsize]) for p in ph]
    new = struct.pack("<IIQQQQQQ", 1, 5, off, va, va, size, size, align).ljust(entsize, b"\0")
    last_load = max(i for i, p in enumerate(ph) if p["type"] == 1)
    note_idx = ph.index(note)
    entries.insert(last_load + 1, new)
    entries.pop(note_idx if note_idx <= last_load else note_idx + 1)
    data[phoff:phoff + entsize * len(entries)] = b"".join(entries)
    return va, off


def _func_ranges(data):
    """[(start, end, name)] for defined FUNC symbols in .dynsym (may be empty)."""
    out = []
    try:
        secs = _sections(data)
        for s in secs:
            if s["type"] != 11:
                continue
            st = secs[s["link"]]["off"]
            for j in range(s["size"] // 24):
                nm, info, _, shndx, val, sz = struct.unpack_from("<IBBHQQ", data, s["off"] + j * 24)
                if shndx and (info & 0xf) == 2 and val and sz:
                    end = data.find(b"\x00", st + nm)
                    out.append((val, val + sz, data[st + nm:end].decode(errors="replace")))
    except (struct.error, IndexError):
        return []
    return out


def _provider_open_sites(data, open_sites, ioctl_sites):
    """Keep only GoldfishAddressSpaceBlockProvider's open(). The ASG transport
    (createGoldfishAddressSpaceStream) inlines its own open() and must keep
    failing: a memfd-backed ASG ring has no consumer and would hang.
    With symbols: the enclosing function must be the provider. Without symbols:
    the provider issues its subdevice PING ioctl directly within a few
    instructions of the open(); the ASG stream goes through the C API instead."""
    funcs = _func_ranges(data)
    keep = []
    for va in open_sites:
        name = next((n for a, b, n in funcs if a <= va < b), None)
        if name is not None:
            if "GoldfishAddressSpaceBlockProvider" in name:
                keep.append(va)
        elif any(0 < s - va <= 0x60 for s in ioctl_sites):
            keep.append(va)
    return keep


def find_address_space_sites(data):
    """[(bl_vaddr, stub_entry)] for the block-provider open() and the
    address-space ioctl()s; [] when the file doesn't use the device."""
    if data.find(GAS_PATH) < 0:
        return []
    try:
        open_sites, ioctl_sites = find_gas_call_sites(data)
    except RuntimeError as e:
        print(f"[gfx_autopatch] WARNING: {e}")
        return []
    open_sites = _provider_open_sites(data, open_sites, ioctl_sites)
    if not open_sites:
        return []  # no block-provider open() -> nothing to emulate
    return [(va, "gas_open") for va in open_sites] + [(va, "gas_ioctl") for va in ioctl_sites]


def print_blob(path):
    blob = open(path, "rb").read()
    h = blob.hex()
    print("GAS_BLOB = bytes.fromhex(")
    for i in range(0, len(h), 96):
        print(f'    "{h[i:i + 96]}"')
    print(")")


def main():
    if len(sys.argv) == 3 and sys.argv[1] == "--blob":
        print_blob(sys.argv[2])
        return 0
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    stock_root, out_root = sys.argv[1], sys.argv[2]
    patched = []
    for dirpath, _, files in os.walk(stock_root):
        for fn in files:
            src = os.path.join(dirpath, fn)
            rel = os.path.relpath(src, stock_root).replace("\\", "/")
            with open(src, "rb") as f:
                data = f.read()
            if data[:4] != b"\x7fELF":
                continue
            out, msg = patch_bytes(data)
            print(f"[gfx_autopatch] {rel}: {msg}")
            if out is None:
                continue
            dst = os.path.join(out_root, rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            with open(dst, "wb") as f:
                f.write(out)
            patched.append(rel)
    os.makedirs(out_root, exist_ok=True)
    with open(os.path.join(out_root, "manifest.txt"), "w", newline="\n") as f:
        for rel in patched:
            f.write(rel + "\n")
    print(f"[gfx_autopatch] {len(patched)} file(s) patched")
    return 0 if patched else 1


if __name__ == "__main__":
    sys.exit(main())
