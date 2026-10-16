#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 - <<'PY'
import hashlib
import json
import shutil
import subprocess
from pathlib import Path

root = Path.cwd()
lock = json.loads((root / 'scripts/native-sources.json').read_text())
cache = root / 'third_party/.downloads'
cache.mkdir(parents=True, exist_ok=True)

def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()

def fetch(url, dest, expected):
    if dest.is_file() and sha(dest) == expected:
        return
    dest.parent.mkdir(parents=True, exist_ok=True)
    temporary = dest.with_suffix(dest.suffix + '.partial')
    subprocess.run(['curl', '--fail', '--show-error', '--silent', '--location', '--retry', '3',
                    '--proto', '=https', '--tlsv1.2', url, '--output', str(temporary)], check=True)
    if sha(temporary) != expected:
        temporary.unlink(missing_ok=True)
        raise SystemExit(f'Checksum mismatch for {url}')
    temporary.replace(dest)

for archive in lock['archives']:
    tarball = cache / archive['name']
    fetch(archive['url'], tarball, archive['sha256'])
    destination = root / archive['destination']
    marker = destination / '.motionpin-source-sha256'
    if marker.is_file() and marker.read_text().strip() == archive['sha256']:
        continue
    destination.mkdir(parents=True, exist_ok=True)
    # Do not import upstream workflows into the project or run any CI.
    subprocess.run(['tar', '-xzf', str(tarball), '--strip-components=1',
                    '--exclude=*/.github', '-C', str(destination)], check=True)
    marker.write_text(archive['sha256'] + '\n')

for source in lock['files']:
    # Patched files are verified by patch-native.py, not downloaded again.
    dest = root / source['destination']
    patched = next((p for p in lock['patches'] if p['path'] == source['destination']), None)
    if patched and dest.is_file() and sha(dest) == patched['afterSha256']:
        continue
    fetch(source['url'], dest, source['sha256'])

subprocess.run(['python3', 'scripts/patch-native.py'], check=True)
subprocess.run(['python3', 'scripts/update-native-manifest.py', '--check'], check=True)
manifest = json.loads((root / 'MODEL_MANIFEST.json').read_text())
for artifact in manifest['artifacts']:
    dest = root / 'app/src/main' / artifact['path']
    if not dest.is_file() or sha(dest) != artifact['sha256'] or dest.stat().st_size != artifact['sizeBytes']:
        raise SystemExit(f'Model verification failed: {dest}')
shutil.copyfile(root / 'MODEL_MANIFEST.json', root / 'app/src/main/assets/MODEL_MANIFEST.json')
shutil.copyfile(root / 'THIRD_PARTY_NOTICES.txt', root / 'app/src/main/assets/THIRD_PARTY_NOTICES.txt')
print('Pinned RIFE v4.6 / ncnn / glslang ready; model hashes verified')
PY
