import os, sys, struct, lz4.block

def parse_cpio(cpio):
    pos = 0
    entries = []
    while pos + 110 <= len(cpio):
        if cpio[pos:pos+6] != b'070701':
            pos += 1
            continue
        hdr = cpio[pos:pos+110]
        ns = int(hdr[94:102], 16)
        fs = int(hdr[54:62], 16)
        name = cpio[pos+110:pos+110+ns-1].decode('utf-8', errors='ignore')
        pos += (110 + ns + 3) & ~3
        if name == 'TRAILER!!!':
            break
        data = cpio[pos:pos+fs]
        pos += (fs + 3) & ~3
        entries.append((name, hdr, data))
    return entries

def make_cpio_entry(ino, mode, name, data):
    name_b = name.encode('utf-8') + b'\x00'
    hdr = (
        f'070701'
        f'{ino:08x}'
        f'{mode:08x}'
        f'{0:08x}{0:08x}'
        f'{1:08x}'
        f'{0:08x}'
        f'{len(data):08x}'
        f'{0:08x}{0:08x}{0:08x}{0:08x}'
        f'{len(name_b):08x}'
        f'{0:08x}'
    ).encode('ascii')
    out = bytearray(hdr + name_b)
    while len(out) % 4 != 0: out.append(0)
    out.extend(data)
    while len(out) % 4 != 0: out.append(0)
    return bytes(out)

GFX_OVERLAY_VERSION = 1  # bump to invalidate every cached overlay


def lz4_legacy_segment(raw):
    """Kernel-compatible legacy LZ4 stream (magic 0x184C2102), split into
    independent blocks of at most 8 MiB uncompressed (the format's limit)."""
    out = bytearray(b'\x02\x21\x4c\x18')
    step = 8 << 20
    for i in range(0, len(raw), step):
        c = lz4.block.compress(raw[i:i + step], store_size=False)
        out += struct.pack('<I', len(c)) + c
    out += b'\x00\x00\x00\x00'
    while len(out) % 4:
        out.append(0)
    return bytes(out)


def _hash_file(h, path):
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 22), b''):
            h.update(chunk)


def build_gfx_overlay(sys_dir, bridge_dir):
    """Return [(vendor_rel_path, bytes)] replacing stock vendor binaries.

    Universal: the stock files come from this image's own vendor.img and are
    patched by gfx_autopatch.py's signature-based patcher, so any Android
    version / form factor works without per-image patch sets. Cached under
    <bridge>/cache/gfx/<key>, keyed on vendor.img + patcher sources, so only
    the first launch of a new image does any work."""
    import hashlib, shutil, subprocess
    vendor_img = os.path.join(sys_dir, 'vendor.img')
    if not os.path.isfile(vendor_img):
        print('[prepare_whpx_initrd] no vendor.img; graphics overlay skipped')
        return []
    h = hashlib.sha256(b'gfx-overlay-v%d' % GFX_OVERLAY_VERSION)
    for src in ('vendor_extract.py', 'gfx_autopatch.py'):
        _hash_file(h, os.path.join(bridge_dir, src))
    st = os.stat(vendor_img)
    h.update(b'%d' % st.st_size)
    _hash_file(h, vendor_img)
    key = h.hexdigest()[:20]
    cache = os.path.join(bridge_dir, 'cache', 'gfx', key)
    done = os.path.join(cache, 'overlay.txt')
    if not os.path.isfile(done):
        work = cache + '.tmp'
        shutil.rmtree(work, ignore_errors=True)
        stock, out = os.path.join(work, 'stock'), os.path.join(work, 'out')
        os.makedirs(stock)
        py = sys.executable or 'python'
        for cmd in ([py, os.path.join(bridge_dir, 'vendor_extract.py'), vendor_img, stock],
                    [py, os.path.join(bridge_dir, 'gfx_autopatch.py'), stock, out]):
            r = subprocess.run(cmd, capture_output=True, text=True)
            sys.stdout.write(r.stdout)
            if r.returncode not in (0, 1):
                print(f'[prepare_whpx_initrd] {os.path.basename(cmd[1])} failed: {r.stderr[-800:]}')
                return []
        entries = []
        man = os.path.join(out, 'manifest.txt')
        if os.path.isfile(man):
            entries = [l.strip() for l in open(man) if l.strip()]
        # Vulkan HAL policy: masked so the stack uses the GLES path (same as
        # the legacy start_android_ui.sh mask, but before anything loads it).
        for root, _, files in os.walk(stock):
            for fn in files:
                rel = os.path.relpath(os.path.join(root, fn), stock).replace('\\', '/')
                if '/hw/vulkan.' in '/' + rel:
                    p = os.path.join(out, *rel.split('/'))
                    os.makedirs(os.path.dirname(p), exist_ok=True)
                    open(p, 'wb').close()
                    if rel not in entries:
                        entries.append(rel)
        shutil.rmtree(cache, ignore_errors=True)
        os.makedirs(cache)
        for rel in entries:
            dst = os.path.join(cache, 'files', *rel.split('/'))
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copyfile(os.path.join(out, *rel.split('/')), dst)
        with open(done, 'w', newline='\n') as f:
            f.write('\n'.join(entries) + '\n')
        shutil.rmtree(work, ignore_errors=True)
        print(f'[prepare_whpx_initrd] graphics overlay built and cached: {key} ({len(entries)} files)')
    else:
        print(f'[prepare_whpx_initrd] graphics overlay cache hit: {key}')
    result = []
    for rel in (l.strip() for l in open(done)):
        if rel:
            with open(os.path.join(cache, 'files', *rel.split('/')), 'rb') as f:
                result.append((rel, f.read()))
    return result


