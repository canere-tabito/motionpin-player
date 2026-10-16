# Device validation protocol

This is a protocol and blank report template, not evidence that any device test
has run. Populate `VALIDATION_REPORT.md` with actual results. Use `PASS`, `FAIL`,
or `NOT_RUN` for each case; do not enter zero for an unavailable measurement.
An emulator can establish installation or lifecycle behavior but cannot establish
physical-device 4K60, Vulkan throughput, HDR fidelity, or thermal performance.

## Record once per run

Record the tested commit and working-tree state, APK SHA-256/size, application ID,
versionCode, certificate SHA-256, release/debug variant, device model, SoC/RAM,
Android version/build, page size, display mode/refresh/HDR capabilities, power and
thermal state, test time, and tools. Record each licensed test clip's source/hash,
codec/profile/level, resolution/crop/rotation, pixel format/bit depth, color
primaries/transfer/matrix/range and HDR metadata, audio format, frame-rate rational
or VFR PTS description, duration, and bitrate. Use synthetic or redistribution-safe
clips; do not upload private video or screenshots containing it.

## APK artifact gate

Run the verifier against the exact APK that would be distributed:

```sh
python3 scripts/verify-artifact.py app/build/outputs/apk/release/app-release-unsigned.apk \
  --mode build --sdk /path/to/android-sdk \
  --model-manifest MODEL_MANIFEST.json \
  --report build/reports/artifact-verification.json
```

The model manifest input has `artifacts: [{"path": "assets/...", "sha256": "..."}]`;
repeated `--model-file APK_PATH=SHA256` is also supported. Match `--native-library`
to the library named by CMake if it differs from `lib/arm64-v8a/libmotionpin_rife.so`.
For a signed distribution APK, omit `--mode build` and pass
`--expected-certificate-sha256` from an independent record of the canonical key.
The verifier never signs or changes APK bytes. Build mode always labels its
output `NOT_FOR_DISTRIBUTION`, even when structural checks pass. A distribution
artifact must pass SDK `zipalign -c -P 16 -v 4`, `apksigner verify --verbose
--print-certs`, certificate identity, compiled manifest, model hash, native ABI,
and every ELF `PT_LOAD`/uncompressed ZIP 16KB alignment check. This artifact gate
does not certify model redistribution rights or device quality.

## Required device cases

1. **Offline launch and model execution.** Install the actual release-optimized
   artifact, clear application data, enable airplane mode with Wi-Fi off, then
   import a local seekable SDR test clip. Run real inference and record model and
   engine hashes, GPU/backend, inference count, native failure messages, output
   hash and timestamps. Successful app launch alone is not inference evidence.
2. **4K60 normal playback.** On an eligible physical device, play licensed H.264
   and HEVC 3840×2160 60/1 or 60000/1001 clips for at least 60 measured seconds
   each. Record the actual selected decoder, hardware/software status, profile,
   display refresh, rendered/dropped frame counter deltas, longest consecutive
   drop run, AV sync measurement method/result, memory and thermal state. Exclude
   and state warmup/seek windows. Calculate `dropped/(rendered+dropped)`; under 1%
   is a provisional target, not a universal guarantee. Play one representative
   clip for at least 10 minutes and sample temperature/memory/AV sync every minute.
3. **Four orientations and persistence.** Exercise portrait and landscape device
   orientations with portrait and landscape media. Test FIT/FILL explanation,
   single tap, ±5-second double tap, hold-to-slow with release restoration, pinch,
   zoomed one-finger pan, font scaling and TalkBack. Create at least ten mixed
   point/range pins; edit boundaries including ±0.1 seconds; set loop, speed,
   zoom/pan and audio. Rotate during playback and editing, force-stop/relaunch,
   and verify exact source-time state and normalized post-rotation zoom position.
   Verify clipping at the exclusive interval end and no duplicate job creation.
4. **Permissions and data integrity.** Test API 29–32 storage permission, API 33
   video permission, and API 34+ selected-video permission with access removal.
   Verify omitted selected-access items are not marked deleted. Revoke a SAF
   grant and reopen. Test unavailable/cloud and non-seekable providers without
   silent large copying. Export/import a versioned backup twice; verify no
   duplicate pins/channels, no media/cache/key/grant export, and atomic rejection
   of truncated, oversized, unsupported-version and invalid-time input. Try a
   same-name/same-size different-content relink and require refusal or explicit
   confirmation without automatic transfer. Test any supported DB migration.
