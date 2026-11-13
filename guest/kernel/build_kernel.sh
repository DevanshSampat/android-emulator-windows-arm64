#!/bin/bash
# Builds kernel_whpx_stimer and modules_whpx_6_1_stimer (run in WSL on ARM64):
#   wsl -d Ubuntu -- bash build_kernel.sh <output dir, e.g. /mnt/c/.../whpx_bridge>
#
# Kernel: Google's android14-6.1 GKI at the commit of the stock kernel_whpx_universal
# (6.1.23, ab9964412), plus whpx_stimer.c. Config: the stock kernel's own config
# (stock_android14-6.1.config, read from its IKCONFIG) with
#   - WHPX_STIMER on,
#   - the virtio, failover and system_heap modules on (what Android's virtual-device build adds),
#   - WERROR off (newer clang), TRIM_UNUSED_KSYMS off (its symbol list is a Google build path),
#   - MODULE_SIG_PROTECT off (modules are signed with this build's key, not Google's).
# The kernel release string stays the stock one. Everything else, including struct layouts and
# exported symbol CRCs, matches stock; the build checks the CRCs against stock modules.
#
# Needs: sudo apt install clang lld llvm dwarves libssl-dev libelf-dev bc flex bison kmod git python3
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
DEST=${1:?usage: build_kernel.sh <output dir>}
W=~/whpx-kernel
K=$W/common
O=$W/out
COMMIT=7e35917775b8b3e3346a87f294e334e258bf15e6
export KBUILD_BUILD_USER=whpx KBUILD_BUILD_HOST=whpx-build
export GIT_DIR=/nonexistent   # keep scripts/setlocalversion from appending git state
MK="make -C $K O=$O ARCH=arm64 LLVM=1 LLVM_IAS=1"

if [ ! -d "$K" ]; then
  mkdir -p "$W"
  (unset GIT_DIR; git init -q "$K" && cd "$K" &&
   git fetch -q --depth 1 https://android.googlesource.com/kernel/common $COMMIT &&
   git checkout -q FETCH_HEAD)
fi

# --- source changes (idempotent) ---
cp "$HERE/whpx_stimer.c" "$K/drivers/clocksource/whpx_stimer.c"
if ! grep -q WHPX_STIMER "$K/drivers/clocksource/Kconfig"; then
  cat >> "$K/drivers/clocksource/Kconfig" <<'EOF'

config WHPX_STIMER
	bool "Hyper-V synthetic timer tick for QEMU on Windows Hypervisor Platform"
	depends on ARM64 && ARM_GIC_V3 && HAVE_ARM_SMCCC
	help
	  Ticks from Hyper-V's synthetic timer instead of the architected
	  timer when "whpx_stimer" is on the kernel command line.
EOF
  echo 'obj-$(CONFIG_WHPX_STIMER)		+= whpx_stimer.o' >> "$K/drivers/clocksource/Makefile"
fi
if ! grep -q whpx_stimer_init "$K/arch/arm64/kernel/time.c"; then
  python3 - "$K/arch/arm64/kernel/time.c" <<'EOF'
import sys
p = sys.argv[1]
s = open(p).read()
s = s.replace("void __init time_init(void)\n{",
  "#ifdef CONFIG_WHPX_STIMER\nextern void __init whpx_stimer_init(void);\n#endif\n\nvoid __init time_init(void)\n{", 1)
s = s.replace("\ttimer_probe();\n", "\ttimer_probe();\n#ifdef CONFIG_WHPX_STIMER\n\twhpx_stimer_init();\n#endif\n", 1)
open(p, "w").write(s)
EOF
fi
# Host tool only (resolve_btfids): newer glibc returns const char * from strchr.
sed -i 's/next_path = strchr(s, /next_path = (char *)strchr(s, /' "$K/tools/lib/bpf/libbpf.c"

# --- config ---
mkdir -p "$O"
cp "$HERE/stock_android14-6.1.config" "$O/.config"
"$K/scripts/config" --file "$O/.config" \
  -e WHPX_STIMER -d WERROR -d TRIM_UNUSED_KSYMS -d MODULE_SIG_PROTECT \
  --set-str LOCALVERSION "-android14-4-00257-g7e35917775b8-ab9964412" \
  --set-str UNUSED_KSYMS_WHITELIST "" --set-str SYSTEM_TRUSTED_KEYS "" \
  -m VIRTIO_VSOCKETS -m FAILOVER -m NET_FAILOVER -m VIRTIO_BLK -m VIRTIO_NET -m VIRTIO_CONSOLE \
  -m HW_RANDOM_VIRTIO -m DMABUF_HEAPS_SYSTEM -m VIRTIO_PCI -e VIRTIO_PCI_LEGACY -m VIRTIO_MMIO \
  -m VIRTIO_DMA_SHARED_BUFFER
$MK olddefconfig >/dev/null

# --- build ---
$MK -j"$(nproc)" Image modules
[ "$(cat $O/include/config/kernel.release)" = "6.1.23-android14-4-00257-g7e35917775b8-ab9964412" ] \
  || { echo "unexpected kernel release"; exit 1; }

# --- stage ---
rm -rf $W/modinst
$MK INSTALL_MOD_PATH=$W/modinst INSTALL_MOD_STRIP=1 DEPMOD=true modules_install >/dev/null
MODS="failover net_failover system_heap virtio-rng virtio_blk virtio_console virtio_dma_buf virtio_mmio
      virtio_net virtio_pci virtio_pci_legacy_dev virtio_pci_modern_dev vmw_vsock_virtio_transport zram zsmalloc"
mkdir -p "$DEST/modules_whpx_6_1_stimer"
rm -f "$DEST/modules_whpx_6_1_stimer/"*.ko
for m in $MODS; do
  cp "$(find $W/modinst -name "$m.ko" | head -1)" "$DEST/modules_whpx_6_1_stimer/"
done
gzip -9 -n -c $O/arch/arm64/boot/Image > "$DEST/kernel_whpx_stimer"

# --- ABI check: stock modules' symbol CRCs against this kernel and its modules ---
STOCK="$DEST/modules_whpx_6_1"
if [ -d "$STOCK" ]; then
  bad=0
  for ko in "$STOCK"/*.ko; do
    while read -r crc sym; do
      ours=$(awk -v s="$sym" '$2==s {print $1; exit}' $O/Module.symvers)
      if [ -z "$ours" ] || [ "$((ours))" != "$((crc))" ]; then echo "ABI: $(basename "$ko") $sym"; bad=1; fi
    done < <(modprobe --dump-modversions "$ko" 2>/dev/null)
  done
  [ $bad = 0 ] && echo "ABI check: all symbol CRCs match the stock build"
fi
ls -l "$DEST/kernel_whpx_stimer"
