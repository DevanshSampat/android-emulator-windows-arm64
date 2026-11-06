# Architecture

## Goal

Run stock Android system images on Windows 11 ARM64 from Android Studio. System images are never modified on disk. Everything the guest needs is supplied at boot.

## Components

| Component | Runs on | Role |
|---|---|---|
| `WhpxLauncher` | Host | Installed as `emulator.exe`. Finds Android Studio and starts the bridge in a kill-on-close job. |
| `WhpxEmulatorBridge` | Host | gRPC emulator service for Studio, QEMU launch, AVD setup, Extended Controls. |
| `GfxstreamPipeServer` | Host | Hosts `libgfxstream_backend.dll`, relays guest GL streams, captures frames. |
| `prepare_whpx_initrd.py` | Host | Builds the per-image ramdisk. |
| `vendor_extract.py`, `gfx_autopatch.py` | Host | Read the image's vendor partition and patch its binaries. |
| `whpx_init` | Guest | Wraps PID 1 and applies the overlay before HALs start. |
| `gfx_forwarder` | Guest | Relays pipe traffic from a unix socket to TCP. |
| `whpx_loop.sh` | Guest | Applies runtime settings and restarts agents after a framework restart. |

## Boot

1. The bridge reads the AVD's `config.ini` and the image's metadata.
2. API 34 and above boot `kernel_whpx_universal` (6.1.23). Older images boot `kernel_p7_permissive` (5.15.119). `prepare_whpx_initrd.py` adds the matching modules from `modules_whpx_6_1` or `modules_whpx_5_15`.
3. On the first boot of an image, `vendor_extract.py` reads `vendor.img` (GPT, then ext4 or erofs) and `gfx_autopatch.py` patches its binaries by signature. Results are cached per image hash and shipped as an extra ramdisk segment.
4. `whpx_init` bind-mounts the patched files over `/vendor` when the property service appears, and starts `gfx_forwarder`.
5. After boot, the bridge pushes the input daemon, network agent and guest scripts through adb.

## Vendor patching

Stock images expect goldfish devices that QEMU does not provide here.

- The goldfish pipe is redirected to the abstract unix socket `@whpx_pipe`.
- `/dev/goldfish_address_space` is replaced by memfd stubs from `gas_stubs.S`, placed in code padding or a new load segment.
- DMA colour buffer calls are rewritten to their stream equivalents, because the host cannot read guest physical memory over TCP.
- YUV readback is removed from the decode path.

Sites are found by code signature rather than fixed offsets, so a new image normally needs no change. The patcher needs `capstone`. Without it the allocator is skipped and the display stack fails to start.

## Graphics

```
Guest GL client -> @whpx_pipe -> gfx_forwarder -> TCP via slirp -> GfxstreamPipeServer -> gfxstream -> ANGLE -> Vulkan
```

Each GL connection gets its own pipe resource and render thread. Details that matter:

- `ReplyFilter` hides capabilities the guest cannot use, such as native sync and DMA.
- Colour buffers live until the guest closes their refcount pipe. `gfx_forwarder` reports that to the host, and the host releases the buffer after a grace period.
- `FixChooseConfig` rewrites `EGL_DONT_CARE` for bitmask attributes, which the host would otherwise match as a mask.

## Display

The renderer watches compose and post operations for the current display buffer. To avoid a full-size readback, it composes that buffer into a viewer-sized colour buffer with `rcComposeWithoutPost` and reads the result. Screenshots ask for a full-size frame instead.

Frames reach the bridge through a shared file: a 16-byte header, two frame slots, and an 8-byte tail holding the size the viewer wants.

Guest vsync follows the host monitor's refresh rate. Phones and tablets also get `peak_refresh_rate` and `min_refresh_rate`, because Android otherwise caps app rendering at 60 fps.

The renderer opts QEMU, itself and the bridge out of Windows power throttling. They have no window, so Windows treats them as background work.

## Networking, input and power

- Networking uses QEMU user mode (slirp). `DefaultNetworkAgent` registers the connection with Android, and `whpx_init` keeps the routing rule alive.
- Touch and keys reach Android through `WhpxInputDaemon`, which injects events through the input manager.
- There is no battery device, so `whpx_init` provides one under `/sys/class/power_supply`, and `whpx_loop.sh` applies values set in Extended Controls.

## Device frames

Android Studio decodes WebP through an x64 library that cannot load in its ARM64 runtime. The installer ships skin images as PNG data under their original names.

## Alternative not taken

Exposing gfxstream as a real virtio-gpu device would remove the vendor patching and `gfx_forwarder`, because stock images already support it. It would need a virtio-gpu backend in QEMU on Windows ARM64. It remains the most promising route to lower graphics latency.

## Known issue

On Android 13, a reply to the graphics allocator can go missing, usually within minutes of boot. The allocator holds its lock while it waits, so every app's buffer request queues behind it, the display stops, and the watchdog restarts the framework. The host is idle at that point and has answered every request it logged. The cause is unknown, and it has not been reproduced on demand.
