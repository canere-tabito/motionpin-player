#!/usr/bin/env python3
"""Check signed/unsigned APKs include the exact offline dancer-tracking asset."""
import hashlib
import sys
import zipfile
from pathlib import Path

MODEL = "assets/models/tracker/efficientdet_lite0.tflite"
EXPECTED_SHA256 = "40338edf5ec70d43e318b0a716a84d4564cd1802759a7a07170c7e43796dbf58"
EXPECTED_BYTES = 13836895

def main(apk_path: str) -> int:
    apk = Path(apk_path)
    with zipfile.ZipFile(apk) as archive:
        info = archive.getinfo(MODEL)
        if info.compress_type != zipfile.ZIP_STORED:
            raise ValueError(f"{MODEL} is compressed; MediaPipe asset mapping is not guaranteed")
        if info.file_size != EXPECTED_BYTES:
            raise ValueError(f"tracking model has wrong byte size: {info.file_size}")
        digest = hashlib.sha256()
        with archive.open(info) as stream:
            for chunk in iter(lambda: stream.read(1 << 20), b""):
                digest.update(chunk)
        if digest.hexdigest() != EXPECTED_SHA256:
            raise ValueError(f"tracking model hash mismatch: {digest.hexdigest()}")
    print(f"PASS offline tracking asset: {MODEL}, sha256={EXPECTED_SHA256}")
    return 0

if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: python3 scripts/verify-tracker-apk.py <apk>", file=sys.stderr)
        sys.exit(2)
    try:
        sys.exit(main(sys.argv[1]))
    except (OSError, ValueError, zipfile.BadZipFile, KeyError) as ex:
        print(f"FAIL tracking APK verification: {ex}", file=sys.stderr)
        sys.exit(1)
