#!/bin/bash
#
# Builds chdman for Android arm64, clean-room from official MAME.
#
# This is how app/src/main/jniLibs/arm64-v8a/libchdman.so was produced. It is not a fork
# of anyone's project: it carves chdman and only its dependencies out of the official
# mamedev/mame tree and cross-compiles them with the Android NDK. Re-run it to rebuild
# against a newer MAME, then drop the result back into jniLibs.
#
# Provenance of the shipped binary:
#   MAME commit : 5f4a88b1e1811e5e7794357389f68cc01e19ebd3 (2026-08-16)
#   NDK         : 27.3.13750724 (clang 18, target aarch64-linux-android21)
#   Result      : ELF64 AArch64 PIE, deps libc/libm/libdl only (libc++ static),
#                 subcommands include createcd and createdvd.
#
# chdman is GPL-2.0; see app/src/main/assets/licences/gpl-2.0-chdman.txt.
#
# Usage: set NDK to your NDK path and run from an empty working directory.
set -euo pipefail

NDK="${NDK:-/c/Users/Joey/AppData/Local/Android/Sdk/ndk/27.3.13750724}"
BIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"
CC="$BIN/clang.exe"; CXX="$BIN/clang++.exe"
T="--target=aarch64-linux-android21"

# 1. Sparse, shallow checkout of only the parts chdman needs.
if [ ! -d mame ]; then
  git clone --no-checkout --depth 1 --filter=blob:none https://github.com/mamedev/mame.git
  cd mame
  git sparse-checkout init --cone
  git sparse-checkout set src/tools src/lib/util src/osd \
    3rdparty/zlib 3rdparty/flac 3rdparty/lzma 3rdparty/utf8proc 3rdparty/zstd
  git checkout
else
  cd mame
fi

INC="-Isrc/lib/util -Isrc/lib -Isrc/osd -I3rdparty -I3rdparty/zlib -I3rdparty/lzma/C -I3rdparty/utf8proc -I3rdparty/zstd/lib -I3rdparty/flac/include -I3rdparty/flac/src/libFLAC/include"
DEF="-DLSB_FIRST -DCRLF=2 -DFLAC__NO_DLL -DNDEBUG -DZSTD_DISABLE_ASM"
FLACDEF="-DFLAC__NO_DLL -DFLAC__HAS_OGG=0 -DFLAC__NO_ASM -DHAVE_STDINT_H -DHAVE_INTTYPES_H -DHAVE_STDBOOL_H -DHAVE_LROUND -DPACKAGE_VERSION=\"1.4.3\" -DFLAC__ALIGN_MALLOC_DATA"

# Builds one ABI. $1 target triple, $2 extra cflags, $3 libFLAC intrinsics to drop, $4 out.
build_abi() {
  local T="--target=$1 $2" OBJ="build/obj-$1"; local drop="$3" out="$4"
  rm -rf "$OBJ"; mkdir -p "$OBJ"
  o(){ echo "$OBJ/$(echo "$1"|tr '/' '_').o"; }
  cc()  { "$CC"  $T -O2 -fPIC $DEF $INC -c "$1" -o "$(o "$1")"; }
  cxx() { "$CXX" $T -O2 -fPIC -std=c++20 $DEF $INC -c "$1" -o "$(o "$1")"; }
  flc() { "$CC"  $T -O2 -fPIC $FLACDEF -I3rdparty/flac/src/libFLAC/include -I3rdparty/flac/include -c "$1" -o "$(o "$1")"; }

  # Third-party codecs (C): zlib, utf8proc, lzma, zstd, and libFLAC (no ogg, no x86 asm).
  for f in 3rdparty/zlib/*.c 3rdparty/utf8proc/utf8proc.c 3rdparty/lzma/C/*.c \
           3rdparty/zstd/lib/common/*.c 3rdparty/zstd/lib/compress/*.c 3rdparty/zstd/lib/decompress/*.c; do cc "$f"; done
  for f in 3rdparty/flac/src/libFLAC/*.c; do
    case "$f" in *ogg_*|*_intrin_avx2*|*_intrin_sse*|*_intrin_ssse3*|*_intrin_fma*) continue;; esac
    case "$f" in $drop) continue;; esac
    flc "$f"
  done

  # MAME util (C++), minus the archive/xml/crypto pieces chdman does not use.
  for f in src/lib/util/*.cpp; do
    case "$f" in *unzip.cpp|*un7z.cpp|*aes256cbc.cpp|*nanosvg.cpp|*zippath.cpp|*xmlfile.cpp) continue;; esac
    cxx "$f"
  done

  # Minimal OSD core (POSIX file layer for Android), and the tool.
  cxx src/osd/osdcore.cpp; cxx src/osd/osdsync.cpp; cxx src/osd/strconv.cpp
  cxx src/osd/modules/file/posixfile.cpp; cxx src/osd/modules/file/posixdir.cpp
  cxx src/osd/modules/file/posixptty.cpp; cxx src/osd/modules/file/posixsocket.cpp
  cxx src/tools/chdman.cpp

  # A couple of symbols that live in files a headless tool does not build.
  cat > "$OBJ/stubs.cpp" <<'EOF'
#include <cstdlib>
extern const char build_version[];
const char build_version[] = "mame-chdman (Chameleon build)";
const char *osd_getenv(const char *name) { return std::getenv(name); }
EOF
  cxx "$OBJ/stubs.cpp"

  # Link a self-contained PIE, named as a library so Android extracts it to the
  # executable nativeLibraryDir.
  "$CXX" $T -pie -fPIE "$OBJ"/*.o -o "$out" -static-libstdc++ -lm -ldl
  echo "Built: $(pwd)/$out"
}

# 2. arm64 keeps the NEON intrinsics (baseline on aarch64); armv7 drops them and asks for
#    the NEON FPU explicitly. Both produce libchdman.so for their jniLibs ABI folder.
build_abi "aarch64-linux-android21" "" "" "libchdman-arm64.so"
build_abi "armv7a-linux-androideabi21" "-mfpu=neon -mfloat-abi=softfp" "*_intrin_neon*" "libchdman-armv7.so"
echo "Copy libchdman-arm64.so -> app/src/main/jniLibs/arm64-v8a/libchdman.so"
echo "Copy libchdman-armv7.so -> app/src/main/jniLibs/armeabi-v7a/libchdman.so"
