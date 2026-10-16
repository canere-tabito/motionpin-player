#!/usr/bin/env python3
"""Verify both offline dancer-tracking models in the exact final Android APK."""
import hashlib
import sys
import zipfile
from pathlib import Path

MODELS = {
    "assets/models/tracker/efficientdet_lite0.tflite": (
        13836895, "40338edf5ec70d43e318b0a716a84d4564cd1802759a7a07170c7e43796dbf58"
    ),
    "assets/models/tracker/efficientdet_lite2.tflite": (
        23096891, "ad2abbf2b4e10585e15176fd7b5ef03c28dda959ae26fc142549fdd1814db91d"
    ),
}

def verify(apk_path: str) -> None:
    with zipfile.ZipFile(Path(apk_path)) as archive:
        for filename, (length, expected_hash) in MODELS.items():
            info = archive.getinfo(filename)
            if info.compress_type != zipfile.ZIP_STORED:
                raise ValueError(f"{filename} must not be compressed for mapped model loading")
            if info.file_size != length:
                raise ValueError(f"{filename} unexpected size: {info.file_size} != {length}")
            digest = hashlib.sha256()
            with archive.open(info) as stream:
                for chunk in iter(lambda: stream.read(1 << 20), b""):
                    digest.update(chunk)
            if digest.hexdigest() != expected_hash:
                raise ValueError(f"{filename} sha256 mismatch")
            print(f"PASS {filename}: size={length}, sha256={expected_hash}")

if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: verify-tracker-apk.py APK_PATH", file=sys.stderr)
        sys.exit(2)
    try:
        verify(sys.argv[1])
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as err:
        print(f"FAIL tracker models: {err}", file=sys.stderr)
        sys.exit(1)
