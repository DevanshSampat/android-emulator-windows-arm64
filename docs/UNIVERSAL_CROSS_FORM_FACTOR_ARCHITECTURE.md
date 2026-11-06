# Universal Cross-Form-Factor Architecture (Phones, Tablets, Watches, TVs, Automotive)

To make any emulator of any form factor and Android version (API 30–37+) start and run flawlessly out of the box on Windows ARM64 WHPX without manual patching, here is the comprehensive architectural approach:

---

## 1. Form-Factor & Version Agnostic Kernel Dispatch

### Kernel-to-Ramdisk Module Matching
In newer Android versions (Android 14/15/16+ across Wear OS, Android TV, Tablets, and Phones), first-stage init dynamically loads critical hardware modules (`virtio_blk.ko`, `virtio_net.ko`, `virtio_pci.ko`) directly from the system image's `ramdisk.img`.

These kernel modules strictly enforce symbol versioning (`module_layout`). Attempting to use mismatched kernels across different API levels causes kernel module load failures and storage mount hangs.

### Approach
- **Modern System Images (API 34+ across Wear OS, TV, Automotive, Phones):** `WhpxEmulatorBridge` dynamically inspects the system image. When running on WHPX (which operates with 4KB translation on Windows ARM64 hosts), 4KB GKI/universal kernels (Linux 6.1 `kernel_whpx_universal`) are utilized with dynamically matching VirtIO/Goldfish modules injected into Segment 2 of the ramdisk.
- **Legacy System Images (API 30–33):** Images that require goldfish legacy device trees or have rigid SELinux enforcement route automatically to our permissive 4KB kernel (`kernel_p7_permissive`).

---

## 2. Dynamic Memory Page-Size & Filesystem Alignment

### Form Factors Affected
- Modern high-performance phones/tablets frequently use 16KB page sizes (`ps16k`).
- Wear OS watches, Android TVs, and standard images use 4KB page sizes.

### Approach
- **Bridge Inspection:** The bridge inspects `config.ini` and image metadata for page size flags (`ps16k` or `tag.ids = 16kb`).
- **Disk Partitions (`cache.img`, `userdata.img`):** Formats ext4 partitions with matching block sizes: `-b 16384` for 16KB targets and `-b 4096` for 4KB targets. This prevents kernel filesystem mount rejections (`blocksize larger than page size`).

---

## 3. Form-Factor Adaptive Display & Input Pipeline

### Wear OS / Android TV / Automotive UI Characteristics
- **Wear OS:** Round or square displays (e.g. 454×454), circular touch coordinate space, distinct navigation (hardware crown, rotary events).
- **Android TV:** 1080p/4K 16:9 fixed orientation, DPAD and remote control keycodes (`KEYCODE_DPAD_CENTER`, `BACK`, `HOME`, `PLAY/PAUSE`).
- **Tablets / Foldables:** Multi-window dynamic orientation and hinge states.

### Approach
- The bridge reads `hw.lcd.width`, `hw.lcd.height`, `hw.lcd.density`, and device tag (`android-wear`, `android-tv`, `google_apis`) from `config.ini`.
- Configures the GfxStream pipeline and shared memory frame buffers to the exact dimensions of the form factor dynamically.
- Configures `WhpxInputDaemon` to map laptop arrow keys and Enter directly to Android TV DPAD keys when running a TV target, standard touch/gestures for phones/tablets/watches, and rotary scroll simulation for Wear OS.

---

## 4. Zero-Touch Post-Boot Payload Auto-Provisioning

### The Problem
Previously, custom patches (hardware acceleration fix, network agent, input daemon) required manual copying or individual script launches.

### The Universal Solution (Zero-Touch Provisioning)
- **ADB Lifecycle Hook:** `WhpxEmulatorBridge` runs an automated background supervisor loop that detects whenever an AVD comes online via ADB.
- **Form-Factor Aware Injection:**
  - Installs the self-healing network supervisor (`DefaultNetworkAgent` with infinite respawn) so internet and DNS work on every device.
  - Installs the unified keyboard/input daemon (`WhpxInputDaemon`).
  - Applies graphics renderer overrides conditionally based on API level (e.g. enabling hardware-accelerated dialog rendering on API 33 where software buffers fail to blit).
- **Zero User Intervention:** Create an emulator in Android Studio (phone, watch, or TV) and click "Run"; the bridge handles complete provisioning transparently.
