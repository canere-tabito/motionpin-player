#!/usr/bin/env bash
# Real model smoke test; CPU host output is not Android/Vulkan qualification.
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
sdk="${ANDROID_HOME:-${MOTIONPIN_TOOLCHAINS_DIR:-/workspace/toolchains}/android-sdk}"
cmake_bin="${MOTIONPIN_CMAKE:-${sdk}/cmake/3.22.1/bin/cmake}"
ninja_bin="${MOTIONPIN_NINJA:-${sdk}/cmake/3.22.1/bin/ninja}"
host_build="${MOTIONPIN_HOST_BUILD_DIR:-${root}/artifacts/native-host/build}"
evidence="${root}/artifacts/native-host"
mkdir -p "$host_build" "$evidence"
cd "$root"
python3 scripts/update-native-manifest.py --check
vulkan_args=()
if [[ ! -f /usr/include/vulkan/vulkan.h ]]; then
    headers="${sdk}/ndk/28.1.13356709/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include"
    mkdir -p "$host_build/vulkan-headers"
    cp -a "$headers/vulkan" "$headers/vk_video" "$host_build/vulkan-headers/"
    vulkan_args+=("-DVulkan_INCLUDE_DIR=${host_build}/vulkan-headers")
fi
if [[ -f /usr/lib/x86_64-linux-gnu/libvulkan.so.1 ]]; then
    vulkan_args+=("-DVulkan_LIBRARY=/usr/lib/x86_64-linux-gnu/libvulkan.so.1")
fi
"$cmake_bin" -S app/src/main/cpp -B "$host_build" -G Ninja \
    "-DCMAKE_MAKE_PROGRAM=$ninja_bin" -DCMAKE_BUILD_TYPE=Release "${vulkan_args[@]}" > "$evidence/configure.log" 2>&1
"$cmake_bin" --build "$host_build" -j 3 > "$evidence/build.log" 2>&1
"$host_build/rife_host_test" app/src/main/assets/models/rife-v4.6 "$evidence/rife" | tee "$evidence/result.txt"
if [[ "${MOTIONPIN_TEST_VULKAN:-0}" == 1 ]]; then
    "$host_build/rife_host_test" app/src/main/assets/models/rife-v4.6 "$evidence/rife-vulkan" vulkan | tee "$evidence/vulkan-result.txt"
fi
