#!/bin/bash
set -euo pipefail

# This script cross-compiles libandroid-sysvshm.so for both arm64 (glibc)
# and armhf (glibc) using aarch64-linux-gnu-gcc / arm-linux-gnueabihf-gcc.
#
# After building, the arm64 .so is also copied to the APK assets at
# app/src/main/assets/preload/libandroid-sysvshm-glibc.so for the native
# Linux Steam preload path (see GlibcPreloadInstaller.kt). Re-run this
# script whenever you change android_sysvshm.c to keep the asset in sync.
#
# The bionic-linked copy that ships inside imagefs.txz under
# imagefs/usr/lib/libandroid-sysvshm.so is built separately as part of
# the imagefs image — this script does not touch it.

cd "$(dirname "$0")"

rm -rf build64
mkdir build64
( cd build64 && cmake .. -DCMAKE_TOOLCHAIN_FILE=../cross-arm64.cmake && make -j"$(nproc)" )

rm -rf build
mkdir build
( cd build && cmake .. -DCMAKE_TOOLCHAIN_FILE=../cross-armhf.cmake && make -j"$(nproc)" )

# Stage the arm64 glibc build as an APK asset.
asset_dir="../app/src/main/assets/preload"
mkdir -p "$asset_dir"
aarch64-linux-gnu-strip build64/libandroid-sysvshm.so
cp build64/libandroid-sysvshm.so "$asset_dir/libandroid-sysvshm-glibc.so"
echo "Updated APK asset: $asset_dir/libandroid-sysvshm-glibc.so"