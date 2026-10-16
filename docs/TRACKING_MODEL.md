# MotionPin dancer tracking — bundled model provenance

- Detector: Google MediaPipe EfficientDet-Lite0 float32, 320×320, 80 COCO classes (person).
- Official immutable model URL: https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float32/1/efficientdet_lite0.tflite
- SHA-256: `40338edf5ec70d43e318b0a716a84d4564cd1802759a7a07170c7e43796dbf58` (13,836,895 bytes, verified against a public independent reproduction; verify build-time bytes locally).
- Model distribution: Apache-2.0; preserve attribution and license notices. This is not the RIFE model and does not affect its provenance.
- Runtime library: `com.google.mediapipe:tasks-vision:1.1.0` (version fixed).
- `scripts/setup-tracker-model.sh` downloads the exact asset **only during local build**, checks hash and fails closed. GitHub Actions are not used.
- The installed app has no `INTERNET` permission and never downloads a model at runtime.
- Usage: detect human bounding boxes in *downscaled sampled analysis frames*; original H.264/HEVC/HDR media goes to the existing ExoPlayer unchanged. A rendered crop is a viewport transform, not lossless enhancement or new video.
- Limits: persons overlapping or leaving the field of view can interrupt tracking; no reliable reidentification across occlusions is claimed. Tracking samples may compete with video decode and require device performance validation. Do not silently switch identities.
- Sources: [Google Object Detector](https://developers.google.com/edge/mediapipe/solutions/vision/object_detector), [Android API](https://developers.google.com/edge/mediapipe/solutions/vision/object_detector/android), [Google EfficientDet dataset/model info](https://www.kaggle.com/models/tensorflow/efficientdet).
