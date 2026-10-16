#!/usr/bin/env python3
"""Compile/run production Kotlin association/camera on labelled offline traces; no APK tasks."""
import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
SOURCES = [
    "app/src/main/java/com/caneretabito/motionpin/player/PlaybackGeometry.kt",
    "app/src/main/java/com/caneretabito/motionpin/tracking/TrackingMath.kt",
    "app/src/main/java/com/caneretabito/motionpin/tracking/StablePersonMatcher.kt",
]
HARNESS = ROOT / "scripts/tracking-eval/TrackingScenarioEval.kt"


def run(command):
    subprocess.run([str(x) for x in command], cwd=ROOT, check=True)


def compiler():
    """Use an existing toolchain; never download or trigger Gradle/Actions."""
    java = Path(os.environ.get("JAVA_HOME", "/usr")) / "bin/java"
    if not java.is_file():
        java = Path(shutil.which("java") or "java")
    standalone = shutil.which("kotlinc")
    if standalone:
        home = Path(standalone).resolve().parents[1]
        return [standalone], str(home / "lib/kotlin-stdlib.jar"), java
    caches = [Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))),
              Path("/workspace/toolchains/gradle-cache")]
    for cache in caches:
        for jar in sorted(cache.glob("wrapper/dists/gradle-*/**/lib/kotlin-compiler-embeddable-*.jar")):
            libs = jar.parent
            stdlib = next(libs.glob("kotlin-stdlib-[0-9]*.jar"))
            return [java, "-cp", str(libs / "*"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"], str(stdlib), java
    raise SystemExit("NOT_RUN: install kotlinc or point GRADLE_USER_HOME at an existing Gradle distribution")


def evaluate(label, revision, output, traces):
    compiler_command, stdlib, java = compiler()
    work = output / label
    work.mkdir(parents=True, exist_ok=True)
    source_dir = work / "sources"
    source_dir.mkdir(exist_ok=True)
    sources = []
    hashes = {}
    for relative in SOURCES:
        if revision:
            result = subprocess.run(["git", "show", f"{revision}:{relative}"], cwd=ROOT, capture_output=True)
            if result.returncode:
                if relative.endswith("StablePersonMatcher.kt"):
                    continue  # Baseline defines this class inside TrackingMath.kt.
                raise SystemExit(result.stderr.decode())
            content = result.stdout
        else:
            source = ROOT / relative
            if not source.exists() and relative.endswith("StablePersonMatcher.kt"):
                continue
            content = source.read_bytes()
        source = source_dir / Path(relative).name
        source.write_bytes(content)
        sources.append(source)
        hashes[relative] = hashlib.sha256(content).hexdigest()
    classes = work / "classes"
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir()
    run(compiler_command + ["-no-stdlib", "-no-reflect", "-classpath", stdlib,
                           "-jvm-target", "17", "-d", classes, *sources, HARNESS])
    reports = work / "reports"
    run([java, "-cp", f"{classes}{os.pathsep}{stdlib}",
         "com.caneretabito.motionpin.validation.TrackingScenarioEvalKt", reports, *traces])
    with (reports / "summary.csv").open() as handle:
        rows = list(csv.DictReader(handle))
    provenance = {"revision": revision or "working_tree", "source_sha256": hashes,
                  "harness_sha256": hashlib.sha256(HARNESS.read_bytes()).hexdigest(),
                  "evaluation_kind": "annotated_detection_trace" if traces else "synthetic_detection_trace",
                  "detector_inference_executed": False, "android_executed": False,
                  "metrics": rows}
    (reports / "summary.json").write_text(json.dumps(provenance, indent=2) + "\n")
    return provenance


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", help="Git revision to compare (for example cfc8197)")
    parser.add_argument("--trace", action="append", default=[], type=Path,
                        help="Annotated CSV input; repeat for multiple clips. Default: deterministic synthetic suite")
    parser.add_argument("--output", type=Path, default=ROOT / "build/tracking-evaluation")
    parser.add_argument("--check", action="store_true", help="Enforce deterministic synthetic regression gates")
    args = parser.parse_args()
    traces = [x.resolve() for x in args.trace]
    if args.check and traces:
        parser.error("--check applies only to the built-in synthetic suite; annotate real-clip acceptance criteria separately")
    output = args.output.resolve()
    current = evaluate("current", None, output, traces)
    result = {"current": current}
    if args.baseline:
        revision = subprocess.check_output(["git", "rev-parse", "--verify", f"{args.baseline}^{{commit}}"], cwd=ROOT, text=True).strip()
        result["baseline"] = evaluate("baseline", revision, output, traces)
    (output / "comparison.json").write_text(json.dumps(result, indent=2) + "\n")
    for label, evaluation in result.items():
        rows = evaluation["metrics"]
        print(f"{label}: {len(rows)} scenarios, false associations={sum(int(r['false_matches']) for r in rows)}, "
              f"ID switches={sum(int(r['id_switches']) for r in rows)}")
    if args.check:
        rows = {row["scenario"]: row for row in current["metrics"]}
        checks = {
            "no_false_associations": all(int(row["false_matches"]) == 0 for row in rows.values()),
            "full_group_continuity": all(rows[f"group_{count}"]["correct_matches"] == rows[f"group_{count}"]["observations"]
                                         for count in (2, 4, 8, 12)),
            "dense_sampling_recovers": rows["dense_sampling_short_occlusion"]["correct_matches"] == "7",
            "seek_requires_reselection": rows["seek_without_reselection"]["lost"] == "1",
            "full_body_approach_contained": rows["rapid_subject_approach"]["camera_cropped_samples"] == "0",
        }
        (output / "regression-gates.json").write_text(json.dumps(checks, indent=2) + "\n")
        if not all(checks.values()):
            raise SystemExit("FAIL: " + ", ".join(key for key, passed in checks.items() if not passed))
        print(f"PASS: {len(checks)} synthetic regression gates")
    print(f"Report: {output / 'comparison.json'}")


if __name__ == "__main__":
    main()
