# Independent detector verification — 2026-10-16

This run loaded the real downloaded model bytes on Linux x86_64 CPU. It is a
model-format and inference smoke test, **not an Android runtime, dance tracking,
HDR, thermal, or 4K60 playback result**.

| Check | Lite0 | Lite2 |
|---|---|---|
| Exact size | 13,836,895 bytes PASS | 23,096,891 bytes PASS |
| SHA-256 | `40338edf5ec70d43e318b0a716a84d4564cd1802759a7a07170c7e43796dbf58` PASS | `ad2abbf2b4e10585e15176fd7b5ef03c28dda959ae26fc142549fdd1814db91d` PASS |
| Float32 input | `[1,320,320,3]` | `[1,448,448,3]` |
| Raw score / box outputs | `[1,19206,90]` / `[1,19206,4]` | `[1,37629,90]` / `[1,37629,4]` |
| Metadata normalization | mean/std 127.5/127.5 | mean/std 127.5/127.5 |
| Labels | 90; first label `person` PASS | 90; first label `person` PASS |
| MediaPipe 1.1.0 load and blank inference | PASS, zero detections | PASS, zero detections |
| Person fixture inference | PASS, primary score 0.8083 | PASS, primary score 0.8761 |

The float32 models have **two raw heads**, not four postprocessed SSD outputs.
MediaPipe 1.1.0 successfully applies the metadata-aware decoder; replacing it with
a generic four-output SSD reader would be incompatible. The host inspection used
Python `mediapipe==1.1.0`, `ai-edge-litert==2.1.0`, and XNNPACK CPU inference.

At the application's HIGH threshold 0.22, Lite2 also produces a second detection
with score 0.2586 on the astronaut's lower-right equipment/body region. At 0.30
that detection disappears. This is direct evidence of the recall/false-positive
tradeoff; a successful model load does not prove detection precision. No threshold
accuracy claim is made from one photograph. The intended person's score is not a
tracking-identity confidence measure.

The fixture is NASA's public-domain Eileen Collins astronaut photograph, from
[scikit-image v0.20.0](https://raw.githubusercontent.com/scikit-image/scikit-image/v0.20.0/skimage/data/astronaut.png).
Its provenance is documented by
[scikit-image's astronaut data API](https://scikit-image.org/docs/stable/api/skimage.data.html#skimage.data.astronaut).
SHA-256: `88431cd9653ccd539741b555fb0a46b61558b301d4110412b5bc28b5e3ea6cb5`.
The fixture and downloads are kept only in ignored local validation artifacts.

Reproduce after preparing the pinned model assets and Python dependencies:

```sh
bash scripts/setup-tracker-model.sh
python scripts/verify-tracker-host.py \
  --person-image /path/to/astronaut.png \
  --output artifacts/validation/model-host.json
```

The script performs no network access. The optional fixture may be omitted for
hash/metadata/blank inference checks. A missing Linux GLES runtime must be installed
or supplied via the loader's library path even for CPU MediaPipe inference.
Measured host blank-image timings are emitted in JSON solely for reproducibility;
they must not be used to predict Android latency or playback contention.

## Android dependency inspection

The actual Google Maven 1.1.0 artifacts were downloaded and inspected, rather than
assuming behavior from a different MediaPipe release:

- `tasks-core-1.1.0.aar` SHA-256:
  `8fd870589d83d4ec9df50043e6ab053d88442cb90b6410c990c93538b5a07a2c`.
- `tasks-vision-1.1.0.aar` SHA-256:
  `f4e50c114c14efe8762523ba2f9e6610b4da186bb4ee227bf49ca3e801caf6cc`.
- Both manifests require API 24; MotionPin's minimum API 29 satisfies this.
- `tasks-core` includes `arm64-v8a/libmediapipe_tasks_jni.so`. Its three ELF LOAD
  segments have alignment `0x4000` (16 KB). This checks ELF layout only; APK ZIP
  alignment and real 16 KB device loading remain NOT_RUN.
- `javap` confirms the ObjectDetector builder methods used by the app, including
  IMAGE running mode, score threshold, category allowlist and maximum results.
- `CpuOptions.Builder` exposes no thread-count setter. Do not claim a two-thread
  cap or alter undocumented protobuf/native options to force one.
- `BitmapImageContainer.close()` calls `Bitmap.recycle()` on the exact supplied
  Bitmap. `MPImage.close()` closes that container when its reference count reaches
  zero. Sampling appearance or rendering a preview after closing that MPImage
  would use a recycled bitmap. The app keeps inference input ownership explicit.

Android instrumentation model loading, offline APK asset loading, R8 behavior,
native-library coexistence and actual playback performance remain device gates.

## Confirmed transitive network permission and correction

The first full debug/release manifest merges contained `android.permission.INTERNET`,
added by MediaPipe's `com.google.android.datatransport:transport-backend-cct:3.1.0`.
The source manifest alone therefore did not enforce the promised offline boundary.
The application now explicitly removes INTERNET during manifest merging and removes
DataTransport's backend-discovery service, job service and alarm receiver. Required
library classes remain available so MediaPipe's logger construction is not broken
by class exclusions.

Inspection of the pinned DataTransport bytecode confirms that an unregistered
backend returns before persisting a new event or scheduling its upload. The pinned
MediaPipe Java API offers no public logger-disable or CPU-thread-count option.
`ACCESS_NETWORK_STATE` remains because Media3-common/exoplayer also contribute and
use it; it does not grant socket/network transmission access. The build now checks
the merged manifests via `verify*OfflineManifest`, including transitive components.
This gate failed on the original merged manifest as expected.

The operating-system permission boundary blocks Internet access, but Android
runtime verification of detector creation/close, logging behavior on an upgraded
installation, and absence of background work still requires a device. No runtime
telemetry data or media was transmitted during this host-only validation.
