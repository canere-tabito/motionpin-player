#!/usr/bin/env python3
"""Refresh (or verify) cache identity from exact native sources and model hashes."""
import argparse
import hashlib
import json
from pathlib import Path
import re

parser = argparse.ArgumentParser()
parser.add_argument('--check', action='store_true')
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
manifest_path = root / 'MODEL_MANIFEST.json'
manifest = json.loads(manifest_path.read_text())
bridge = root / 'app/src/main/java/com/caneretabito/motionpin/ai/NativeRife.kt'
bridge_text = bridge.read_text()
source_paths = [
    'app/src/main/cpp/CMakeLists.txt', 'app/src/main/cpp/native_rife.cpp',
    'app/src/main/cpp/rife_engine.cpp', 'app/src/main/cpp/rife_engine.h',
    str(bridge.relative_to(root)), 'scripts/native-sources.json',
]
def sha(data):
    return hashlib.sha256(data).hexdigest()
def canonical_sha(value):
    return sha(json.dumps(value, sort_keys=True, separators=(',', ':')).encode())
source_hashes = {}
for source_path in source_paths:
    content = (root / source_path).read_bytes()
    if source_path.endswith('NativeRife.kt'):
        # Avoid a self-referential digest while preserving all executable code.
        content = re.sub(rb'const val (MODEL_HASH|ENGINE_HASH) = "[^"]*"',
                         rb'const val \1 = "<computed>"', content)
    source_hashes[source_path] = sha(content)
model_hash = canonical_sha({item['path']: item['sha256'] for item in manifest['artifacts']})
engine_hash = canonical_sha(source_hashes)
manifest['modelHash'] = model_hash
manifest['engineHash'] = engine_hash
manifest['engineSourceFiles'] = source_hashes
manifest['engineHashMethod'] = 'SHA-256 of canonical sorted JSON sourcePath:SHA256 map; NativeRife MODEL_HASH/ENGINE_HASH literal values normalized to <computed>. Dependency revisions, checksums and patches are included via scripts/native-sources.json.'
for name, value in [('MODEL_HASH', model_hash), ('ENGINE_HASH', engine_hash)]:
    bridge_text = re.sub(rf'const val {name} = "[^"]*"', f'const val {name} = "{value}"', bridge_text)
manifest_text = json.dumps(manifest, indent=2, ensure_ascii=False) + '\n'
if args.check:
    if manifest_text != manifest_path.read_text() or bridge_text != bridge.read_text():
        raise SystemExit('Native source identity changed. Review changes and run scripts/update-native-manifest.py before building.')
else:
    bridge.write_text(bridge_text)
    manifest_path.write_text(manifest_text)
    (root / 'app/src/main/assets/MODEL_MANIFEST.json').write_text(manifest_text)
print(f'Model SHA-256: {model_hash}\nEngine SHA-256: {engine_hash}')
