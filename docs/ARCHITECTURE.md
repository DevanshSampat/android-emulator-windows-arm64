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
2. API 34 and above boot `kernel_whpx_stimer`: the stock 6.1.23 GKI kernel rebuilt with `guest/kernel/whpx_stimer.c`, with the modules in `modules_whpx_6_1_stimer` from the same build. QEMU runs with `-accel whpx,hyperv=on` and the kernel gets `whpx_stimer`, so the tick comes from Hyper-V's synthetic timer instead of the architected timer. On some Snapdragon X hosts (X1-26-100) Hyper-V's arch-timer emulation stalls the vCPU between ticks and the guest runs about 100 times slower; the WSL2 kernel avoids this the same way. `WHPX_TIMER=arch` boots the stock `kernel_whpx_universal` with `modules_whpx_6_1` instead. Older images boot `kernel_p7_permissive` (5.15.119). `prepare_whpx_initrd.py` adds the matching modules from `modules_whpx_6_1` or `modules_whpx_5_15`. These include `zram.ko` and `zsmalloc.ko`, taken from the stock image with the same kernel build (API 33 `vendor.img`; API 34 `system_dlkm` inside `system.img`), so the zram swap that every image's fstab asks for is set up. The guest gets the RAM set in Device Manager (`hw.ramSize`), with a floor of 2 GB.
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
- Colour buffers live until the guest closes their refcount pipe. `gfx_forwarder` reports that to the host, and the host releases the buffer after a grace period. Handles are numbered per renderer instance, so the forwarder keeps its control connection open, starts a new generation when the renderer goes away, and only reports pipes of the current generation. Otherwise, after a renderer restart, buffers from the old instance would free unrelated live buffers that reuse their numbers.
- `FixChooseConfig` rewrites `EGL_DONT_CARE` for bitmask attributes, which the host would otherwise match as a mask.

## Display

The renderer watches compose and post operations for the current display buffer. To avoid a full-size readback, it composes that buffer into a viewer-sized colour buffer with `rcComposeWithoutPost` and reads the result. Screenshots ask for a full-size frame instead.

Frames reach the bridge through a shared file: a 16-byte header, two frame slots, and an 8-byte tail holding the size the viewer wants.

A capture starts when a compose is seen in the guest's stream, after a 5 ms settle, because the renderer may not have run the compose yet. Captures are not padded to a fixed interval: guest composes already pace them, and padding made a cycle longer than a 120 Hz frame. If a capture is identical to the frame already published, the compose was probably not finished, so the renderer reads again after 1 ms, up to three times, instead of publishing a repeat.

Without native fence sync the guest ends every `eglSwapBuffers` with `glFinishRoundTrip`, which waits for the host GPU. Replying after only a flush was tried and rejected: under ANGLE another context could then read a buffer before the frame was in it, so every other frame showed older content. The renderer hides `ANDROID_EMU_sync_buffer_data`, which made every `glBufferData` wait for a host reply (about 3.7 ms per frame while scrolling).

`WHPX_PERF=1` makes the renderer write per-connection timings to `%TEMP%\whpx_perf.log` once a second. While `%TEMP%\whpx_perf_bridge` exists, the bridge writes frames sent to Studio per second and their build and send times to `%TEMP%\whpx_stream_perf.log`. Creating `%TEMP%\whpx_cb_dump` makes the renderer write every tracked colour buffer (size, host references, pinned, queued, idle time) to `%TEMP%\whpx_cb_dump.txt` once.

Guest vsync follows the host monitor's refresh rate. Phones and tablets also get `peak_refresh_rate` and `min_refresh_rate`, because Android otherwise caps app rendering at 60 fps.

The renderer opts QEMU, itself and the bridge out of Windows power throttling. They have no window, so Windows treats them as background work.

## Networking, input and power

- Networking uses QEMU user mode (slirp). `DefaultNetworkAgent` registers the connection with Android, and `whpx_init` keeps the routing rule alive.
- Touch and keys reach Android through `WhpxInputDaemon`, which injects events through the input manager. Mouse wheel and touchpad scrolling arrive as `ACTION_SCROLL` mouse events at the pointer position, scaled from Studio's 120 units per notch.
- `whpx_loop.sh` sets the three animation scales to Android's default of 1.0 on each boot.
- There is no battery device, so `whpx_init` provides one under `/sys/class/power_supply`, and `whpx_loop.sh` applies values set in Extended Controls.

## Device frames

Android Studio decodes WebP through an x64 library that cannot load in its ARM64 runtime. The installer ships skin images as PNG data under their original names.

## Alternative not taken

Exposing gfxstream as a real virtio-gpu device would remove the vendor patching and `gfx_forwarder`, because stock images already support it. It would need a virtio-gpu backend in QEMU on Windows ARM64. It remains the most promising route to lower graphics latency.

## Known issue

On Android 13, a reply to the graphics allocator can go missing, usually within minutes of boot. The allocator holds its lock while it waits, so every app's buffer request queues behind it, the display stops, and the watchdog restarts the framework. The host is idle at that point and has answered every request it logged. The cause is unknown, and it has not been reproduced on demand.
