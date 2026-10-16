#!/usr/bin/env bash
# Verified local-build model preparation only. App has no INTERNET permission.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
dest="$root/app/src/main/assets/models/tracker"
base="https://storage.googleapis.com/mediapipe-models/object_detector"
mkdir -p "$dest"
command -v curl >/dev/null || { echo "curl required for first-time offline model preparation" >&2; exit 1; }
verify() {
    local name="$1" sha="$2" bytes="$3"
    local path="$dest/$name.tflite"
    [[ -f "$path" ]] || return 1
    [[ "$(stat -c '%s' "$path")" == "$bytes" ]] || return 1
    printf '%s  %s\n' "$sha" "$path" | sha256sum --check --status
}
fetch() {
    local name="$1" sha="$2" bytes="$3"
    if verify "$name" "$sha" "$bytes"; then echo "Verified existing offline detector: $name"; return; fi
    if [[ -f "$dest/$name.tflite" ]]; then
        echo "Existing $name model mismatches pinned hash or size. Refusing silent replacement." >&2; exit 1
    fi
    local temp
    temp="$(mktemp "$dest/$name.download.XXXXXX")"
    trap 'rm -f "$temp"' RETURN
    curl --fail --location --retry 3 --connect-timeout 20 --max-time 300 \
        "$base/$name/float32/1/$name.tflite" --output "$temp"
    [[ "$(stat -c '%s' "$temp")" == "$bytes" ]] || { echo "Model size mismatch: $name" >&2; exit 1; }
    printf '%s  %s\n' "$sha" "$temp" | sha256sum --check --status ||
        { echo "Model hash mismatch: $name" >&2; exit 1; }
    mv "$temp" "$dest/$name.tflite"
    trap - RETURN
    echo "Verified downloaded offline detector: $name"
}
fetch efficientdet_lite0 40338edf5ec70d43e318b0a716a84d4564cd1802759a7a07170c7e43796dbf58 13836895
fetch efficientdet_lite2 ad2abbf2b4e10585e15176fd7b5ef03c28dda959ae26fc142549fdd1814db91d 23096891
