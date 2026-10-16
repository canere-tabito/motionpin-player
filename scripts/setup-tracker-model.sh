#!/usr/bin/env bash
# Model retrieval happens only at BUILD time. Runtime uses the APK asset offline.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
target="$root/app/src/main/assets/models/tracker/efficientdet_lite0.tflite"
sha="40338edf5ec70d43e318b0a716a84d4564cd1802759a7a07170c7e43796dbf58"
url="https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float32/1/efficientdet_lite0.tflite"
mkdir -p "$(dirname "$target")"
if [[ -f "$target" ]] && printf '%s  %s\n' "$sha" "$target" | sha256sum -c --status; then
  exit 0
fi
# A stale or wrong model is NEVER silently reused.
if [[ -f "$target" ]]; then echo "Tracking model hash mismatch. Refusing to replace without explicit cleanup." >&2; exit 1; fi
command -v curl >/dev/null || { echo "curl is required to prepare the tracking asset before building" >&2; exit 1; }
temp="$(mktemp "$target.download.XXXXXX")"
trap 'rm -f "$temp"' EXIT
curl --fail --location --retry 3 --connect-timeout 20 --max-time 240 "$url" --output "$temp"
printf '%s  %s\n' "$sha" "$temp" | sha256sum -c --status || { echo "Official model SHA-256 mismatch; no APK can be built" >&2; exit 1; }
mv "$temp" "$target"
trap - EXIT
