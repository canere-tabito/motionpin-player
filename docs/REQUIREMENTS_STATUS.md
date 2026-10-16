# Requirement implementation and acceptance status

Reviewed against [IMPLEMENTATION_REQUIREMENTS.md](IMPLEMENTATION_REQUIREMENTS.md) on 2026-10-16.
This is a source inventory, not a declaration that all P0 requirements pass. The current Android
implementation has not been exercised on an eligible physical device. Successful compilation,
host inference, unit tests and APK inspection cannot establish 4K60 playback, Android Vulkan
inference, HDR fidelity, thermal behavior or the four-orientation user experience.

`CODE PRESENT` means the corresponding path is connected in source. `PARTIAL` means a limitation
or missing part is identified. `NOT IMPLEMENTED` means there is no functioning implementation
of that part. Execution results belong in [VALIDATION_REPORT.md](../VALIDATION_REPORT.md), using
`PASS`, `FAIL` or `NOT_RUN`; the device cases below are `NOT_RUN` until actual evidence is recorded.
The test procedure is [DEVICE_VALIDATION.md](DEVICE_VALIDATION.md).

## Separate media acceptance gates

| Gate | Current implementation | Acceptance status / remaining work |
|---|---|---|
| Normal H.264/HEVC 3840×2160 60/59.94 fps | Media3/MediaCodec with SurfaceView, source files played directly; decoder/display diagnostics | CODE PRESENT; actual H.264 and HEVC ≥60-second runs, <1% provisional drop target, AV sync and ≥10-minute sustained run are NOT_RUN. No claim of universal 4K60 support. |
| Normal HDR10/HLG/HDR10+/Dolby Vision | Format/profile/display inspection and rejection of known unsupported combinations | PARTIAL acceptance: actual decoder/display output and OS conversions are unverified. Extraction or capability inspection is not proof of correct HDR display. |
| 1080p and 4K SDR AI | Connected original-resolution decode → real RIFE → encode/mux → validation → private cache → pin playback | CODE PRESENT for explicitly identified BT.709, 8-bit, 4:2:0, supported full-frame geometry and RGB8-representable signals; Android 1080p and 4K end-to-end runs and visual acceptance are NOT_RUN. |
| 10-bit/HDR AI | Separate P010 decoder/Main10 encoder inventory and model/intermediate/metadata gates; requests rejected before RGB8 inference | NOT IMPLEMENTED as a preserving generation path. The bundled model interface and current intermediate conversion are RGB8. HDR10, HLG, HDR10+, Dolby Vision and 10/12-bit input remain unsupported. This is a P0 gap, not a completed HDR feature. |

## P0 source mapping

Paths in this table are relative to the repository root. Java/Kotlin paths share
`app/src/main/java/com/caneretabito/motionpin/` unless shown in full.

