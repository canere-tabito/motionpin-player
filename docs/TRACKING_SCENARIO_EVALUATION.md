# Offline tracking scenario evaluation

This harness compiles the actual production `TrackingMath.kt`, `StablePersonMatcher.kt`
and `PlaybackGeometry.kt`. It also compiles the same harness against the baseline Git
revision in a separate output directory. It does not reimplement the association or camera
equations. Baseline sources are read with `git show`; the working tree is never replaced.
No Gradle APK tasks, signing, network access, GitHub Actions, or source-video edits occur.

## Reproduce

Requirements: Python 3, JDK 17+, and either `kotlinc` or an existing Gradle distribution
under `GRADLE_USER_HOME`. No dependencies are downloaded by this script.

```sh
python3 scripts/evaluate-tracking.py \
  --baseline cfc819794f9b811268398b617b63465b3caf9e29 --check
```

Reports go to `build/tracking-evaluation/` (ignored generated artifacts):

- `comparison.json`: both summaries, source SHA-256 and harness SHA-256.
- `regression-gates.json`: the five explicit synthetic acceptance checks.
- `{current,baseline}/reports/summary.{csv,json}`: per-scenario measurements.
- `*-input.csv`: reproducible labelled inputs; identity labels never reach the matcher.
- `*-association.csv`: status, selected true identity, candidate count, confidence margin.
- `*-camera.csv`: approximately 30 Hz camera positions, zoom and containment.

The initial seed is excluded from observation counts. Both versions use the common
two-argument matcher constructor; the current app can additionally seed rival identities
from the full selection frame. The comparison therefore does not credit that extra input.
The 17 traces cover solo movement, 2/4/8/12 candidates, identical-costume crossing, short
and long occlusion, dense sampling with missed detections, a reversal beside a rival,
merged boxes, missing appearance, jumps/turns/camera pan, irregular source timestamps,
detection jitter, approaching subjects and a seek without reselection.

## Metric definitions and limits

| Metric | Definition |
|---|---|
| Correct / false associations | MATCHED result whose external identity is / is not `target`; a merged detection counts as false. |
| Identity changes | Change of accepted external identity since the preceding accepted detection, including recovery to target; this is not a claim of standard MOTChallenge IDSW scoring. |
| Visible match fraction | Correct associations on frames with a visible-target `truth` row divided by those frames, excluding the seed. |
| Unavailable / longest unavailable | Sum / longest run of source-time intervals ending on visible-target frames without a correct match. Wrong matches also count as unavailable. This is sampled interval accounting, not continuous ground-truth duration. |
| Detector-target absent | Same interval accounting when a visible target has no target-labelled detector candidate. |
| Camera available / cropped samples | `TrackingTrajectory.at` availability and whether the accepted/interpolated full box fits the actual clamped viewport; square FIT geometry, full-body mode, ~30 Hz. |
| Center acceleration RMS | Root-mean-square 2D center acceleration in normalized video units/s²; gaps reset derivatives. Includes starts/turns, so lower is not universally better. |
| Maximum zoom step | Largest absolute zoom change between adjacent available 30 Hz samples. |

The trajectories use **synthetic detector outputs**, not inferred detections from people.
They measure association/camera logic under controlled inputs. They do not measure Lite0
or Lite2 recall, lighting robustness, real re-identification, physical camera shake, UI frame
pacing, Android decoder contention, heat, HDR quality or 4K60 playback. Safe abstention
can reduce continuity; the reversal scenario deliberately remains ambiguous after the
turn instead of choosing the rival. Long occlusion and a seek require reselection.

## Real annotated clips

Use footage owned by the evaluator or licensed for this purpose; retain license, source
hash, orientation, codec and actual source PTS in a local evidence manifest. Do not upload
footage. Collect production detector output with its actual source PTS, post-rotation
normalized boxes, confidence and production appearance samples. Human-label each
candidate as `target`, a stable other identity, or `merged`; add a visible-target truth box.
Do not assign identity by proximity to the tracker output: that would conceal switches.
The current harness consumes an exported trace; it does **not** automatically instrument
Android video decoding or extract annotations from a video.

CSV is unquoted, with this exact header and simple alphanumeric/underscore/hyphen IDs:

