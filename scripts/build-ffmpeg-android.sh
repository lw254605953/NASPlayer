#!/usr/bin/env bash
# Rebuild the LGPL-only FFmpeg audio extension. No user media or credentials are used.
set -euo pipefail

if [[ "${1:-}" == --help ]]; then
  echo 'Usage: bash scripts/build-ffmpeg-android.sh'
  echo 'Requires ANDROID_NDK_HOME (NDK r28c), CMake, Ninja, Make, curl, tar/xz.'
  echo 'Optional: ANDROID_HOME, NASPLAYER_NATIVE_BUILD_DIR (absolute), NASPLAYER_NATIVE_JOBS.'
  exit 0
fi
if [[ $# != 0 ]]; then echo 'Unexpected arguments; use --help.' >&2; exit 2; fi

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
module_dir="$project_dir/decoder-ffmpeg"
build_dir="${NASPLAYER_NATIVE_BUILD_DIR:-$project_dir/.native-build}"
sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
ndk_dir="${ANDROID_NDK_HOME:-${sdk_dir:+$sdk_dir/ndk/28.2.13676358}}"
jobs="${NASPLAYER_NATIVE_JOBS:-4}"
case "$build_dir" in /*) ;; *) echo 'NASPLAYER_NATIVE_BUILD_DIR must be absolute.' >&2; exit 2 ;; esac
case "$jobs" in ''|*[!0-9]*|0) echo 'NASPLAYER_NATIVE_JOBS must be positive.' >&2; exit 2 ;; esac
[[ -n "$ndk_dir" && -f "$ndk_dir/source.properties" ]] || { echo 'Set ANDROID_NDK_HOME to Android NDK 28.2.13676358.' >&2; exit 2; }
for program in cmake ninja make curl tar; do
  command -v "$program" >/dev/null || { echo "Missing build tool: $program" >&2; exit 2; }
done

case "$(uname -s)-$(uname -m)" in
  Darwin-arm64|Darwin-x86_64) host=darwin-x86_64
    [[ ! -d "$ndk_dir/toolchains/llvm/prebuilt/darwin-arm64" ]] || host=darwin-arm64 ;;
  Linux-x86_64) host=linux-x86_64 ;;
  *) echo 'Native build supports Linux x86_64 and macOS.' >&2; exit 2 ;;
esac
toolchain="$ndk_dir/toolchains/llvm/prebuilt/$host"
[[ -x "$toolchain/bin/clang" ]] || { echo "Missing NDK toolchain: $toolchain" >&2; exit 2; }

version=6.1.4
archive="$build_dir/downloads/ffmpeg-$version.tar.xz"
expected_sha=a231e3d5742c44b1cdaebfb98ad7b6200d12763e0b6db9e1e2c5891f2c083a18
mkdir -p "$build_dir/downloads" "$build_dir/source"
if [[ ! -f "$archive" ]]; then
  curl --fail --location --proto '=https' --retry 2 "https://ffmpeg.org/releases/ffmpeg-$version.tar.xz" -o "$archive.part"
  mv "$archive.part" "$archive"
fi
if command -v sha256sum >/dev/null; then
  actual_sha="$(sha256sum "$archive" | awk '{print $1}')"
else
  actual_sha="$(shasum -a 256 "$archive" | awk '{print $1}')"
fi
[[ "$actual_sha" == "$expected_sha" ]] || { echo 'FFmpeg archive checksum mismatch; refusing to build.' >&2; exit 1; }
ffmpeg_dir="$build_dir/source/ffmpeg-$version"
if [[ ! -f "$ffmpeg_dir/configure" ]]; then tar -xf "$archive" -C "$build_dir/source"; fi

decoders=(aac ac3 eac3 dca truehd mlp flac opus vorbis mp3 ape)
common=(
  --target-os=android --enable-cross-compile --enable-static --disable-shared
  --disable-doc --disable-programs --disable-everything --disable-avdevice
  --disable-avformat --disable-swscale --disable-postproc --disable-avfilter
  --disable-symver --enable-swresample --disable-v4l2-m2m --disable-vulkan
  --disable-network --disable-autodetect --disable-iconv --disable-zlib
  --disable-gpl --disable-nonfree --extra-ldexeflags=-pie
)
for decoder in "${decoders[@]}"; do common+=("--enable-decoder=$decoder"); done

for abi in arm64-v8a x86_64; do
  case "$abi" in
    arm64-v8a) arch=aarch64; cpu=armv8-a; prefix=aarch64-linux-android ;;
    x86_64) arch=x86_64; cpu=x86-64; prefix=x86_64-linux-android ;;
  esac
  options=("${common[@]}")
  if [[ "$abi" == x86_64 ]]; then options+=(--disable-asm); fi
  install_dir="$build_dir/install/$abi"
  echo "Building FFmpeg $version for $abi"
  (
    cd "$ffmpeg_dir"
    make distclean >/dev/null 2>&1 || true
    ./configure "${options[@]}" --prefix="$install_dir" --libdir="$install_dir/lib" \
      --arch="$arch" --cpu="$cpu" --cross-prefix="$toolchain/bin/${prefix}33-" \
      --cc="$toolchain/bin/${prefix}33-clang" --cxx="$toolchain/bin/${prefix}33-clang++" \
      --nm="$toolchain/bin/llvm-nm" --ar="$toolchain/bin/llvm-ar" \
      --ranlib="$toolchain/bin/llvm-ranlib" --strip="$toolchain/bin/llvm-strip" --extra-cflags=-fPIC
    make -j"$jobs"
    make install-libs
  )
  cmake -S "$module_dir/src/main/jni" -B "$build_dir/cmake-$abi" -G Ninja \
    -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-33 -DANDROID_NDK="$ndk_dir" \
    -DCMAKE_TOOLCHAIN_FILE="$ndk_dir/build/cmake/android.toolchain.cmake" \
    -DFFMPEG_SOURCE_DIR="$ffmpeg_dir" -DFFMPEG_BINARY_DIR="$install_dir/lib" \
    -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="$module_dir/src/main/jniLibs/$abi"
  cmake --build "$build_dir/cmake-$abi" --target ffmpegJNI
  "$toolchain/bin/llvm-strip" --strip-unneeded "$module_dir/src/main/jniLibs/$abi/libffmpegJNI.so"
done
echo 'FFmpeg audio libraries ready. Run ./gradlew :app:assembleDebug.'
