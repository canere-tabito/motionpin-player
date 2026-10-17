#!/usr/bin/env python3
"""Reproduce alpha1's real R8/protobuf crash and test the app's current keep rules.

Uses actual cached MediaPipe/protobuf classes and AGP's bundled R8, not mocks.
The JVM classfile backend exercises schema reflection; Android JNI/runtime and
the APK's DEX still require the separate release-byte gate/device tests.
No downloads, APK generation, signing, or source mutation are performed.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import zipfile


def unique_artifact(cache, relative):
    matches = list(cache.glob(relative))
    if len(matches) != 1:
        raise ValueError(f"Expected exactly one pinned artifact {relative}, found {len(matches)}")
    return matches[0]


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, default=Path(os.environ.get(
        "MOTIONPIN_JAVA_HOME", "/workspace/toolchains/jdk-21.0.12.1+1")))
    parser.add_argument("--gradle-cache", type=Path, default=Path(os.environ.get(
        "GRADLE_USER_HOME", "/workspace/toolchains/gradle-cache")))
    parser.add_argument("--output", type=Path, default=root / "artifacts/release-regression/r8-probe")
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    cache = args.gradle_cache / "caches/modules-2/files-2.1"
    core = unique_artifact(cache, "com.google.mediapipe/tasks-core/1.1.0/*/*.aar")
    protobuf = unique_artifact(cache, "com.google.protobuf/protobuf-javalite/4.26.1/*/*.jar")
    r8 = unique_artifact(cache, "com.android.tools.build/builder/8.9.2/*/builder-8.9.2.jar")
    java = str(args.java_home / "bin/java")
    report = {
        "scope": "Real R8 classfile/protobuf host regression; NOT Android JNI/device execution",
        "inputs": {p.name: sha256(p) for p in (core, protobuf, r8)},
        "keepRulesSha256": sha256(root / "app/proguard-rules.pro"),
    }

    def run(command, log_name, check=True):
        completed = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        (output / log_name).write_text(completed.stdout)
        if check and completed.returncode:
            raise RuntimeError(f"Command failed ({completed.returncode}); see {output / log_name}")
        return completed

    version = run([java, "-cp", str(r8), "com.android.tools.r8.R8", "--version"], "r8-version.log")
    report["r8Version"] = version.stdout.strip()
    # Only the unmodified generated logging protos are needed; avoiding Android
    # UI/native classes allows the exact failing protobuf path to run on the JVM.
    protos = output / "logging-protos.jar"
    with zipfile.ZipFile(core) as aar, zipfile.ZipFile(io.BytesIO(aar.read("classes.jar"))) as classes:
        with zipfile.ZipFile(protos, "w") as target:
            for name in classes.namelist():
                if name.startswith("com/google/mediapipe/proto/MediaPipeLogging") and name.endswith(".class"):
                    target.writestr(name, classes.read(name))
    classes_dir = output / "probe-classes"
    classes_dir.mkdir(exist_ok=True)
    run([str(args.java_home / "bin/javac"), "--release", "17", "-cp",
         os.pathsep.join(map(str, (protos, protobuf))), "-d", str(classes_dir),
         str(root / "scripts/release-regression/MediaPipeProtoProbe.java")], "javac.log")
    probe = output / "probe.jar"
    run([str(args.java_home / "bin/jar"), "cf", str(probe), "-C", str(classes_dir), "."], "jar.log")
    baseline_rules = (
        "-keep class MediaPipeProtoProbe { public static void main(java.lang.String[]); }\n"
        "-dontwarn javax.annotation.**\n-dontwarn com.google.errorprone.annotations.**\n")
    results = {}
    for mode in ("baseline", "fixed"):
        rules = output / f"{mode}.pro"
        rules.write_text(baseline_rules + ("\n" + (root / "app/proguard-rules.pro").read_text()
                                           if mode == "fixed" else ""))
        optimized = output / f"{mode}.jar"
        run([java, "-cp", str(r8), "com.android.tools.r8.R8", "--classfile", "--release",
             "--lib", str(args.java_home), "--pg-conf", str(rules), "--output", str(optimized),
             "--pg-map-output", str(output / f"{mode}.map"), str(probe), str(protos), str(protobuf)],
            f"{mode}-build.log")
        executed = run([java, "-cp", str(optimized), "MediaPipeProtoProbe"], f"{mode}-run.log", check=False)
        results[mode] = {"exitCode": executed.returncode, "output": executed.stdout.strip()}
    if results["baseline"]["exitCode"] == 0 or "Field platform_" not in results["baseline"]["output"]:
        raise AssertionError("Baseline did not reproduce the known reflective-field crash; inspect logs")
    if results["fixed"]["exitCode"] != 0 or "PASS real SystemInfo" not in results["fixed"]["output"]:
        raise AssertionError("Current app keep rules failed real protobuf build/serialize/parse")
    report.update({"status": "PASS", "baselineRegressionReproduced": True, "runs": results})
    (output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
