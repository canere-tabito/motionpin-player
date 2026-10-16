#!/usr/bin/env bash
set -euo pipefail
# Canonical signing only. Never generate a key here or print a password.
: "${ANDROID_HOME:?Set ANDROID_HOME to the installed SDK}"
: "${MOTIONPIN_KEYSTORE:?Set the secure canonical keystore file path}"
: "${MOTIONPIN_KEY_ALIAS:?Set the canonical key alias}"
: "${MOTIONPIN_STORE_PASS_FILE:?Set a permission-restricted password file path}"
: "${MOTIONPIN_KEY_PASS_FILE:?Set a permission-restricted password file path}"
: "${MOTIONPIN_CERT_SHA256:?Set the independently recorded expected certificate SHA256}"
[[ ${MOTIONPIN_KEY_BACKUP_CONFIRMED:-} == yes ]] || { echo 'Canonical key durable backup has not been confirmed.' >&2; exit 1; }
unsigned=${1:?Usage: scripts/sign-release.sh unsigned.apk output.apk}
signed=${2:?Specify a new output APK path}
[[ ! -e "$signed" ]] || { echo 'Refusing to overwrite an existing artifact.' >&2; exit 1; }
build_tools="$ANDROID_HOME/build-tools/35.0.0"
for secret_file in "$MOTIONPIN_KEYSTORE" "$MOTIONPIN_STORE_PASS_FILE" "$MOTIONPIN_KEY_PASS_FILE"; do
  [[ -f "$secret_file" ]] || { echo 'A signing input file is missing.' >&2; exit 1; }
  mode=$(stat -c %a "$secret_file")
  (( (8#$mode & 8#077) == 0 )) || { echo 'Signing inputs must not be readable by group or other users.' >&2; exit 1; }
done
if "$build_tools/apksigner" verify "$unsigned" >/dev/null 2>&1; then
  echo 'Input is already signed. Refusing to replace a signature.' >&2
  exit 1
fi
mkdir -p "$(dirname "$signed")"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
"$build_tools/zipalign" -P 16 -f 4 "$unsigned" "$work/aligned.apk"
"$build_tools/apksigner" sign --ks "$MOTIONPIN_KEYSTORE" --ks-key-alias "$MOTIONPIN_KEY_ALIAS" \
  --ks-pass "file:$MOTIONPIN_STORE_PASS_FILE" --key-pass "file:$MOTIONPIN_KEY_PASS_FILE" \
  --out "$work/signed.apk" "$work/aligned.apk"
"$build_tools/apksigner" verify --verbose --print-certs "$work/signed.apk" > "$work/verification.txt"
if rg -q 'CN=Android Debug' "$work/verification.txt"; then
  echo 'A debug certificate cannot sign a distribution artifact.' >&2
  exit 1
fi
actual=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$work/verification.txt" | tr '[:upper:]' '[:lower:]' | tr -d ': ')
expected=$(printf '%s' "$MOTIONPIN_CERT_SHA256" | tr '[:upper:]' '[:lower:]' | tr -d ': ')
[[ ${#expected} == 64 && "$actual" == "$expected" ]] || { echo 'Certificate differs from the expected canonical fingerprint.' >&2; exit 1; }
"$build_tools/zipalign" -c -P 16 -v 4 "$work/signed.apk" > "$work/alignment.txt"
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
python3 "$project_dir/scripts/verify-artifact.py" "$work/signed.apk" --mode release \
  --sdk "$ANDROID_HOME" --expected-certificate-sha256 "$expected" \
  --model-manifest "$project_dir/MODEL_MANIFEST.json" --report "$work/artifact-report.json" >/dev/null
mv "$work/signed.apk" "$signed"
cp "$work/verification.txt" "$signed.signature.txt"
cp "$work/artifact-report.json" "$signed.artifact-report.json"
sha256sum "$signed"
stat -c 'APK bytes: %s' "$signed"