def gfx_overlay_segment(sys_dir, bridge_dir):
    """Third initramfs segment: /whpx_gfx/{manifest,<idx>,gfx_forwarder},
    consumed by whpx_init (see apply_gfx_overlay)."""
    try:
        files = build_gfx_overlay(sys_dir, bridge_dir)
    except Exception as e:
        print(f'[prepare_whpx_initrd] graphics overlay failed: {e}')
        return b''
    if not files:
        return b''
    cp = bytearray()
    ino = 3000
    cp += make_cpio_entry(ino, 0o040755, 'whpx_gfx', b''); ino += 1
    manifest = ''
    for i, (rel, data) in enumerate(files):
        cp += make_cpio_entry(ino, 0o100644, f'whpx_gfx/{i}', data); ino += 1
        manifest += f'{i} {rel}\n'
    cp += make_cpio_entry(ino, 0o100644, 'whpx_gfx/manifest', manifest.encode()); ino += 1
    fwd = os.path.join(bridge_dir, 'gfx_forwarder')
    if os.path.isfile(fwd):
        with open(fwd, 'rb') as f:
            cp += make_cpio_entry(ino, 0o100755, 'whpx_gfx/gfx_forwarder', f.read()); ino += 1
    cp += make_cpio_entry(ino, 0, 'TRAILER!!!', b'')
    while len(cp) % 512:
        cp.append(0)
    print(f'[prepare_whpx_initrd] graphics overlay: {len(files)} vendor file(s) in initrd')
    return lz4_legacy_segment(bytes(cp))


