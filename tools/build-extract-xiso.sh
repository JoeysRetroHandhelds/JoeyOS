#!/bin/bash
#
# Builds extract-xiso for Android arm64, from official XboxDev/extract-xiso.
#
# This is how app/src/main/jniLibs/arm64-v8a/libextractxiso.so was produced. extract-xiso is
# one C file; it is compiled as it is with the Android NDK, nothing added or changed.
# Its -r mode rewrites an Xbox disc image (a full Redump .iso, or an older xiso) as a trimmed
# xiso, which is what the Compress ROMs tool uses.
#
# Provenance of the shipped binary:
#   extract-xiso commit : 3f5b62cfe68f000b0e3c8a30104973f3a297948e (build-202609111233)
#   NDK                 : 27.3.13750724 (clang 18, target aarch64-linux-android29)
#   Result              : ELF64 AArch64 PIE, deps libc only.
#
# extract-xiso is BSD-licensed; see app/src/main/assets/licences/bsd-extract-xiso.txt.
#
# Usage: set NDK to your NDK path and run from an empty working directory.
set -euo pipefail

NDK="${NDK:-/c/Users/Joey/AppData/Local/Android/Sdk/ndk/27.3.13750724}"
BIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"

if [ ! -d extract-xiso ]; then
  git clone --depth 1 https://github.com/XboxDev/extract-xiso.git
  cd extract-xiso
  git fetch --depth 1 origin 3f5b62cfe68f000b0e3c8a30104973f3a297948e
  git checkout 3f5b62cfe68f000b0e3c8a30104973f3a297948e
else
  cd extract-xiso
fi

"$BIN/clang.exe" --target=aarch64-linux-android29 -O2 -fPIE -pie -D__LINUX__ -D_LARGEFILE64_SOURCE \
  -D_FILE_OFFSET_BITS=64 extract-xiso.c -o extract-xiso
"$BIN/llvm-strip.exe" --strip-unneeded extract-xiso -o libextractxiso.so
echo "Built: $(pwd)/libextractxiso.so"
echo "Copy to app/src/main/jniLibs/arm64-v8a/libextractxiso.so"
