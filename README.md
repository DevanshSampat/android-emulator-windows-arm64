# Android Emulator for Windows ARM64

Runs Android Virtual Devices natively on Windows 11 ARM64 PCs (Snapdragon X and similar), using
QEMU with Windows Hypervisor Platform (WHPX) and Android's gfxstream renderer. It replaces the
stock emulator inside Android Studio, so AVDs are created and started the normal way.

**To just use it**, install the bundled Android Studio from
[android-studio-arm64-releases](https://github.com/DevanshSampat/android-studio-arm64-releases/releases/latest).
This repository holds the source.

## Status

Stock ARM64 system images boot with no per-image patching. The bridge adapts them at boot.

| Form factor | Tested on |
|---|---|
| Phone | Android 13, 14, 16 and 17 (Pixel 7, 8a, 9a, 10 Pro) |
| TV | Android 12 and 14 (720p, 4K) |
| Wear OS | Android 17 |

Working: display, touch, networking, adb, location, video playback, Google sign-in, Extended
Controls, and guest vsync that follows the host monitor's refresh rate.

### Known limitations

- Google publishes no ARM64 TV images for API 28 to 30 or API 35, so those cannot run.
- There is no audio device yet.
- Video uses software decoders in the guest, so high resolutions depend on the host CPU.
- The 4K TV AVD renders at about 14 fps.
- Scrolling on a phone AVD shows about 116 to 118 distinct frames per second on a 120 Hz monitor
  (Pixel 10 Pro, Settings). Each frame still waits for the host GPU to finish, because the guest
  has no native fence sync.
- An intermittent freeze was seen on Android 13 and could not be reproduced on demand: a reply to
  the graphics allocator goes missing and the display stops. See `docs/ARCHITECTURE.md`.
- Tested on one Snapdragon X machine so far.

## How it works

```
Android Studio
   | gRPC (display, input, Extended Controls)
Bridge (WhpxEmulatorBridge)  --starts-->  QEMU + WHPX  --runs-->  Stock Android image
   |                                                                  |
   | shared frame file                                                | GL commands
Renderer (GfxstreamPipeServer + gfxstream) <---- TCP through slirp --- gfx_forwarder
```

| Folder | Contents |
|---|---|
| `bridge/` | `emulator.exe` launcher, gRPC emulator service, QEMU launch, Extended Controls |
| `renderer/` | Hosts gfxstream, relays guest GL traffic, captures frames |
| `guest/` | `whpx_init` (first-stage init), GL forwarder, address-space stubs, agents, boot scripts |
| `image-tools/` | Builds the per-image ramdisk and patches stock vendor binaries at first boot |
| `installer/` | Assembles the distributable emulator and the NSIS installer |
| `docs/` | Architecture |

Stock images expect goldfish devices that do not exist here. At first boot, `gfx_autopatch.py`
retargets those paths in the image's vendor binaries, and the result is cached per image.

## Building

Requires Windows 11 ARM64, Android Studio, the .NET Framework 4 compiler, and WSL with
`gcc-aarch64-linux-gnu` and `nsis`.

```bat
:: bridge\ and renderer\ (javac from Android Studio's JBR; classpath is Studio's gRPC and proto jars)
csc -platform:x64 -optimize+ -unsafe -out:GfxstreamPipeServer.exe GfxstreamPipeServer.cs
csc -optimize+ -out:WhpxLauncher.exe WhpxLauncher.cs
javac -nowarn -cp <studio jars> WhpxExtendedControls.java WhpxEmulatorBridge.java
```

```sh
# guest/
wsl -d Ubuntu -- bash build_whpx_init.sh
```

`whpx_bridge` lives in `<sdk>\emulator`, and `WhpxLauncher.exe` is copied to
`<sdk>\emulator\emulator.exe`. Everything is resolved relative to that folder.

These are **not in this repository** and ship in the installer: QEMU for Windows ARM64, gfxstream
and its dependencies from the Android Emulator, prebuilt kernels and kernel modules, a Python
runtime with `lz4` and `capstone`, and the patched guest libraries.

## Licences

The code here is under the Apache License 2.0. The installer also carries third-party software
under its own terms, including QEMU (GPL v2, licence files alongside the binaries, source at
[qemu.org](https://www.qemu.org/download/)) and Android components (Apache 2.0).

This project is not affiliated with or endorsed by Google.