| Requirement | Source implementation | Status and specific verification gate |
|---|---|---|
| Dedicated reproducible Android project; API29+, arm64; pinned versions | `app/build.gradle.kts`, `build.gradle.kts`, Gradle wrapper, `scripts/build-local.sh`, `docs/TOOLCHAIN.md` | CODE PRESENT. Local build/lint/unit results and exact artifact identity must be taken from the validation/build reports. No Actions workflow is part of the project. |
| Three tabs, continue/recent, folder/search/sort, point/range/AI pin filters, manual channels | `ui/MotionPinApp.kt`, `ui/MainViewModel.kt`, `data/MotionRepository.kt` | CODE PRESENT. Lazy lists, two concurrent thumbnail loads and a bounded thumbnail LRU are implemented. Progressive scan state includes empty/partial/error. A 1000-video/100-pin device stress run is NOT_RUN. |
| Obsidian/Graphite/Champagne visual system and four orientation combinations | `ui/Theme.kt`, `ui/PlayerScreen.kt`, `player/PlaybackGeometry.kt` | CODE PRESENT: width-fit portrait layout for landscape media, immersive portrait media, landscape HUD, optional side controls for portrait media. Visual fit, cutouts, enlarged fonts and rotations need device review. |
| FIT by default, explained FILL; single tap HUD; double tap ±5 seconds; hold-to-slow; pinch/pan | `ui/PlayerScreen.kt`, `player/RetainedPlayer.kt`, `player/PlaybackGeometry.kt` | CODE PRESENT. SurfaceView is retained; there is no TextureView fallback. Surface scaling/cropping, HDR compositing, gesture cancellation and frame timing are NOT_RUN on device. |
| 48dp controls, accessibility descriptions and alternatives to gestures/long press | `ui/PlayerScreen.kt`, `ui/MotionPinApp.kt`, `ui/PinEditor.kt` | CODE PRESENT for principal controls and custom accessibility actions. Full TalkBack and font-scale traversal is NOT_RUN; source annotations alone do not establish accessibility acceptance. |
| Player lifetime independent of recomposition/rotation | `ui/MainViewModel.kt`, `player/RetainedPlayer.kt`, `ui/PlayerScreen.kt`, `MainActivity.kt` | CODE PRESENT: ViewModel owns ExoPlayer; AndroidView attaches/detaches its SurfaceView. Editing uses retained state and saveable fields. Rotation/restart validation with ten pins is NOT_RUN. |
| Direct source playback; codec/profile/level/HW-SW/display diagnostics | `player/PlaybackDiagnostics.kt`, `player/RetainedPlayer.kt` | CODE PRESENT for format capabilities, actual selected decoder and cumulative rendered/dropped counts. Warmup/seek-excluded measurement, longest consecutive drop run, AV synchronization and sustained thermals are not automated by the current diagnostics; they require the device protocol. |
| Point pin without pausing; editable ±5-second interval candidate; six speeds; exact source µs bounds | `ui/MainViewModel.kt`, `ui/PinEditor.kt`, `domain/Timeline.kt`, `data/Entities.kt`, `data/MotionRepository.kt` | CODE PRESENT. Point/range validation, decimal seconds/milliseconds and ±0.1-second edits exist; frame-perfect seeking is not promised. Actual pin persistence/reopen behavior is NOT_RUN. |
| Accurate interval looping, not an approximate UI end timer | `player/RetainedPlayer.kt` | CODE PRESENT using ClippingMediaSource and repeat mode. Boundary/gapless/audio behavior remains device-unverified. The 150ms ticker only updates UI state. |
| Saved zoom/pan in normalized post-rotation video coordinates; no AI baking | `domain/Timeline.kt`, `player/PlaybackGeometry.kt`, `ui/PinEditor.kt`, `data/Entities.kt` | CODE PRESENT. Pin scale/center persisted; viewport applied when either source or cache is displayed. Four orientation comparisons and process restart still need device testing. |
| Real bundled offline AI, JNI/ncnn/Vulkan integration, arbitrary timestep | `ai/NativeRife.kt`, `app/src/main/cpp/{native_rife.cpp,rife_engine.cpp,rife_engine.h}`, `app/src/main/assets/models/rife-v4.6/`, `MODEL_MANIFEST.json` | CODE PRESENT. Exact converted v4.6 graph/weights and custom Warp are pinned. [NATIVE_VALIDATION.md](NATIVE_VALIDATION.md) records PASS for real CPU-host and Mesa llvmpipe software-Vulkan inference on synthetic frames, arbitrary timestep, exact endpoints and CPU/Vulkan comparison. Android JNI/model loading and physical-GPU inference remain NOT_RUN; host software Vulkan is not Android device evidence. |
| Streaming decode from preceding keyframe; display PTS/VFR/B-frame handling; original exact hits/endpoints; scene-cut holds | `ai/CodecFrames.kt`, `ai/VideoPipeline.kt`, `domain/Timeline.kt` | CODE PRESENT with fixed reusable RGB buffers and codec backpressure. Duplicate/out-of-order handling and rational planning have unit-test cases. Scene-cut detection is a heuristic, not guaranteed cut recognition; real VFR/B-frame/cut clips need device validation. |
| 0.5×/0.25× slow motion baked into 60fps cache; no cumulative rational rounding | `domain/Timeline.kt`, `ai/VideoPipeline.kt` | CODE PRESENT. Frame timestamps are computed from their indices. Unit cases include 10 seconds at 0.25× → 40 seconds/2400 frames and 60000/1001 timing. Actual output duration/PTS on Android is NOT_RUN. |
| Cache playback at speed 1.0; source/cache seek, compare and pin coordinates share source-time basis | `player/PlaybackGeometry.kt`, `player/RetainedPlayer.kt`, `ui/MainViewModel.kt` | CODE PRESENT. Player mapping clamps to the source interval; cache playback does not apply the saved slow speed a second time. Equal-source-time comparison and pin recording are NOT_RUN on device. |
| FrameDescriptor/QualityContract across formats and explicit supported/unsupported/unverified states | `domain/Quality.kt`, `ai/VideoQualityProbe.kt`, `ai/CodecFrames.kt`, `ai/VideoPipeline.kt` | CODE PRESENT. Unknown colors/precision are blocked; source size/crop/rotation/bit depth/color and real output codec/profile are checked. Detailed frame/model visual fidelity remains unverified. |
| Preserve source resolution; no undisclosed SDR/8-bit/low-quality fallback | `ai/VideoQualityProbe.kt`, `ai/CodecFrames.kt`, `ai/VideoPipeline.kt`, native engine | CODE PRESENT for strict supported SDR path: no full-frame downscale, no quality-reduction retry. Out-of-range/subblack/superwhite/chroma excursions and nonrepresentable RGB8 samples are rejected. HDR/high-precision path is missing. No optional consent-based reduced-quality mode is offered. |
| HDR intermediate precision, model suitability and static/dynamic metadata gates | `ai/VideoQualityProbe.kt` (`HdrPipelineProbe`), `domain/Quality.kt`, `MODEL_MANIFEST.json` | PARTIAL implementation effort: hardware inventory and separate failure reasons exist. P010/high-precision decode→color→inference→encode, HDR-adapted model validation and dynamic metadata retiming are NOT IMPLEMENTED. A capability inventory cannot satisfy this P0. |
| Honest compression and output validation | `ai/CodecFrames.kt`, `ai/VideoPipeline.kt` | CODE PRESENT: native-size AVC High, explicit bitrate plan, inspect muxed codec/profile/color/dimensions, every sample PTS/count/duration and first three decoded output frames. Lossy encoding is stated. Encode-only control, hair/limb/costume/occlusion/motion comparisons and measured detail retention are NOT_RUN. |
| Audio matches slow interval or is explicitly muted | `ai/VideoPipeline.kt`, `player/RetainedPlayer.kt`, `ui/PinEditor.kt`, `ui/PlayerScreen.kt` | AI cache audio is deliberately muted and labeled, permitted for the initial AI path. AI audio stretching is NOT IMPLEMENTED. Normal playback requests Media3 pitch-preserving speed; audible pitch/sync quality at each offered speed has not been measured. |
| UUID video identity; pin metadata, channels/relations, AI jobs, settings persisted | `data/Entities.kt`, `data/MotionPinDatabase.kt`, `data/MotionRepository.kt` | CODE PRESENT with Room and non-destructive schema policy. Pins include tags/notes/zoom/audio/AI reference. Current schema is v1; there is no previous released schema to migrate. Device SQL persistence, forced process death and future migration testing remain unperformed. |
| Permissions API29–32/33/34+, SAF persisted returned read grant, permission/unavailable states | `MainActivity.kt`, `data/MotionRepository.kt`, `app/src/main/AndroidManifest.xml` | CODE PRESENT. Rechecks on foreground; partial-query omissions never imply deletion. Unknown cloud providers, picker-backed remote ambiguity and non-seekable video URIs are rejected with a local-file explanation; no automatic giant copy/download. Revocation/provider behavior is NOT_RUN. |
| Strong restored/source identity; no same-name automatic relink; source revision invalidation | `data/MotionRepository.kt`, `domain/Quality.kt`, `ai/AiCoordinator.kt` | CODE PRESENT. Full hash at explicit AI/cache boundaries, explicit weak/mismatch confirmation, retained pins and invalidated AI references on source change. Existing videos are not all rehashed on startup. Same-name/same-size replacement device tests remain NOT_RUN. |
| Versioned bounded JSON backup, consistent snapshot, atomic validation/import, UUID conflicts, repeated import | `data/BackupCodec.kt`, `data/MotionRepository.kt`, `ui/MotionPinApp.kt` | CODE PRESENT: 8MiB/20,000-record/nesting bounds, integer time/range checks, transactional DB writes and explicit all-or-nothing conflict result. Source files/cache/grants/keys excluded. Pure roundtrip/conflict/malformed-input tests exist; actual SAF+Room import twice and rollback on device are NOT_RUN. |
| Settings backup and restore | `data/Entities.kt`, `data/MotionRepository.kt`, `data/BackupCodec.kt`, `ui/MainViewModel.kt` | CODE PRESENT: settings table/API, snapshot import/export and persisted FIT/FILL, default playback speed and mute preferences. List sorting/filter/search use saveable Compose state, rather than persistent library preferences. Settings restart/backup restoration needs device verification. |
| Strong cache key and completion publication; crash reconciliation; protect referenced caches | `ai/VideoPipeline.kt`, `ai/AiCoordinator.kt`, `ui/MainViewModel.kt`, `ui/MotionPinApp.kt` | CODE PRESENT: source hash/revision, interval/rate, model/engine/precision/color/encoder/audio enter identity; temp validation plus atomic rename precedes DB completion. Source and cache bytes rehashed before reuse; incomplete/orphan files are removed and pins retained. Explicit cache clearing preserves pins and refuses active playback/generation. No automatic LRU evicts referenced results; low storage stops work. Crash-window/clear-and-regenerate tests are NOT_RUN. |
| One GPU job, playback priority, safe cancellation/release, progress and restart | `MotionRuntime.kt`, `ai/AiCoordinator.kt`, `ai/VideoPipeline.kt`, `data/JobPersistencePolicy.kt`, native engine | CODE PRESENT with one process-wide coordinator, a serialized worker and playback admission handshake. Cancellation/pause checks occur at processing boundaries; current native inference must return first. Progress/status persists; stale-source cleanup can record failure without making the old interval runnable. Resume explicitly regenerates the whole interval, rather than appending an incomplete MP4. GPU resource release/latency/OOM/device-lost device tests are NOT_RUN. |
| Low storage/battery/power saver/thermal SEVERE; hysteresis without quality changes | `ai/AiCoordinator.kt`, `ai/VideoPipeline.kt` | CODE PRESENT. Storage is checked before and during generation; severe thermal pause is latched until light-or-below; charging does not override thermal state. Actual memory peaks/thermal response/low-space recovery are NOT_RUN. |
| API35 mediaProcessing FGS/notification/timeout; API29–34 safe visible-only execution | `AiProcessingService.kt`, `MotionRuntime.kt`, `MainActivity.kt`, manifest | CODE PRESENT for mediaProcessing foreground type, progress/stop notification, runtime notification permission request, `onTimeout`, non-sticky service and explicit resume. Earlier Android uses visibility/boundary pause. OS quota enforcement, notification permission/visibility and timeout behavior need device validation; no service restart loop or mediaPlayback-type workaround is used. |
| No automatic network/cloud transfer; non-destructive source handling | Manifest, `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml`, repository and pipeline | CODE PRESENT: no INTERNET/all-files/image permission, read-only source access, separate private generated files, explicit local SAF backup and automatic-backup exclusions. OEM transfer behavior cannot be completely guaranteed. Static artifact verification must inspect the final compiled manifest. |
| Model/dependency provenance and notices | `MODEL_MANIFEST.json`, `THIRD_PARTY_NOTICES.txt`, `scripts/native-sources.json`, `ui/LicenseDialog.kt` | CODE PRESENT for exact adopted converted graph/weights, pinned upstream revisions/hashes, input/shape/timestep/precision and license evidence. Original PyTorch checkpoint hash and exact upstream conversion command were not published and remain unknown; this limitation is recorded. Runtime download is not required. |
| 16KB native packaging and release identity | `app/src/main/cpp/CMakeLists.txt`, `app/build.gradle.kts`, `scripts/verify-artifact.py`, `scripts/sign-release.sh` | CODE PRESENT for modern NDK/flexible page size, checks over each ELF and ZIP entry, zipalign/apksigner/certificate/model/manifest verification. Artifact checks must pass against the final APK; 16KB Android load test is separately NOT_RUN. No substitute/debug release key is generated. |
| Canonical-key signing and direct GitHub Release byte upload/re-download | `scripts/sign-release.sh`, `scripts/publish-release.py` | TOOLING PRESENT, delivery not established by source. Canonical key/durable backup/certificate identity, Actions-disabled evidence, actual upload/API URL and re-download hash verification must all succeed before claiming a deliverable signed Release. See final build/validation report for actual authorization and outcome. |

