# Tracking audit and implementation decisions — 2026-10-16

Baseline: `cfc819794f9b811268398b617b63465b3caf9e29`, clean checkout of
`feat/motionpin-player`, application `com.caneretabito.motionpin`, version
`0.2.0-alpha1` / code 2. Application identity, Room schema, existing source-video
and RIFE quality contracts are unchanged. No runtime networking was added.

## Confirmed defects and corrections

| Evidence in previous implementation | Correction and verification |
|---|---|
| MediaPipe transitive DataTransport merged INTERNET into both actual app manifests, despite its absence in the source manifest. | Explicit removal in the app manifest and a build gate over merged debug/release manifests; offline policy must be checked after dependency merging. |
| Empty/mismatched appearance had distance zero; invalid float descriptors evaded comparison. | Reject unknown/corrupt appearance. Real production matcher regression tests. |
| A large frame gap was clamped to two seconds before association; missing-count expiry changed with detector cadence. | Monotonic observations and 1.25-second maximum source-time gap, checked before association. VFR, duplicate/out-of-order, high-cadence occlusion and long-gap tests. |
| Motion prediction selected a nearby uniformed rival when the target reversed. | Keep a bounded set of recent competing detections; reject contested alternative assignments and uncertain singleton recovery. Keep original appearance and a short-term average; reset velocity faster for supported reversals. Labelled baseline/current comparison. |
| Area gating accepted broad detections spanning multiple dancers. | Width/height and area gates reject merged detections; separate horizontal/vertical motion gates avoid using body height as horizontal travel allowance. |
| A confirmed anchor after a long gap was rejected with the gap. | Return exact confirmed timestamps before deciding whether interpolation is safe. No extrapolated motion beyond observation. |
| Camera smoothing moved on repeated paused timestamps, and FULL fitting ignored the actual viewport aspect/FILL crop. | Shared rendered geometry, unchanged-time stability, source-time adaptive damping and bounded lookahead; full-body containment tests use actual rendered coordinates. FULL temporarily uses FIT, including while held, without rewriting saved FILL preference. |
| Fixed minimum zoom left small dancers too small; slow zoom-out could clip an approaching dancer. | Fit a body region to available viewport height/width, with a 10% comfort target, separate 2% hard safety ceiling and immediate containment correction; minor box-size noise no longer bypasses zoom damping. This enlarges existing pixels, not AI resolution enhancement. |
| Analysis restarted from last successful match rather than last inspected frame. | Resume from inspected timestamp, including misses; avoid repeated decoding/feeding old observations. |
| MPImage wrappers leaked; naively adding close before appearance sampling would recycle the pixels still in use. | Actual MediaPipe 1.1.0 AAR inspection confirms Bitmap ownership. Extract appearance within wrapper lifetime, close each wrapper, keep only a separate bounded selection preview. |
| Cancellation did not serialize native work; checking generation separately from publishing allowed a race. | Serialize detector/retriever sessions; check Job cancellation after native calls; generation-guard publications under one monitor. Release barrier before transferring resources to RIFE. Native calls themselves remain non-interruptible. |
| RIFE admission could overlap tracking or an old tracking auto-resume. | Cancel selection/auto-resume on explicit transitions; await both playback and tracking release; selection waits for RIFE to yield. Disposal uses an admission revision so an old player cannot release a newer player's reservation. |
| Source seek/loop was inferred only from camera elapsed time. | Explicit Media3 discontinuity revision resets camera even on short forward seeks; outside retained analysis requires reselection. Manual resume consumes pending discontinuity before seeding easing. |
| Low-frame pressure sampling could be defeated by three-second replay loops. | Accumulate decoder-counter windows independently of seek/loop revisions, yield immediately during buffering, recover after cooldown. Never lower playback quality. |
| Numbered buttons had unnumbered boxes; overlapping taps immediately selected the smallest box. | Numbered overlays and accessible chips, expanded small-target hit areas, overlap cycling, highlight then explicit start. Manual tools reliably hold automatic motion. |

## Algorithm choices

This is a conservative single-selected-person tracker with bounded rival context,
not a full multi-object identity system. Candidate ranking uses predicted and
stationary positions, IoU, area/aspect, detection confidence, original/recent
appearance and a best-versus-runner-up margin. The rival guard is bounded by the
24-detection cap. It deliberately returns ambiguity when identity is not supported.

A full Kalman/Hungarian/ByteTrack/BoT-SORT implementation was not adopted here:
tracking every person's ID would still depend on uncertain sparse observations,
and low-confidence detections without reliable identity evidence must not silently
replace the selected dancer. Optical flow adds intervening frame decoding and
would need actual PTS and device-load validation; it cannot solve long occlusion.
Pose/ReID models add compute, model assets and their own validation requirements.
No evidence from this environment justified those costs. The corrected motion,
rival and short-term appearance approach has a measured improvement on the
specified synthetic regressions, not a general dance-accuracy claim.

## Resource and signal contracts

- Original video/AI cache remain direct Media3 SurfaceView sources. Only analysis
  thumbnails are RGB8, max 640px side. No source transcode, downsample, frame-rate
  cap, HDR/SDR conversion, source-file mutation or video upload is introduced.
- Models and retrievers are closed after each bounded six-second window. One
  native session is active; a 20ms yield is inserted between samples during
  playback. MediaPipe Java 1.1.0 has no public CPU-thread-count setter.
- Model choice remains HIGH Lite2/250ms or BALANCED Lite0/500ms. Resource gates
  wait on severe heat (until LIGHT or below), low battery, saver mode, buffering
  or measured dropped-frame pressure; they do not change model or source quality.
- Retained anchors are capped at 2,400 (~10 minutes HIGH, ~20 minutes BALANCED).
  Seeking earlier requires reselection. Six-second lookahead is not guaranteed
  when the decoder/inference cannot keep up; playback takes priority.
- MMR provides requested-time nearby frames, not verified exact frame PTS.
  The matcher handles irregular source sample times, but exact VFR image/PTS
  agreement remains an Android decoder gate. Manual rotation correction must
  make the preview match the displayed video; automatic 180-degree OEM behavior
  is not guaranteed.
- The 12×8 RGB spatial scene guard subtracts a uniform lighting shift. It can
  miss similar-looking cuts and stop on large camera moves. Synthetic unit
  tests do not establish stage-light/cut accuracy.

## Evidence and remaining acceptance

See [VALIDATION_REPORT](../VALIDATION_REPORT.md),
[scenario protocol](TRACKING_SCENARIO_EVALUATION.md),
[host model evidence](TRACKING_HOST_MODEL_VALIDATION.md) and
[pre-APK gate](TRACKING_PRE_APK_QUALITY_GATE.md). Host tests do not certify
SurfaceView transforms, camera comfort, real dance identity, Android native
loading, 16KB-page operation, decoder contention, sustained 4K60 or HDR output.
