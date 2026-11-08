@echo off
rem Builds ANGLE (libEGL.dll, libGLESv2.dll, vulkan-1.dll) for Windows ARM64.
rem Run from this folder. Set DEPOT_TOOLS to a depot_tools checkout.
setlocal
if "%DEPOT_TOOLS%"=="" (echo Set DEPOT_TOOLS to your depot_tools folder & exit /b 1)
set HERE=%~dp0
set DEPOT_TOOLS_WIN_TOOLCHAIN=0
set vs2022_install=C:\Program Files\Microsoft Visual Studio\18\Community
set GYP_MSVS_OVERRIDE_PATH=%vs2022_install%
set PATH=%DEPOT_TOOLS%;%PATH%
if not exist angle (
  git clone https://chromium.googlesource.com/angle/angle || exit /b 1
  git -C angle checkout 533fa5ced || exit /b 1
  copy /y "%HERE%angle.gclient" angle\.gclient >nul
)
cd angle
call gclient sync --no-history --shallow -j 8 || exit /b 1
git -C build apply --check "%HERE%angle-build-toolchain.patch" 2>nul && git -C build apply "%HERE%angle-build-toolchain.patch"
if not exist out\arm64 mkdir out\arm64
copy /y "%HERE%args.gn" out\arm64\args.gn >nul
call gn gen out\arm64 || exit /b 1
call ninja -C out\arm64 libEGL libGLESv2
