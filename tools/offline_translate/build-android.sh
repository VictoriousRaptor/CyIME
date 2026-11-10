#!/bin/sh
# Builds libcytranslate.so (offline translation: slimt + cytranslate.cc JNI) for arm64-v8a and x86_64.
# armeabi-v7a and x86 are left out: slimt's NEON path is aarch64-only and 32-bit phones are too slow
# for these models; there the translate plugin reports offline translation as unsupported.
#
# Needs a Linux Android NDK (r27 or newer), CMake 3.18+ and Ninja:
#   ANDROID_NDK=/path/to/android-ndk-r29 tools/offline_translate/build-android.sh
# Output: $WORK/out/<abi>/libcytranslate.so. Put the printed SHA-256 into
# app/build-logic/tasks-offline-translate.gradle.kts and upload the files as
# libcytranslate-<first 8 hex>-<abi>.so (GitHub Release `models`, ModelScope Cyletix/cyime-model).
# ABIS can select a subset (for example arm64-v8a); JOBS bounds compiler concurrency.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=${WORK:-$HERE/work}
: "${ANDROID_NDK:?set ANDROID_NDK to the NDK directory}"
# DavidVentura/slimt (GPL-2.0; fork of jerinphilip/slimt with the Firefox base/base-memory and
# split-vocabulary models), the commit DavidVentura/slimt-sys pins on 2026-10-22.
COMMIT=fdc0f98706b193bf17cb419f2fe08bf1d9913607
mkdir -p "$WORK"
if [ ! -d "$WORK/slimt" ]; then
  git clone https://github.com/DavidVentura/slimt.git "$WORK/slimt"
fi
git -C "$WORK/slimt" checkout -q $COMMIT
git -C "$WORK/slimt" submodule update --init --recursive
for abi in ${ABIS:-arm64-v8a x86_64}; do
  cmake -S "$HERE" -B "$WORK/build-$abi" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=$abi -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_static \
    -DSLIMT_SOURCE_DIR="$WORK/slimt"
  # One compile job at a time keeps memory low (AGENTS.md: heavy builds run alone).
  cmake --build "$WORK/build-$abi" --target cytranslate -j "${JOBS:-2}"
  mkdir -p "$WORK/out/$abi"
  cp "$WORK/build-$abi/libcytranslate.so" "$WORK/out/$abi/"
done
# libc++ is linked in statically (the app's libc++_shared may be another NDK version): every undefined
# symbol must come from the system libraries the .so names.
for lib in "$WORK"/out/*/libcytranslate.so; do
  if "$ANDROID_NDK"/toolchains/llvm/prebuilt/*/bin/llvm-nm -D --undefined-only "$lib" | grep -v -e '@LIBC' -e '@LIBDL' -e '@LIBLOG' -e ' __android_log_' ; then
    echo "unresolved symbols in $lib" >&2; exit 1
  fi
done
ls -l "$WORK"/out/*/libcytranslate.so
sha256sum "$WORK"/out/*/libcytranslate.so
