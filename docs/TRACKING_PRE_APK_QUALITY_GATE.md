# MotionPin — tracking pre-APK gate and build handoff

Updated: 2026-10-16. Development version: `0.2.0-alpha1` (code 2).
Baseline: `cfc819794f9b811268398b617b63465b3caf9e29`.
The latest executed outcomes are in [VALIDATION_REPORT.md](../VALIDATION_REPORT.md).
This document is a gate/procedure, not a physical-device acceptance certificate.

## Current authorization boundary

No GitHub Actions, workflow creation/dispatch/rerun, APK assemble/package, signing,
key generation/replacement, debug-key fallback, tag or Release publication in this
work. Future artifact creation requires the next explicit instruction. App ID,
data schema, source files and the canonical signing identity remain unchanged.

`scripts/validate-pre-apk.sh` runs a dry-run graph followed by:

```
compileDebugKotlin compileReleaseKotlin compileDebugAndroidTestKotlin testDebugUnitTest lintRelease
```

`scripts/no-apk.init.gradle` rejects artifact/signing/install/publish tasks before
execution, including transitive tasks. Resource compilation and class/JAR tasks
are permitted. `scripts/build-local.sh` now defaults to tests and lint rather
than assembling an APK. Never call a no-argument build script assuming its
historical defaults are still appropriate.

## Implemented corrections to review

- Conservative time-based identity tracking with valid appearance, bounded rival
  observations, shape/motion/IoU gates and uncertainty after ambiguous occlusion.
- Exact confirmed anchors after gaps, explicit seek/loop camera reset, no stale
  catch-up across uncovered time, correct source clock for cache/replay/pins.
- Actual aspect/FIT/FILL geometry, size-aware FULL/UPPER/LOWER framing, paused
  stability, adaptive damping, controlled zoom and manual resume. FULL temporarily
  renders FIT without changing the stored user preference.
- Serialized native detector/retriever lifetime, atomic stale-result rejection,
  correct MPImage/Bitmap ownership, resume after inspected rather than matched
  samples, and release barriers at playback/tracking/RIFE transitions.
- Scene-change safety guard; thermal hysteresis, low battery/saver, buffering and
  recent drop pressure all defer analysis without lowering source quality.
- Numbered target overlays, 48dp small-target hit allowance, overlap cycling,
  selection confirmation, accessible choices and always-available reselection.

See [implementation rationale](TRACKING_IMPLEMENTATION_NOTES.md). These are
implemented paths; real footage and hardware acceptance remain separate.

## Reproduce the host gates

1. Use [TOOLCHAIN.md](TOOLCHAIN.md). Run `scripts/setup-native.sh` and
   `scripts/setup-tracker-model.sh`. Both actual model SHA-256/size checks must pass.
2. Run `scripts/validate-pre-apk.sh`. Preserve task graph, compiler/test reports
   and lint text/XML. Fix failures before advancing. Kotlin mirrors/stubs are not
   substitutes for compilation of the actual app.
3. Run `python3 scripts/evaluate-tracking.py --baseline cfc819794f9b811268398b617b63465b3caf9e29`.
   This compiles actual baseline/current pure sources and reports identity errors,
   continuity, unavailable time and camera measurements. See
   [scenario protocol](TRACKING_SCENARIO_EVALUATION.md) for annotated real traces.
4. Run the offline host model verifier with the environment described in
   [TRACKING_HOST_MODEL_VALIDATION.md](TRACKING_HOST_MODEL_VALIDATION.md).
   Host Tasks inference is useful format/API evidence, not an Android native pass.
5. Confirm no tracked workflow, APK, signing material or private video has entered
   the diff; check `git diff --check`, no INTERNET permission and unchanged source
   quality policy. Record GitHub branch head after authorized source push.

## Mandatory next-stage device acceptance

An APK and instrumentation APK are required for the following. They are deferred
until APK creation is explicitly authorized; source compilation alone cannot pass.

| Study | Cases | Evidence needed |
|---|---|---|
| Native/offline | Both exact Lite models, airplane mode, API29+ arm64; 16KB device separately | Model hash/size, production options, native load/inference output, no network or source writes |
| Identity | 2/4/8+ dancers; similar uniforms, crossings, target/rival disappearing, turn/jump, edge exits, lighting and cuts | Legal clip provenance/hash, independent target labels, false associations and ID changes, visible coverage, unavailable time, safe-stop/reselect count |
| Camera | Portrait/landscape video and device, FULL/UPPER/LOWER, FIT/FILL, fast lateral motion/approach | Actual displayed body containment, jitter/lag/zoom plots and viewing comfort; saved FILL restores on exit; no pixel-restoration claim |
| Playback contention | Same 4K60 H.264 and HEVC SDR/HDR clips: OFF, Lite0, Lite2 | ≥60-second runs plus one ≥10-minute run; actual decoder, rendered/dropped deltas, drop bursts, AV sync, CPU/GPU/RAM, power/thermal; warmup/seek excluded for acceptance metrics |
| Timeline/lifecycle | Short/long seek, backward loop, 3s replay, .25× AI cache, rotation, pause/resume, background and process death | Correct original-source time, no stale anchors/duplicate decoder, native-release ordering, predictable manual resume |
| UI/accessibility | Small/overlapping people, no detections, quality changes, TalkBack/font scaling, errors and cancellation | Select each numbered target, confirmation never starts wrong index, controls reachable and no fullscreen/pin/reel interference |
| Regression | Pins/loop/replay/reels, cache comparisons/RIFE, backup/restore and upgrade | Existing tests plus actual device transactions/playback; same applicationId/schema/canonical key |

The provisional qualified-device target is <1% dropped frames; it is not a measured
result or a universal promise. Zero silent identity substitution is a controlled
scenario acceptance target, not a guarantee for all footage. Measure the increase
in safe pauses as well as any decrease in wrong-person tracking.

## Artifact and signing handoff — only after explicit authorization

- Revalidate the exact source commit, then use existing local build tooling.
- Verify both uncompressed detector assets, source/RIFE assets, manifest, ABI,
  ELF/ZIP 16KB alignment and optimized native loading on the resulting artifact.
- Use only the existing canonical key with its independently recorded certificate
  and durable user-managed backup. No substitute key, debug fallback or unsigned
  public distribution. No artifact/key was produced in this task.
- Record artifact hash/size and actual device evidence. Release publishing is a
  separate explicit authorization. Never use GitHub Actions.

## Known limits

Real dance-video accuracy, SurfaceView transformations/HDR compositing and sustained
Android performance are not certified by host results. MMR nearby-frame timing,
manual OEM rotation correction, RGB8 stage lighting, similar-looking cuts, long
occlusion and detector false positives remain risks. Bounded history requires
reselection when seeking outside coverage. Conservative rival/shape checks can
pause on genuine complex motion. Multi-instance Activity resource ownership and
long-duration memory behavior need device study. See the report for measured
values and explicit NOT_RUN entries; never replace them with target values.
