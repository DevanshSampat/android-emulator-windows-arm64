@echo off
rem Builds libgfxstream_backend.dll for Windows ARM64 with clang-cl.
rem Run from the folder that contains the patched gfxstream checkout. Set LLVM to an LLVM for
rem Windows ARM64 install (with clang-cl.exe and lld-link.exe in bin).
setlocal
if "%LLVM%"=="" (echo Set LLVM to your LLVM install folder & exit /b 1)
set VS=C:\Program Files\Microsoft Visual Studio\18\Community
call "%VS%\VC\Auxiliary\Build\vcvarsall.bat" arm64 >nul || exit /b 1
set PATH=%LLVM%\bin;%VS%\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin;%VS%\Common7\IDE\CommonExtensions\Microsoft\CMake\Ninja;%PATH%
if not exist build-gfxstream\build.ninja cmake -S gfxstream -B build-gfxstream -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_COMPILER=clang-cl -DCMAKE_CXX_COMPILER=clang-cl -DCMAKE_LINKER=lld-link -DBUILD_STANDALONE=ON -DDEPENDENCY_RESOLUTION=AOSP || exit /b 1
ninja -C build-gfxstream gfxstream_backend