```csv
record,time_us,id,left,top,right,bottom,confidence,appearance
frame,0,,,,,,,
truth,0,target,0.2,0.2,0.38,0.75,1,
detection,0,target,0.2,0.2,0.38,0.75,0.91,0.45;0.45;0.45
frame,250000,,,,,,,
truth,250000,target,0.22,0.2,0.4,0.75,1,
detection,250000,target,0.22,0.2,0.4,0.75,0.9,0.45;0.45;0.45
```

Declare each strictly increasing `frame` first, including frames with no detections.
Omit `truth` when the target is fully occluded/out of frame; a visible but undetected target
must still have `truth`. The first frame must contain a selected `target` detection with
valid appearance. A three-component signature above only illustrates the format; export
all production feature components without changing their dimensionality. Use actual PTS
for VFR. Split scene cuts, seeks and explicit reselections into separate traces, then test
the app's scene/seek reset separately. Filename stems must use the same simple characters.

```sh
python3 scripts/evaluate-tracking.py --trace /local/annotated_dance_01.csv \
  --trace /local/annotated_dance_02.csv --baseline cfc8197 \
  --output build/tracking-real-evaluation
```

Judge real clips with declared acceptance thresholds, including false matches, continuity,
unavailable duration and manual inspection of corresponding source footage. Do not infer
real-video PASS from synthetic thresholds. Record selection difficulty and deliberate
reselection count separately. Longer 2–30 minute clips are required for the device gate.

## Original geometric video fixture

An optional local renderer creates a labelled original box-animation MP4 from a trace.
It requires Pillow and ffmpeg. The video is a debugging/playback fixture and is explicitly
labelled synthetic; it is unsuitable for measuring person-detector accuracy.

```sh
python3 scripts/render-tracking-fixture.py \
  build/tracking-evaluation/current/reports/same_uniform_crossing-input.csv \
  build/tracking-evaluation/same-uniform-crossing.mp4
ffprobe -v error -show_entries stream=codec_name,width,height,avg_frame_rate,nb_frames \
  -show_entries format=duration,size -of json \
  build/tracking-evaluation/same-uniform-crossing.mp4
```

It writes a **new** 640×360/30 fps H.264 fixture, refusing to overwrite any existing file.
This does not change or represent source-video quality. Source trace and rendered video
remain local. No third-party footage or model-based person-generation license is involved.

## Execution record — 2026-10-16

See `validation/tracking-scenario-comparison.csv` and `validation/tracking-scenario-provenance.json` for recorded
measurements and exact source/harness hashes. Host: OpenJDK 21.0.12.1, cached Kotlin
compiler 2.0.20, JVM target 17. Status: production Kotlin compilation, 17 baseline/current
synthetic traces and five regression gates **PASS**. The same-uniform crossing CSV import
round trip reproduced every metric (**PASS**). Original geometric MP4 rendering and
ffprobe checks **PASS**: H.264, 640×360, 30/1 fps, 151 frames, 5.033333 s, 33,141 bytes.
Actual annotated dance videos, Android viewing of this fixture and
4K60/HDR OFF/ON performance comparisons are **NOT_RUN**: no eligible device or licensed
real annotated dance corpus was available to this evaluator.

Key measured effects (synthetic, not real-world rates):

- False associations: **5 → 0**; accepted identity changes: **5 → 0** across this suite.
- 2/4/8/12-candidate continuity: **40/40 → 40/40** per trace.
- Identical-costume crossing: **17/20 → 19/20** correct, with zero false associations.
- Five missing detections spaced 50 ms apart: **0/7 → 7/7** correct once detections return.
- Reversal beside rival: **1 correct + 3 false → 1 correct + 3 ambiguous**; this safety gain
  explicitly does not prove automatic recovery after a hard turn.
- Approaching subject: full-body crop violations **30 → 0** sampled camera frames.
- Detector-jitter trace center acceleration RMS: **0.747753 → 0.528774** normalized units/s².
  Smooth-solo RMS slightly increases **0.041040 → 0.042172**; no universal smoothness claim.
- Detector-jitter maximum zoom step: **0.002367 → 0.001947** after separating comfort
  margin from the hard containment limit. Rapid-approach maximum zoom step increases
  **0.018054 → 0.037749** because containment takes priority over zoom damping; the
  corresponding crop violations decrease from 30 to zero.
