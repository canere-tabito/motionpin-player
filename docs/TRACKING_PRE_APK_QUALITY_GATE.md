# MotionPin — dancer auto-follow quality gates (before any new APK)

Status date: 2026-10-16. Target source: 0.2.0-alpha1. Do not call CODE PRESENT device-validated.
Scope: user-selected multi-person dance tracking. All processing offline. Source video and RIFE cache remain unmodified.

## Eight layers already implemented in source

1. **Detection fidelity:** user chooses high-resolution EfficientDet-Lite2 float32 (448px) by default, sampled every 250ms, or manually chooses Lite0 (320px) sampled every 500ms. Both official weights have fixed SHA-256, uncompressed APK validation and Apache-2.0 notices. Up to 24 detections; strict manual target selection.
2. **Identity safety:** person appearance from torso patches, selected-template preservation, recent observation, velocity prediction, size gate and competing-candidate margin. Ambiguous observations are not promoted. Persistent lost/ambiguous status stops rather than picking another similarly dressed person.
3. **Temporal fidelity:** detected keyframes follow original source microsecond clock. Six-second bounded lookahead publishes confirmed anchors in groups of two; 2,400-anchor retention cap. Never interpolate >900ms of missing detection or extrapolate >250ms beyond a confirmed anchor; reset crop filter after major seek.
4. **Stable camera:** normalized crop center with deadzone and time-based easing, slower zoom damping. FULL framing keeps the entire detectable bounding box where geometry permits; UPPER and LOWER are intentional detail views. Manual pan freezes follow and can resume; exiting restores the pre-tracking user viewport.
5. **4K / thermal protection:** analyze <=640px reference bitmaps only; original SurfaceView media is not reencoded or resized. If actual rendered/dropped counters show excess drops, stop further analysis and prefer video decoding. Severe thermal status or battery saver pauses inference without automatically downgrading video or switching model.
6. **Multi-person UX:** annotated selection screenshot, accessible numbered targets and confidence values, ±1-second source preview scrubbing, 90° manual preview correction, quality-mode chips, visible follow state, matching count, FULL/UPPER/LOWER crop choices, reselect/stop/hold controls.
7. **Privacy and failure integrity:** no app INTERNET permission, no runtime model fetch, no biometrics/face embedding or exported tracking coordinates. On video switch, app background, explicit stop or processing failure, tracking stops and stale work is invalidated via generation token.
8. **Source-level checks:** added JVM tests for camera motion, crowded same-cost candidates, outfit mismatch, occlusion/loss, no post-loss reassignment, invalid boxes, seek/occlusion-safe interpolation, smooth/framing zoom and full-body safe zoom. Added Android instrumentation proving offline model hashes, asset mapping, native creation and a tiny inference call once a device is connected.

## Executed verification, NOT Android acceptance

- A source-logic mirror of TrackingMath.kt and a VideoViewport test stub were compiled by local Kotlin/JVM 1.9.0; 18 deterministic assertions passed after correcting the full-body safety-zoom expectation and adding short-occlusion handling.
- The new Gradle unit suite, Android 1.1.0 dependency resolution, APK model assets, native MediaPipe inference and physical-device quality measurements are **NOT_RUN**. This is not a source build PASS or release signing certification.
- The model hashes were cross-referenced with a public independent byte comparison. The actual model downloads are performed and verified only when a local build is undertaken.

## Pre-release acceptance protocol (run before shipping, not optional assertions)

| Study | Samples | Required outcome |
|---|---|---|
| Identity under crowded choreography | 2, 4, 8+ adults, equal/similar costumes, crossings, stage light changes, fast rotation and temporary occlusion | Record target visibility, confirmed samples, false-person switches, safe lost/reselect count; zero silent identity substitutions in controlled test scenarios |
| Motion tracking & crop | fast side-to-side, vertical jumps, camera pans, person near edges, closeup/tall performer | Capture screen/video geometry, visible body ratio, lag/jitter and sudden camera movements; FULL must not deliberately crop legs/head solely to meet a minimum zoom |
| Decode & inference contention | same legal 4K60 H.264/HEVC SDR and HDR clips with follow off, Lite0, Lite2 | Measure actual decoder, 60-second/10-minute rendered/dropped ratios, longest drop run, battery/temperature/RAM; aim for <1% dropped frames on qualified device and document differences (NOT guaranteed) |
| Timeline & lifecycle | start/middle/end, 5s ± seek, long clips, 59.94, VFR, 0.25× AI cache, portrait/landscape, rotate, background, process death | No stale anchors across clips; no interpolated fake crossings, no crashes, correct replay/cached-source position and full reset on app background |
| Model and offline | clean airplane-mode install, both model quality modes | Both exact sha/size match final APK, both native models load/infer offline, no all-purpose network grants, no source modification |
| Failure & accessibility | denied file, unsupported decode, power saver, severe thermal state, memory pressure, no people detected, slow device | Clear stop/retry messaging, no invisible indefinite processing, accessible selection and cancellation, no silent quality reduction |

## Unresolved risks

Actual dance identity accuracy has not yet been scored on representative clips. MediaMetadataRetriever may return a nearby but not exact timestamp frame; 180-degree OEM rotation behavior is not guaranteed. EfficientDet boxes alone do not solve long occlusion, stage lighting, same-costume crossover or person reidentification. Even an improved tracker may have gaps. The model does not add actual video resolution; crop enlargement trades field of view for subject focus.

## Build/QA handoff (no APK was created in this task)

- Ensure tracked changes are at the intended GitHub commit; preserve existing RIFE and release signing rules.
- Run scripts/setup-native.sh and scripts/setup-tracker-model.sh with verification of both Lite weights.
- Run scripts/build-local.sh testDebugUnitTest lintRelease assembleRelease, resolving any failures before requesting a signed build.
- Check scripts/verify-artifact.py and scripts/verify-tracker-apk.py on the resulting APK.
- On a device, execute OfflineTrackingModelsTest and the six quality studies above; record measured values and failing scenarios in VALIDATION_REPORT.md.
- Only when requested and applicable, use the existing canonical signing key and GitHub Releases direct asset/upload verification. Never use GitHub Actions.

