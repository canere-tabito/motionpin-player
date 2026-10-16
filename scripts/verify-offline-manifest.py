#!/usr/bin/env python3
"""Fail closed if a merged application manifest permits network/telemetry upload."""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID_NAME = "{http://schemas.android.com/apk/res/android}name"
TRANSPORT_PREFIX = "com.google.android.datatransport."


def verify(path: Path) -> None:
    manifest = ET.parse(path).getroot()
    for element in manifest.iter():
        name = element.get(ANDROID_NAME, "")
        if element.tag.startswith("uses-permission") and name == "android.permission.INTERNET":
            raise ValueError("Merged manifest permits INTERNET; offline contract violated")
        if element.tag in {"service", "receiver", "provider"} and name.startswith(TRANSPORT_PREFIX):
            raise ValueError(f"Merged manifest enables telemetry component: {name}")
    print(f"PASS offline manifest: {path} (no INTERNET or DataTransport upload components)")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: verify-offline-manifest.py MERGED_ANDROID_MANIFEST.xml")
    try:
        verify(Path(sys.argv[1]))
    except (OSError, ValueError, ET.ParseError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
