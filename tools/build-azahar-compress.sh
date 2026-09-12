#!/bin/bash
#
# Builds Azahar's compression CLI for Android, from official Azahar.
#
# Azahar has no standalone command-line tool, but it carries the ZCCI compression as an
# internal CLI library (src/citra_cli, upstream). This adds a small standalone entry point
# around it and builds only that target with the Android NDK. The wrapper is ours; the
# compression code and format are Azahar's, unchanged. Approach follows Mimir's build
# notes, done independently.
#
# Provenance of the shipped binary:
#   Azahar commit : b34de55b52ad363d3cea93307d3393e4aa2ee41c
#   NDK           : 27.3.13750724, Android CMake 4.1.2 (Azahar needs CMake >= 3.25)
#   ABI           : arm64-v8a, android-29, c++_static
#   Result        : ELF64 AArch64 PIE; deps are Android system libs only. Stripped ~25 MB.
#
# Azahar is GPL-2.0; see app/src/main/assets/licences/gpl-2.0-azahar.txt.
# Windows: use a SHORT base path (C:\az) with long paths on, as for Dolphin.
set -euo pipefail
: "${NDK:?set NDK}"; : "${CMAKE_BIN:?set CMAKE_BIN (Android cmake >= 3.25 bin dir)}"
export PATH="$CMAKE_BIN:$PATH"

if [ ! -d azahar ]; then
  git clone --depth 1 --recurse-submodules --shallow-submodules https://github.com/azahar-emu/azahar.git azahar
fi
cd azahar
git submodule update --init --recursive --force --depth 1

# 1. Our standalone entry point around the upstream CLI dispatcher.
cat > src/citra_cli/standalone_main.cpp <<'EOF'
// Standalone entry point for Azahar's compression CLI, written for Chameleon.
#include "citra_cli/citra_cli.h"
int main(int argc, char* argv[]) { return CitraCLI::ParseCommand(argc, argv); }
EOF

# 2. Our target, and build the CLI even with the Qt GUI off (it is gated behind ENABLE_QT).
grep -q azahar-compress src/citra_cli/CMakeLists.txt || cat >> src/citra_cli/CMakeLists.txt <<'EOF'
add_executable(azahar-compress standalone_main.cpp)
target_link_libraries(azahar-compress PRIVATE citra_cli citra_common citra_core log android vulkan EGL)
EOF
grep -q "NOT ENABLE_QT" src/CMakeLists.txt || sed -i '/add_subdirectory(citra_meta)/,/endif()/{/endif()/aif (NOT ENABLE_QT)    add_subdirectory(citra_cli)endif()
}' src/CMakeLists.txt

# PATCH CHECK: fail loudly if either edit did not take (upstream may have moved the lines).
grep -q "add_executable(azahar-compress" src/citra_cli/CMakeLists.txt || { echo "patch failed: azahar-compress target not added"; exit 1; }
grep -q "NOT ENABLE_QT" src/CMakeLists.txt || { echo "patch failed: citra_cli not enabled for Qt-off build"; exit 1; }

# 3. Configure (CLI only, GUI/audio off, Vulkan on for the core's renderer) and build.
cmake -S . -B build-android -G Ninja -DCMAKE_MAKE_PROGRAM="$CMAKE_BIN/ninja"   -DCMAKE_BUILD_TYPE=Release -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"   -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_static -DANDROID_ARM_NEON=TRUE   -DENABLE_QT=OFF -DENABLE_SDL2=OFF -DENABLE_TESTS=OFF -DENABLE_GDBSTUB=OFF -DENABLE_OPENAL=OFF   -DENABLE_CUBEB=OFF -DENABLE_LIBUSB=OFF -DENABLE_WEB_SERVICE=OFF -DENABLE_SCRIPTING=OFF   -DENABLE_VULKAN=ON -DENABLE_OPENGL=OFF -DENABLE_LTO=OFF -DCITRA_WARNINGS_AS_ERRORS=OFF
cmake --build build-android --target azahar-compress -j 6
"$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip" --strip-unneeded   build-android/bin/Release/azahar-compress -o build-android/bin/Release/libazahar.so
echo "Built: $(pwd)/build-android/bin/Release/libazahar.so"
echo "Copy to app/src/main/jniLibs/arm64-v8a/libazahar.so"
