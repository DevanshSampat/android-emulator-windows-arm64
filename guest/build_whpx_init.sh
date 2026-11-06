#!/bin/bash
# Build the static guest binaries (run in WSL / any aarch64 or cross toolchain):
#   wsl -d Ubuntu -- bash build_whpx_init.sh
set -e
cd "$(dirname "$0")"
CC=gcc
[ "$(uname -m)" = aarch64 ] || CC=aarch64-linux-gnu-gcc
$CC -O2 -static -Wall -Wno-unused-result -Wno-format-truncation -o whpx_init.new whpx_init.c
mv -f whpx_init.new whpx_init
$CC -O2 -static -Wno-unused-result -o gfx_forwarder.new gfx_forwarder.c
mv -f gfx_forwarder.new gfx_forwarder
file whpx_init gfx_forwarder