5. **AI timing and real output.** Use 5–15-second human-motion 1080p and 4K SDR
   clips at 0.5× and 0.25×, with VFR/B-frame and cut samples included in the small
   set. Record source PTS bracketing, duplicate/missing PTS handling, first/last
   output PTS, exact rational output frame count, output duration, real inference
   count and scene-cut held-frame count. At 10 seconds/0.25×/60 fps expect about
   40 seconds and 2400 output frames subject to the documented boundary policy.
   Verify output is full source resolution and that cache playback uses player
   speed 1.0. Compare and seek source↔cache at equal source times, then save pins
   from both. Verify mute labeling or measured pitch-preserving audio behavior.
6. **AI quality and resources.** Compare original, AI and an encode-only control
   at matching source times for at least three of limbs, hair, costume detail,
   occlusion and camera motion. Record encoder codec/profile/bitrate, pixel and
   color formats, processing time, peak CPU/GPU memory if measurable, thermals,
   and artifacts. Equal dimensions/bitrate alone do not establish preserved
   detail. Include limited-range excursions and out-of-RGB8-gamut SDR; verify
   the strict RGB8 path rejects them rather than clipping silently. BT.709 tags
   alone do not establish compatibility with this path. Test cancellation during
   inference, low storage, low battery/power
   saving, severe thermal state with hysteresis, background transition/FGS
   timeout, process death before and after cache rename, and restart recovery.
   Verify only one GPU job, bounded queues, pause/release for 4K playback, no
   partial output treated as complete, and referenced caches protected from LRU.
   Purging cache must preserve pins. A changed source/model/quality/audio
   contract must refuse stale cache reuse.
7. **HDR gates.** Probe HDR10, HLG, HDR10+ and Dolby Vision separately. Record
   decoder, intermediate surfaces/bit depth, color conversion and model precision,
   encoder and output metadata at every stage. Unsupported/unverified AI must
   stop with a reason, preserving source files and their HDR properties. If a
   complete HDR route is available, compare ramps, highlights and real footage
   on an appropriate HDR display with a documented measurement method. SDR
   screenshots and metadata-only checks cannot pass HDR fidelity. Do not copy
   dynamic metadata to generated times or accept a silent SDR/8-bit fallback.
8. **Scale and page size.** Import a synthetic 1000-video/100-pin library and
   observe scroll/search/loading/error behavior without prolonged UI stalls.
   On a 16KB-page arm64 Android environment, record `adb shell getconf PAGE_SIZE`,
   install the release artifact and invoke JNI/model loading. Record that result
   separately from Vulkan/AI/4K performance. A 4KB device cannot pass 16KB loading.
9. **Upgrade and downloaded artifact.** When an existing canonical signed build
   exists, install the new APK as an upgrade and verify retained pins/channels.
   After an authorized Release upload, GET the actual API-returned asset URL,
   check final content is APK rather than login HTML, and compare byte size/hash
   with the local signed APK. Report download/install-by-tap only if performed.

## Copyable result table

| Case | Result | Environment / source | Method and observed result | Limitation / evidence path |
|---|---|---|---|---|
| APK manifest, model, ELF and ZIP checks | NOT_RUN | — | — | — |
| Canonical signature and certificate | NOT_RUN | — | — | — |
| Offline real native inference | NOT_RUN | — | — | — |
| H.264 4K60 ≥60 seconds | NOT_RUN | — | — | — |
| HEVC 4K60 ≥60 seconds | NOT_RUN | — | — | — |
| Continuous playback ≥10 minutes | NOT_RUN | — | — | — |
| Four orientations / gestures / accessibility | NOT_RUN | — | — | — |
| Ten pins, loop, zoom, restart | NOT_RUN | — | — | — |
| Permissions / same-name relink rejection | NOT_RUN | — | — | — |
| Backup reimport / invalid input / migration | NOT_RUN | — | — | — |
| 1080p SDR AI 0.5× / 0.25× | NOT_RUN | — | — | — |
| 4K SDR AI 0.5× / 0.25× | NOT_RUN | — | — | — |
| VFR / scene cut / boundaries / cache mapping | NOT_RUN | — | — | — |
| Visual quality / encoding / audio | NOT_RUN | — | — | — |
| Cancel / storage / background / process death | NOT_RUN | — | — | — |
| Playback exclusion / thermal / cache lifecycle | NOT_RUN | — | — | — |
| HDR10 / HLG precision and quality | NOT_RUN | — | — | — |
| HDR10+ / Dolby Vision gate | NOT_RUN | — | — | — |
| 1000-video / 100-pin library | NOT_RUN | — | — | — |
| 16KB native/model loading | NOT_RUN | — | — | — |
| Upgrade / downloaded APK byte match | NOT_RUN | — | — | — |

Keep normal 4K playback, 4K SDR AI and HDR AI outcomes separate. List unsupported
features explicitly. A passing host unit test or artifact check never substitutes
for an unperformed device case.
