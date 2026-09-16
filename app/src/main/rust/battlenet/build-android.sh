#!/usr/bin/env bash
set -euo pipefail
crate_dir="$(cd "$(dirname "$0")" && pwd)"
repo_dir="$(cd "$crate_dir/../../../../.." && pwd)"
sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
ndk_dir="${ANDROID_NDK_HOME:-$sdk_dir/ndk/27.3.13750724}"
compiler_dir="$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [[ ! -x "$compiler_dir/aarch64-linux-android26-clang" ]]; then
    echo "Set ANDROID_HOME or ANDROID_NDK_HOME to NDK 27.3.13750724." >&2
    exit 1
fi
cargo_bin="${CARGO:-$(command -v cargo)}"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$compiler_dir/aarch64-linux-android26-clang"
export CC_aarch64_linux_android="$compiler_dir/aarch64-linux-android26-clang"
export AR_aarch64_linux_android="$compiler_dir/llvm-ar"
export CARGO_TARGET_DIR="$repo_dir/app/build/rust/battlenet"
export RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384"
"$cargo_bin" build --manifest-path "$crate_dir/Cargo.toml" --locked --release --lib --target aarch64-linux-android
output_dir="$repo_dir/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$output_dir"
cp "$CARGO_TARGET_DIR/aarch64-linux-android/release/libwn_battlenet.so" "$output_dir/"
