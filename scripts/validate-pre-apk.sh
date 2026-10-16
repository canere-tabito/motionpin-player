#!/usr/bin/env bash
# Compile all app variants and instrumentation sources, run JVM tests and lint.
# Deliberately no APK, signing key, installation, publishing, or CI operation.
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
logs="${MOTIONPIN_VALIDATION_DIR:-$root/artifacts/validation}"
mkdir -p "$logs"
tasks=(compileDebugKotlin compileReleaseKotlin compileDebugAndroidTestKotlin testDebugUnitTest lintRelease)
args=(--console=plain --init-script "$root/scripts/no-apk.init.gradle" "$@" "${tasks[@]}")
bash scripts/build-local.sh "${args[@]}" --dry-run 2>&1 | tee "$logs/pre-apk-task-graph.log"
bash scripts/build-local.sh "${args[@]}" 2>&1 | tee "$logs/pre-apk-gradle.log"
