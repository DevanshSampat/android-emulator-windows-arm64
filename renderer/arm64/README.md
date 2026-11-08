# Native ARM64 graphics libraries

The renderer (`GfxstreamPipeServer.exe`) runs natively on ARM64 and loads these from `emulator\lib64-arm64`:

| File | Built from |
|---|---|
| `libgfxstream_backend.dll` | [gfxstream](https://github.com/google/gfxstream) at `e4fae08`, plus `gfxstream-windows-arm64.patch` |
| `libEGL.dll`, `libGLESv2.dll` | [ANGLE](https://chromium.googlesource.com/angle/angle) at `533fa5ced` (2.1.22762, the revision Google's emulator uses), Vulkan back end |
| `vulkan-1.dll` | The Vulkan loader built with ANGLE. ANGLE loads it from its own folder. |

`NOTICE.txt` lists every component compiled into them, with its licence. It ships in `lib64-arm64`.

The x64 renderer (`GfxstreamPipeServer.x64.exe`, the same source built for x64) ships too. It runs under Windows' x64 emulation with the Android Emulator's own x64 libraries in `lib64`. Set `WHPX_RENDERER=x64` before starting the emulator to use it.

## Why the patches

`gfxstream-windows-arm64.patch` adapts standalone gfxstream to how this emulator calls it:

- **Locks the frontend's context and resource maps.** Standalone gfxstream expects one VMM thread to call it. The renderer calls it from every guest connection's threads, which corrupted its heap.
- **Gives pipe reads their own buffer.** Reads and writes on one pipe shared a buffer, so concurrent use mixed up command streams. The read buffer is sized for the reads that happen, so it doesn't add a zero-filled copy of the pipe's buffer to every guest connection.
- **Makes the one-byte pipe poll non-blocking.** It waits up to 2 ms for data and then reports "no data", instead of blocking. The renderer flushes held reply bytes when a read is empty, so a blocking read could hang apps (Chrome did) with a reply stuck in the buffer.
- **Builds with clang-cl for Windows ARM64** (`time.cpp` and `vk_common_operations.cpp`).

`angle-build-toolchain.patch` lets Chromium's build scripts use a local Visual Studio 2026 install (version 18) and Windows SDK: the scripts expect Google's packaged toolchain and older version names.

The renderer also turns off three ANGLE features (`asyncCommandQueue`, `asyncCommandBufferResetAndGarbageCleanup`, `asyncGarbageCleanup`) through `ANGLE_FEATURE_OVERRIDES_DISABLED`. With gfxstream's many contexts on many threads they crashed in context flush.

## Build

Requirements: Windows 11 ARM64, Visual Studio 2026 with the C++ ARM64 tools and CMake, LLVM for Windows ARM64 (clang-cl and lld-link), Git, Python 3, and [depot_tools](https://commondatastorage.googleapis.com/chrome-infra-docs/flat/depot_tools/docs/html/depot_tools_tutorial.html) for ANGLE.

### gfxstream

```bat
git clone https://github.com/google/gfxstream && cd gfxstream && git checkout e4fae08
git apply ..\gfxstream-windows-arm64.patch
cd .. && set LLVM=C:\path\to\llvm && build-gfxstream.cmd
```

The DLL is `build-gfxstream\gfxstream_backend.dll`. Install it as `lib64-arm64\libgfxstream_backend.dll`. It links the C++ runtime statically, so it depends only on Windows system DLLs: a clean Windows install has no ARM64 Visual C++ runtime. ANGLE's build does the same by default.

The renderer itself is an ARM64 .NET Framework program, so it needs Windows 11 22H2 or later, which includes .NET Framework 4.8.1 for ARM64.

### ANGLE

```bat
set DEPOT_TOOLS=C:\path\to\depot_tools
build-angle.cmd
```

`build-angle.cmd` fetches ANGLE at `533fa5ced`, syncs its dependencies, applies `angle-build-toolchain.patch` to `build\`, and builds `out\arm64\libEGL.dll`, `libGLESv2.dll` and `vulkan-1.dll` with the arguments in `args.gn`.