## P0 completion blockers and limitations

1. HDR/10-bit AI has no preserving end-to-end implementation. It is blocked safely, with a capability
   probe and explicit reasons; that is a failure to fulfill the HDR generation scope, not proof of support.
2. Android/Vulkan real inference and the MediaCodec interval pipeline have not passed a device run.
   CPU-host and software-Vulkan inference prove the actual graph computes outputs; they do not prove Android integration,
   4K resource feasibility or visual quality of 5–15-second performance footage.
3. Required physical-device playback, UI, data-safety, resource, offline and page-size acceptance cases
   remain NOT_RUN. Output dimension/color/PTS inspection does not establish perceptual fidelity.
4. Diagnostics do not yet automate AV synchronization, longest consecutive frame-drop runs or
   warmup/seek-excluded performance windows. These measurements must be collected separately.
5. Automatic cache quota/LRU cleanup is not provided. Explicit cache clearing preserves pins and
   the app stops on insufficient storage instead of automatically evicting referenced video results.
6. Formal delivery is separate from implementation: no source or unsigned APK should be described
   as a signed, verified downloadable Release without the actual signing/upload/download evidence.

These limitations must remain in any prerelease notes. A prerelease label does not waive missing
key/license/permission gates, data-safety failures, or the distinction between implemented and
device-validated behavior.

