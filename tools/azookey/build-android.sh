#!/bin/sh
# Builds libCyAzooKey.so (azooKey converter + the C API in CyAzooKey/) for arm64-v8a, armeabi-v7a and
# x86_64; the Swift Android SDK has no 32-bit x86 target. Needs Swift 6.2.0 exactly (the SDK modules are
# built with it) and the swift-6.2-RELEASE-android-24-0.1 SDK (swift sdk install).
# Output: $WORK/out/<abi>/libCyAzooKey.so. Put the printed SHA-256 into app/build-logic/tasks-japanese.gradle.kts
# and upload the files as libCyAzooKey-<first 8 hex>-<abi>.so.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=${WORK:-$HERE/work}
COMMIT=25a6346bf76931c1cf2214015b808875ea82f4bf
mkdir -p "$WORK"
if [ ! -d "$WORK/akkc-lite" ]; then
  git clone --recurse-submodules https://github.com/azooKey/AzooKeyKanaKanjiConverter "$WORK/akkc-lite"
  git -C "$WORK/akkc-lite" checkout --recurse-submodules $COMMIT
  # Foundation/ICU replaced by FoundationEssentials + a small shim; Zenzai left out (see README.md).
  (cd "$WORK/akkc-lite" && patch -p1 < "$HERE/azookey-lite.patch")
fi
rm -rf "$WORK/CyAzooKey" && cp -r "$HERE/CyAzooKey" "$WORK/CyAzooKey"
cd "$WORK/CyAzooKey"
SDK=$HOME/.swiftpm/swift-sdks/swift-6.2-RELEASE-android-24-0.1.artifactbundle/swift-6.2-release-android-24-sdk/android-27d-sysroot/usr/lib
for pair in aarch64-unknown-linux-android28:aarch64:arm64-v8a armv7-unknown-linux-androideabi28:armv7:armeabi-v7a x86_64-unknown-linux-android28:x86_64:x86_64; do
  triple=${pair%%:*}; rest=${pair#*:}; arch=${rest%%:*}; abi=${rest#*:}
  case " ${ABIS:-arm64-v8a armeabi-v7a x86_64} " in *" $abi "*) ;; *) continue ;; esac
  # SwiftPM does not apply --static-swift-stdlib to the library link, so the Swift runtime's own
  # dependencies are named here: liblog, and libc++ linked in statically (the app's libc++_shared may be
  # another NDK version). Without them Android's dlopen fails on the unresolved std::__ndk1 symbols.
  swift build -c release --jobs "${JOBS:-2}" --scratch-path "$WORK/build-$abi" --swift-sdk $triple --static-swift-stdlib \
    -Xlinker -L$SDK/swift_static-$arch/android -Xlinker --gc-sections -Xlinker -s \
    -Xlinker -llog -Xlinker -l:libc++_static.a -Xlinker -l:libc++abi.a -Xlinker --exclude-libs -Xlinker ALL
  mkdir -p "$WORK/out/$abi"
  cp "$WORK/build-$abi/$triple/release/libCyAzooKey.so" "$WORK/out/$abi/"
done
# Every undefined symbol must come from a library the .so names (libc, libm, libdl, liblog).
for lib in "$WORK"/out/*/libCyAzooKey.so; do
  if nm -D --undefined-only "$lib" | grep -v -e '@LIBC' -e ' __android_log_print$'; then
    echo "unresolved symbols in $lib" >&2; exit 1
  fi
done
sha256sum "$WORK"/out/*/libCyAzooKey.so
