#!/bin/bash
#
# Builds dolphin-tool for Android, from official Dolphin.
#
# This is how app/src/main/jniLibs/arm64-v8a/libdolphintool.so was produced. Unlike
# chdman, dolphin-tool is not a small carve-out: it is wired to Dolphin's DiscIO, Common
# and uicommon, so this uses Dolphin's OWN CMake with the Android NDK toolchain and only
# the CLI-tool target, letting CMake resolve the graph while the GUI/Vulkan/audio options
# are turned off. The approach follows Mimir's dolphin-tool build notes, done independently
# against official Dolphin; nothing of theirs is copied.
#
# Provenance of the shipped binary:
#   Dolphin commit : fa61f77fedb1f804851414a231509c55b8d8ed6c
#   NDK            : 27.3.13750724, Android CMake 4.1.2 (Dolphin needs CMake >= 3.25)
#   ABI            : arm64-v8a, android-29, c++_static
#   Result         : ELF64 AArch64 PIE; deps are Android system libs only (libc, libm,
#                    libdl, liblog, libandroid, libEGL, libOpenSLES). Stripped ~6 MB.
#
# dolphin-tool is GPL-2.0; see app/src/main/assets/licences/gpl-2.0-dolphintool.txt.
#
# Notes for a clean build on Windows: use a SHORT base path (e.g. C:\dt) and enable long
# paths (git config --global core.longpaths true, plus the LongPathsEnabled registry key).
# Dolphin's deeply nested submodules overrun the 260-char limit at a long temp path.
set -euo pipefail

: "${NDK:?set NDK to your Android NDK path}"
: "${CMAKE_BIN:?set CMAKE_BIN to an Android cmake >= 3.25 bin dir (e.g. .../cmake/4.1.2/bin)}"
export PATH="$CMAKE_BIN:$PATH"

# 1. Clone official Dolphin with submodules (shallow).
if [ ! -d dolphin ]; then
  git clone --depth 1 --recurse-submodules --shallow-submodules \
    https://github.com/dolphin-emu/dolphin.git dolphin
fi
cd dolphin
git submodule update --init --recursive --force --depth 1

# 2. Two upstream include fixes needed to cross-compile current Dolphin for Android.
grep -q '#include "DiscIO/Blob.h"' Source/Core/DiscIO/VolumeWad.h || \
  sed -i '0,/#include/s//#include "DiscIO\/Blob.h"\n#include/' Source/Core/DiscIO/VolumeWad.h
grep -q '#include "Common/PcapFile.h"' Source/Core/Core/DSP/DSPCaptureLogger.h || \
  sed -i '0,/#include/s//#include "Common\/PcapFile.h"\n#include/' Source/Core/Core/DSP/DSPCaptureLogger.h

# 3. Configure with the Android NDK toolchain, CLI tool only, everything heavy off.
cmake -S . -B build-android -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$CMAKE_BIN/ninja" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_static \
  -DENABLE_CLI_TOOL=ON -DENABLE_NOGUI=OFF -DENABLE_QT=OFF \
  -DENABLE_TESTS=OFF -DENABLE_VULKAN=OFF -DENABLE_CUBEB=OFF -DENABLE_LLVM=OFF \
  -DUSE_DISCORD_PRESENCE=OFF -DUSE_MGBA=OFF -DUSE_RETRO_ACHIEVEMENTS=OFF \
  -DUSE_UPNP=OFF -DENABLE_ANALYTICS=OFF -DENABLE_AUTOUPDATE=OFF

# 4. Build only the tool, then strip and name it as a library so Android extracts it to
#    the executable nativeLibraryDir.
cmake --build build-android --target dolphin-tool -j 6
"$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip" --strip-unneeded \
  build-android/Binaries/dolphin-tool -o build-android/Binaries/libdolphintool.so
echo "Built: $(pwd)/build-android/Binaries/libdolphintool.so"
echo "Copy to app/src/main/jniLibs/arm64-v8a/libdolphintool.so"

# 32-bit (armeabi-v7a): not shipped. GameCube and Wii need a 64-bit device to emulate at
# all, so a 32-bit RVZ tool would run where its output can never be played. arm64 only.
