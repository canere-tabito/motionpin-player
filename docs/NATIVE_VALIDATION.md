# Native RIFE validation

The Android library performs real RIFE v4.6 inference through ncnn Vulkan. The
bundled graph has inputs `in0`, `in1`, `in2` (full spatial timestep map), output
`out0`, and the upstream `rife.Warp` layer. It is not a frame blend or a CLI
subprocess. `NativeRife` exposes synchronous direct RGB8 buffers; `VideoPipeline`
calls it for intermediate source PTS and encodes the resulting frames.

The input contract is known SDR BT.709 RGB8. FP32 network computation does not
make this an HDR/10-bit signal path. There is no full-frame resize, UHD downscale,
automatic lower precision, or CPU fallback in the Android API. Each image is
zero-padded at its right/bottom to a multiple of 32 and cropped using the padded
row stride. The model's internal learned flow pyramid remains part of the exact
upstream graph. Its synthesized frames and subsequent compression can introduce
visible artifacts; source-resolution output is not a losslessness guarantee.

## Recorded checks (2026-10-16 UTC)

| Check | Result | Evidence / scope |
|---|---|---|
| Exact model provenance and hashes | PASS | `MODEL_MANIFEST.json`; both files are committed under the pinned official wrapper repository's MIT license; no external checkpoint was substituted |
| Clean native setup | PASS | `scripts/setup-native.sh` run in an empty temporary project tree: archive/file checksums, safety patches, assets and source identities verified |
| CPU real model inference | PASS | Synthetic original geometric frames, 65×49, t=.25 and .5; 6,908 output channel values differ by >3 from simple blend at midpoint; t=.25 and .5 differ in 6,685 channel values |
| Endpoint and invalid-input handling | PASS | t=0/1 byte-exact originals; NaN timestep rejected before inference |
| Vulkan shader inference on host | PASS | Mesa llvmpipe software Vulkan, LLVM 19.1.7; same exact model, same odd dimensions, FP32 |
| CPU/software Vulkan consistency | PASS | Midpoint byte-identical; t=.25 differs in 3 of 9,555 RGB channel values, maximum absolute difference 1 |
| No default host Vulkan driver | PASS error handling | Without the explicit test ICD, `vkCreateInstance` fails -9 and a native error is returned; no fallback inference is claimed |
| Android arm64 native release compilation | PASS | `:app:externalNativeBuildRelease`, NDK 28.1.13356709, CMake 3.22.1; final APK verification recorded separately |
| Android JNI/model load and physical GPU inference | NOT_RUN | No Android device is attached |
| 1080p / 4K video AI, visual quality, memory/thermal, cancellation latency | NOT_RUN | Requires eligible Android hardware and representative motion footage |
| HDR/10-bit AI | UNSUPPORTED | The RGB8 model boundary cannot retain these signals; quality gate must reject before conversion |
| 16KB device load | NOT_RUN | ELF/APK structural checks are separate; a compatible device/emulator is required |

Tiny host timings (roughly 0.1 s CPU, 7.4 s initial software Vulkan in this
session) include startup and do not predict mobile GPU or 4K performance.
Generated test pictures are owned by this project and contain only geometric
patterns. They are written to ignored `artifacts/native-host/`; no private video
or upstream example photographs are distributed.

## Reproduction

Run `scripts/setup-native.sh`, then `scripts/test-native-host.sh`. The latter
uses the pinned SDK CMake/NDK headers if system Vulkan headers are unavailable.
Override `ANDROID_HOME`, `MOTIONPIN_CMAKE`, `MOTIONPIN_NINJA`, or
`MOTIONPIN_HOST_BUILD_DIR` as needed. Host tests require a native C++ compiler and
Vulkan loader; the CPU test does not require a Vulkan device.

On a compatible Debian Linux x86_64 host with LLVM 19 libraries, optional
`scripts/test-native-vulkan-software.sh` downloads and hash-verifies the exact
Debian Mesa 25.0.7 package, extracts it into the ignored artifacts directory,
and selects its llvmpipe ICD for that test process. It does not install a system
package, alter the app, or turn this test into hardware validation. The script
runs the same inference and checks the CPU/Vulkan output difference.

The test-only package is
`https://deb.debian.org/debian/pool/main/m/mesa/mesa-vulkan-drivers_25.0.7-2+deb13u1_amd64.deb`,
SHA-256 `caf2f7d296b7522efe74e40a74bce762a3ce84c33b53b5e54e1248ac2e0a13a5`.
It is not included in the APK. Quarter-frame mean absolute RGB8 error was
0.0003139717425431711 across all 9,555 channel values; midpoint error was zero.

Re-run `scripts/update-native-manifest.py` after reviewing changes to native
sources. It updates the full engine digest used in cache identity and both
manifest copies. `scripts/setup-native.sh` verifies that this identity is current.

## Native lifetime and failure behavior

Three host RGB buffers are allocated in native memory and owned by one engine.
The pipeline accesses them only within `NativeRife.use`; close frees these buffers,
the network and its per-engine allocators after the synchronous inference
boundary. Java `ByteBuffer` aliases must never escape that scope. The Vulkan
instance is process-wide and remains until process exit; it does not retain the
closed engine's weights or frame buffers.

Load errors, unsupported Vulkan, checked allocation failures, shader compilation
errors, submit/device errors, invalid shapes and non-finite outputs become
`NativeRifeException`. Upstream ncnn submit/upload and Warp shader errors that
were previously ignored are patched to propagate with locked before/after source
hashes. OS low-memory process termination and a driver stuck inside a synchronous
submission cannot be made catchable by these wrappers; bounded cancellation
latency requires device measurement. No quality reduction is attempted on failure.