def prepare_ramdisk_base(sys_dir, avd_dir):
    ramdisk_src = os.path.join(sys_dir, 'ramdisk.img')
    out_base = os.path.join(avd_dir, 'initrd_whpx_base')
    if not os.path.exists(ramdisk_src):
        return False
    
    with open(ramdisk_src, 'rb') as f:
        orig = f.read()

    seg2_idx = orig.find(b'\x02\x21\x4c\x18', 4)
    if seg2_idx == -1:
        pos = 4
        while pos < len(orig):
            if pos + 4 > len(orig): break
            bsize = struct.unpack('<I', orig[pos:pos+4])[0]
            pos += 4
            if bsize == 0 or pos + bsize > len(orig):
                seg2_idx = pos - 4
                break
            pos += bsize

    if seg2_idx != -1 and seg2_idx < len(orig):
        seg1_bytes = orig[:seg2_idx]
        seg2_bytes = orig[seg2_idx:]
        
        user_home = os.path.expanduser('~')
        adbkey_path = os.path.join(user_home, '.android', 'adbkey.pub')
        adb_keys_data = b''
        if os.path.exists(adbkey_path):
            with open(adbkey_path, 'rb') as f:
                adb_keys_data = f.read().strip() + b'\n'

        # Determine API level
        import re
        api_level = 34
        props_file = os.path.join(sys_dir, 'source.properties')
        if os.path.isfile(props_file):
            with open(props_file, 'r', encoding='utf-8', errors='ignore') as pf:
                for pline in pf:
                    if pline.startswith('AndroidVersion.ApiLevel'):
                        try: api_level = int(pline.split('=')[1].strip())
                        except: pass
        else:
            m = re.search(r'android-(\d+)', sys_dir)
            if m: api_level = int(m.group(1))

        # Select matching WHPX kernel modules
        whpx_modules = {}
        bridge_dir = os.path.dirname(os.path.abspath(__file__))
        # The bridge sets WHPX_MODULES_6_1 when it boots kernel_whpx_stimer instead of the stock kernel.
        modules_6_1 = os.environ.get('WHPX_MODULES_6_1') or 'modules_whpx_6_1'
        if api_level >= 34 and os.path.isdir(os.path.join(bridge_dir, modules_6_1)):
            modules_dir = os.path.join(bridge_dir, modules_6_1)
            # system_heap: as on 5.15 below. The image's own copy is built for a newer kernel and
            # is rejected, so without this one every Codec2 component fails with NO_MEMORY.
            # From the stock API 34 vendor.img, same build as this kernel.
            modules_load_data = b"system_heap.ko\nvirtio_blk.ko\nvirtio_console.ko\nvirtio_dma_buf.ko\nvirtio_mmio.ko\nvirtio_pci.ko\nvirtio_pci_legacy_dev.ko\nvirtio_pci_modern_dev.ko\nvirtio-rng.ko\nvmw_vsock_virtio_transport.ko\nfailover.ko\nnet_failover.ko\nvirtio_net.ko\n"
            modules_dep_data = b"/lib/modules/system_heap.ko:\n/lib/modules/virtio_mmio.ko:\n/lib/modules/virtio_pci_legacy_dev.ko:\n/lib/modules/virtio-rng.ko:\n/lib/modules/virtio_blk.ko:\n/lib/modules/virtio_dma_buf.ko:\n/lib/modules/virtio_console.ko:\n/lib/modules/vmw_vsock_virtio_transport.ko:\n/lib/modules/virtio_pci.ko: /lib/modules/virtio_pci_legacy_dev.ko /lib/modules/virtio_pci_modern_dev.ko\n/lib/modules/virtio_pci_modern_dev.ko:\n/lib/modules/failover.ko:\n/lib/modules/net_failover.ko: /lib/modules/failover.ko\n/lib/modules/virtio_net.ko: /lib/modules/net_failover.ko /lib/modules/failover.ko\n"
            modules_alias_data = b"# Aliases extracted from modules themselves.\nalias of:N*T*Cvirtio,mmio virtio_mmio\nalias of:N*T*Cvirtio,mmioC* virtio_mmio\nalias virtio:d00000004v* virtio_rng\nalias virtio:d00000002v* virtio_blk\nalias virtio:d00000003v* virtio_console\nalias virtio:d0000000Bv* virtio_console\nalias virtio:d00000013v* vmw_vsock_virtio_transport\nalias pci:v00001AF4d*sv*sd*bc*sc*i* virtio_pci\nalias virtio:d00000001v* virtio_net\n"
        else:
            modules_dir = os.path.join(bridge_dir, 'modules_whpx_5_15')
            # system_heap: this kernel has neither /dev/ion nor a built-in DMA-BUF system
            # heap, and without one every Codec2 decoder fails to start, silently.
            modules_load_data = b"system_heap.ko\nvirtio_blk.ko\nvirtio_console.ko\nvirtio_dma_buf.ko\nvirtio_mmio.ko\nvirtio_pci.ko\nvirtio_pci_modern_dev.ko\nvirtio-rng.ko\nvmw_vsock_virtio_transport.ko\nfailover.ko\nnet_failover.ko\nvirtio_net.ko\n"
            modules_dep_data = b"/lib/modules/system_heap.ko:\n/lib/modules/virtio_mmio.ko:\n/lib/modules/virtio-rng.ko:\n/lib/modules/virtio_blk.ko:\n/lib/modules/virtio_dma_buf.ko:\n/lib/modules/virtio_console.ko:\n/lib/modules/vmw_vsock_virtio_transport.ko:\n/lib/modules/virtio_pci.ko: /lib/modules/virtio_pci_modern_dev.ko\n/lib/modules/virtio_pci_modern_dev.ko:\n/lib/modules/failover.ko:\n/lib/modules/net_failover.ko: /lib/modules/failover.ko\n/lib/modules/virtio_net.ko: /lib/modules/net_failover.ko /lib/modules/failover.ko\n"
            modules_alias_data = b"# Aliases extracted from modules themselves.\nalias of:N*T*Cvirtio,mmio virtio_mmio\nalias of:N*T*Cvirtio,mmioC* virtio_mmio\nalias virtio:d00000004v* virtio_rng\nalias virtio:d00000002v* virtio_blk\nalias virtio:d00000003v* virtio_console\nalias virtio:d0000000Bv* virtio_console\nalias virtio:d00000013v* vmw_vsock_virtio_transport\nalias pci:v00001AF4d*sv*sd*bc*sc*i* virtio_pci\nalias virtio:d00000001v* virtio_net\n"

        # zram: compressed swap in RAM. Every image's fstab asks for it (zram0, 75% of RAM) and
        # Android sizes its memory management around it, but these kernels build it as a module
        # that only the image's own (mismatched) vendor modules provide. The copies here come from
        # the stock image with the same kernel build. Listed only when present: first-stage init
        # stops the boot if a listed module is missing.
        if all(os.path.isfile(os.path.join(modules_dir, m)) for m in ('zsmalloc.ko', 'zram.ko')):
            modules_load_data += b"zsmalloc.ko\nzram.ko\n"
            modules_dep_data += b"/lib/modules/zsmalloc.ko:\n/lib/modules/zram.ko: /lib/modules/zsmalloc.ko\n"

        if os.path.isdir(modules_dir):
            for m in os.listdir(modules_dir):
                if m.endswith('.ko'):
                    with open(os.path.join(modules_dir, m), 'rb') as mf:
                        whpx_modules[m] = mf.read()

        # 132-byte standalone ARM64 Linux ELF that exits with status 0
        arm64_exit0_elf = (
            struct.pack(
                '<16sHHIQQQIHHHHHH',
                b'\x7fELF\x02\x01\x01\x00\x00\x00\x00\x00\x00\x00\x00\x00',
                2, 0xb7, 1, 0x400078, 64, 0, 0, 64, 56, 1, 64, 0, 0
            ) +
            struct.pack('<IIQQQQQQ', 1, 5, 0, 0x400000, 0x400000, 132, 132, 0x1000) +
            struct.pack('<III', 0x52800ba8, 0xd2800000, 0xd4000001)
        )

        try:
            # Patch seg1 to inject /adb_keys and init.ranchu.rc
            bsize1 = struct.unpack('<I', seg1_bytes[4:8])[0] if seg1_bytes[:4] == b'\x02\x21\x4c\x18' else struct.unpack('<I', seg1_bytes[:4])[0]
            payload1 = seg1_bytes[8:8+bsize1] if seg1_bytes[:4] == b'\x02\x21\x4c\x18' else seg1_bytes[4:4+bsize1]
            decomp1 = lz4.block.decompress(payload1, uncompressed_size=10*1024*1024)
            entries1 = parse_cpio(decomp1)

            new_cpio1 = bytearray()
            ino1 = 1000
            if adb_keys_data:
                new_cpio1.extend(make_cpio_entry(ino1, 0o100644, 'adb_keys', adb_keys_data))
                ino1 += 1
            new_cpio1.extend(make_cpio_entry(ino1, 0o100644, 'force_debuggable', b''))
            ino1 += 1
            adb_debug_prop = b"ro.debuggable=1\nro.force.debuggable=1\nro.adb.secure=0\npersist.sys.usb.config=adb\nservice.adb.tcp.port=5555\n"
            new_cpio1.extend(make_cpio_entry(ino1, 0o100644, 'adb_debug.prop', adb_debug_prop))
            ino1 += 1
            new_cpio1.extend(make_cpio_entry(ino1, 0o100755, 'whpx_true', arm64_exit0_elf))
            ino1 += 1

            init_rc_content = b"""
on early-init
    setprop service.adb.tcp.port 5555
    setprop ro.debuggable 1
    setprop ro.force.debuggable 1
    setprop ro.adb.secure 0

on post-fs-data
    setprop service.adb.tcp.port 5555
    setprop persist.sys.usb.config adb
    start adbd

on boot
    setprop service.adb.tcp.port 5555
    start adbd
"""
            new_cpio1.extend(make_cpio_entry(ino1, 0o100755, 'init.ranchu.rc', init_rc_content.strip() + b'\n'))
            ino1 += 1

            whpx_init_bin = None
            whpx_init_path = os.path.join(bridge_dir, 'whpx_init')
            if os.path.isfile(whpx_init_path):
                with open(whpx_init_path, 'rb') as f:
                    whpx_init_bin = f.read()

            has_build_prop = False
            for name, hdr, data in entries1:
                mode = int(hdr[14:22], 16)
                if name == 'init' and whpx_init_bin:
                    # Stock init renamed to init.stock
                    new_cpio1.extend(make_cpio_entry(ino1, mode, 'init.stock', data))
                    ino1 += 1
                    # whpx_init injected as /init
                    new_cpio1.extend(make_cpio_entry(ino1, 0o100755, 'init', whpx_init_bin))
                    ino1 += 1
                    continue
                if 'build.prop' in name:
                    has_build_prop = True
                    data += b'\nro.debuggable=1\nro.force.debuggable=1\nro.adb.secure=0\npersist.sys.usb.config=adb\nservice.adb.tcp.port=5555\n'
                new_cpio1.extend(make_cpio_entry(ino1, mode, name, data))
                ino1 += 1
            if not has_build_prop:
                prop_data = b'ro.debuggable=1\nro.force.debuggable=1\nro.adb.secure=0\npersist.sys.usb.config=adb\nservice.adb.tcp.port=5555\n'
                new_cpio1.extend(make_cpio_entry(ino1, 0o100644, 'system/etc/ramdisk/build.prop', prop_data))
                ino1 += 1
            new_cpio1.extend(make_cpio_entry(ino1, 0, 'TRAILER!!!', b''))
            while len(new_cpio1) % 512 != 0: new_cpio1.append(0)

            new_p1 = lz4.block.compress(bytes(new_cpio1), store_size=False)
            new_seg1 = b'\x02\x21\x4c\x18' + struct.pack('<I', len(new_p1)) + new_p1 + b'\x00\x00\x00\x00'
            pad1 = (4 - (len(new_seg1) % 4)) % 4
            if pad1 > 0: new_seg1 += b'\x00' * pad1

            # Patch seg2 for fstab
            bsize2 = struct.unpack('<I', seg2_bytes[4:8])[0] if seg2_bytes[:4] == b'\x02\x21\x4c\x18' else struct.unpack('<I', seg2_bytes[:4])[0]
            payload2 = seg2_bytes[8:8+bsize2] if seg2_bytes[:4] == b'\x02\x21\x4c\x18' else seg2_bytes[4:4+bsize2]
            decomp2 = lz4.block.decompress(payload2, uncompressed_size=10*1024*1024)
            entries2 = parse_cpio(decomp2)
            new_cpio2 = bytearray()
            ino2 = 2000

            # Inject force_debuggable, whpx_true, and adb_keys into stage 2 as well
            new_cpio2.extend(make_cpio_entry(ino2, 0o100644, 'force_debuggable', b''))
            ino2 += 1
            new_cpio2.extend(make_cpio_entry(ino2, 0o100644, 'first_stage_ramdisk/force_debuggable', b''))
            ino2 += 1
            new_cpio2.extend(make_cpio_entry(ino2, 0o100644, 'adb_debug.prop', adb_debug_prop))
            ino2 += 1
            new_cpio2.extend(make_cpio_entry(ino2, 0o100644, 'first_stage_ramdisk/adb_debug.prop', adb_debug_prop))
            ino2 += 1
            new_cpio2.extend(make_cpio_entry(ino2, 0o100755, 'whpx_true', arm64_exit0_elf))
            ino2 += 1
            new_cpio2.extend(make_cpio_entry(ino2, 0o100755, 'first_stage_ramdisk/whpx_true', arm64_exit0_elf))
            ino2 += 1
            if adb_keys_data:
                new_cpio2.extend(make_cpio_entry(ino2, 0o100644, 'first_stage_ramdisk/adb_keys', adb_keys_data))
                ino2 += 1
                new_cpio2.extend(make_cpio_entry(ino2, 0o100644, 'adb_keys', adb_keys_data))
                ino2 += 1

            fstab_patched_data = None
            added_modules = set()
            for name, hdr, data in entries2:
                mode = int(hdr[14:22], 16)
                if name.endswith('.ko'):
                    # Skip stock modules; inject matching whpx_modules below
                    continue
                if name == 'lib/modules/modules.load':
                    data = modules_load_data
                elif name == 'lib/modules/modules.dep':
                    data = modules_dep_data
                elif name == 'lib/modules/modules.alias':
                    data = modules_alias_data
                if name == 'first_stage_ramdisk/fstab.ranchu' or name == 'fstab.ranchu':
                    lines = []
                    for line in data.splitlines():
                        l = line.decode('utf-8', errors='ignore')
                        parts = l.split()
                        if len(parts) >= 2 and parts[1] == '/metadata':
                            lines.append('/dev/block/vdd    /metadata    ext4    noatime,nosuid,nodev    wait,formattable,first_stage_mount')
                            continue
                        if len(parts) >= 2 and parts[1] == '/data':
                            lines.append('/dev/block/vdc   /data     ext4      noatime,nosuid,nodev,nomblk_io_submit,errors=panic   wait,check,quota,latemount')
                            continue
                        lines.append(l)
                    data = '\n'.join(lines).encode('utf-8') + b'\n'
                    fstab_patched_data = data
                new_cpio2.extend(make_cpio_entry(ino2, mode, name, data))
                ino2 += 1

            # Inject all universal WHPX kernel modules
            for mod_name, mod_bytes in whpx_modules.items():
                new_cpio2.extend(make_cpio_entry(ino2, 0o100644, f'lib/modules/{mod_name}', mod_bytes))
                ino2 += 1

            if fstab_patched_data:
                new_cpio2.extend(make_cpio_entry(ino2, 0o100644, 'fstab.ranchu', fstab_patched_data))
                ino2 += 1
            new_cpio2.extend(make_cpio_entry(ino2, 0, 'TRAILER!!!', b''))
            while len(new_cpio2) % 512 != 0: new_cpio2.append(0)
            
            new_payload2 = lz4.block.compress(bytes(new_cpio2), store_size=False)
            new_seg2 = b'\x02\x21\x4c\x18' + struct.pack('<I', len(new_payload2)) + new_payload2 + b'\x00\x00\x00\x00'
            pad2 = (4 - (len(new_seg2) % 4)) % 4
            if pad2 > 0: new_seg2 += b'\x00' * pad2
            
            patched_base = new_seg1 + new_seg2 + gfx_overlay_segment(sys_dir, bridge_dir)
            with open(out_base, 'wb') as f:
                f.write(patched_base)
            print(f"[prepare_whpx_initrd] Successfully built patched dual-segment ramdisk base: {len(patched_base)} bytes")
            return True
        except Exception as e:
            print(f"[prepare_whpx_initrd] Decompression error: {e}")
            return False
    else:
        with open(out_base, 'wb') as f:
            f.write(orig)
        return True

if __name__ == '__main__':
    if len(sys.argv) < 3:
        print("Usage: python prepare_whpx_initrd.py <sysDir> <avdDir>")
        sys.exit(1)
    s_dir = sys.argv[1]
    a_dir = sys.argv[2]
    ok = prepare_ramdisk_base(s_dir, a_dir)
    sys.exit(0 if ok else 1)
