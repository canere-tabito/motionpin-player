#!/usr/bin/env python3
"""Small, reviewed safety fixes to pinned native sources; fail on source drift."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parent.parent
lock = json.loads((root / "scripts/native-sources.json").read_text())
for patch in lock["patches"]:
    path = root / patch["path"]
    source = path.read_bytes()
    digest = hashlib.sha256(source).hexdigest()
    if digest == patch["afterSha256"]:
        continue
    if digest != patch["beforeSha256"]:
        raise SystemExit(f"Refusing changed native source: {path}")
    text = source.decode()
    for change in patch["replacements"]:
        if text.count(change["before"]) != change["count"]:
            raise SystemExit(f"Patch context changed: {path}")
        text = text.replace(change["before"], change["after"])
    patched = text.encode()
    if hashlib.sha256(patched).hexdigest() != patch["afterSha256"]:
        raise SystemExit(f"Patched source hash mismatch: {path}")
    path.write_bytes(patched)
print("Native safety patches verified")