## Focused local test inventory

`app/src/test/java/com/caneretabito/motionpin/domain/TimelineTest.kt` covers rational timing,
quarter-speed duration/frame count, cache speed, VFR/cuts, duplicated PTS, pin bounds and normalized
zoom. `QualityTest.kt` covers unknown metadata, dimension/bit-depth/HDR downgrade rejection and
strong identity. `BackupTest.kt` covers serializing/restoring original source time and zoom, omitted
cache references, repeated logical records, UUID collision, invalid times, versions and input bounds.
`JobPersistenceTest.kt` covers failure cleanup after a source becomes shorter while refusing fresh,
runnable or completed work with invalid bounds. `ai/PipelineSafetyTest.kt` covers AVC SPS depth,
cut heuristics and rejecting SDR signal clipping before the RGB8 boundary.

Run them with the pinned local Gradle toolchain. The actual result/count belongs in the validation
report after the latest source edit. These tests do not substitute for a Room/SAF transaction test,
an Android decoder/encoder run, or any of the physical-device acceptance cases.

## P1 backlog retained

- Scene playlists across pins, smart channels, richer scene thumbnails and tag navigation.
- Precise seek/preview controls beyond current timestamp and ±0.1-second input.
- Subtitle and audio-track selection, picture-in-picture and advanced synchronized comparison.
- Validated real-time AI on suitable devices; the present route is interval pre-generation.
- HDR-preserving AI remains a P0 gap above and is not moved into this P1 backlog.

Basic tags/notes and source/cache comparison already exist; their richer P1 forms are not claimed
complete. This inventory does not remove or downgrade requirements from the original brief.
