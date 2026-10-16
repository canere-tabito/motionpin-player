# MotionPin — dancer tracking v2, offline model provenance

## Pinned detectors

User-selectable detectors run with MediaPipe Tasks Vision Android 1.1.0 (Google Maven):

| Mode | Detector | Model input | Size | SHA-256 |
|---|---|---|---|---|
| 高精度 (default) | EfficientDet-Lite2 float32 | 448 × 448 | 23,096,891 bytes | ad2abbf2b4e10585e15176fd7b5ef03c28dda959ae26fc142549fdd1814db91d |
| 軽快 | EfficientDet-Lite0 float32 | 320 × 320 | 13,836,895 bytes | 40338edf5ec70d43e318b0a716a84d4564cd1802759a7a07170c7e43796dbf58 |

Official Google distribution:

- https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite2/float32/1/efficientdet_lite2.tflite
- https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float32/1/efficientdet_lite0.tflite
- Independent model-byte comparison: https://github.com/hugocornellier/object_detection
- Android MediaPipe API: https://developers.google.com/edge/mediapipe/solutions/vision/object_detector/android
- Google confirmed new 1.1.0 tasks-core/vision protobuf-lite fixes October 14, 2026: https://github.com/google-ai-edge/mediapipe/issues/6364

The model and MediaPipe Tasks are distributed under Apache-2.0 with notices retained. Local-build script scripts/setup-tracker-model.sh verifies hashes and bytes for BOTH files and refuses mismatched existing assets. scripts/verify-tracker-apk.py verifies both uncompressed model assets inside the final APK. No GitHub Actions. No runtime network permission or model download.

## Architecture

1. Pause playback; explicitly select a visible person from a real detection preview or numbered accessible targets. User can seek preview ±1 second, rotate the reference preview 90 degrees and select high accuracy / balanced.
2. Run on a max-640px-side **analysis bitmap**, never on the original video/AI cache. Original 4K60/HDR/bit depth/color and audio playback remain handled independently by existing Media3 SurfaceView without reencoding.
3. A stateful person matcher gates on predicted velocity, expected size, person confidence, torso appearance compared to both the original selection and recent detections, and a minimum best-vs-second-best margin. Ambiguous detections are not published, and prolonged ambiguity stops rather than silently jumping to another dancer. This is short-range visual matching, not biometric identification or reliable long-occlusion reidentification.
4. Short six-second ahead-of-playback analysis windows publish validated anchors every two samples, up to 2,400 retained. High uses 4 detector samples per second and Balanced uses 2. The system can defer more analysis if playback indicates a sustained dropped-frame problem.
5. Trajectory interpolation bridges confirmed adjacent frames only (gap <= 900ms) and extrapolates no more than 250ms. The follow camera damps abrupt jitter in source-time coordinates and resets at large seek discontinuities.
6. Framing modes FULL, UPPER and LOWER select an area within the chosen dancer's bounding box; a manual pan suspends the follow crop until resumed. Target selection and frame samples stay private and in memory only.
7. App background, video change or tracking cancellation discard analysis resources. Tracking failure must be visible and offer immediate re-selection.

## Limits and mandatory gates

- MediaMetadataRetriever seeks to a nearby frame, not a guaranteed exact frame PTS. Rotation of retrieved bitmaps can depend on device/OEM; manual preview correction is not a guarantee of 180-degree correctness.
- Crowd overlaps, dramatic lighting, same-color uniforms and occlusion can cause a safe tracking stop. No silent identity changes or guaranteed uninterrupted follow.
- An analysis bitmap is RGB8; this does not convert the original HDR video. A cropped view contains fewer effective source pixels and must not be described as 4K image enhancement.
- Verify locally: Gradle/MediaPipe dependency resolution; all Kotlin tests; lint; both model APK hashes/noCompress; Android offline model load; frames dropped/thermal/decoder load during 4K60; full-size source untouched; 4 orientation/rotation cases; 2/4/8 dancers with collisions; missed/switch rate and frame/second; memory and foreground/background.
- These paths are CODE PRESENT only until an actual fresh Android build and device study succeed. A prior v0.1.0 test does not validate the newer v0.2.0 code.
