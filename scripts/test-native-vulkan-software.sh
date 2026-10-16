#!/usr/bin/env bash
# Optional Linux x86_64 shader-path validation. This is software Vulkan, not a GPU benchmark.
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
driver_dir="${MOTIONPIN_SOFTWARE_VULKAN_DIR:-${root}/artifacts/native-host/mesa}"
mkdir -p "$driver_dir"
package="$driver_dir/mesa-vulkan-drivers.deb"
expected=caf2f7d296b7522efe74e40a74bce762a3ce84c33b53b5e54e1248ac2e0a13a5
if [[ ! -f "$package" ]] || [[ "$(sha256sum "$package" | cut -d' ' -f1)" != "$expected" ]]; then
    curl --fail --silent --show-error --location --proto '=https' \
        'https://deb.debian.org/debian/pool/main/m/mesa/mesa-vulkan-drivers_25.0.7-2+deb13u1_amd64.deb' \
        --output "$package.partial"
    printf '%s  %s\n' "$expected" "$package.partial" | sha256sum --check
    mv "$package.partial" "$package"
fi
dpkg-deb -x "$package" "$driver_dir"
export VK_ICD_FILENAMES="$driver_dir/usr/share/vulkan/icd.d/lvp_icd.json"
export LD_LIBRARY_PATH="$driver_dir/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export LP_NUM_THREADS=3
export MESA_SHADER_CACHE_DISABLE=true
export MOTIONPIN_TEST_VULKAN=1
"$root/scripts/test-native-host.sh"
python3 - "$root" <<'PY'
import json
from pathlib import Path
import sys
evidence = Path(sys.argv[1]) / 'artifacts/native-host'
results = []
for name in ['quarter', 'midpoint']:
    cpu = (evidence / f'rife-{name}.ppm').read_bytes().split(b'\n', 3)[3]
    gpu = (evidence / f'rife-vulkan-{name}.ppm').read_bytes().split(b'\n', 3)[3]
    if len(cpu) != 65 * 49 * 3 or len(gpu) != len(cpu):
        raise SystemExit('Output dimensions do not match')
    difference = [abs(a - b) for a, b in zip(cpu, gpu)]
    results.append(dict(frame=name, shape=[49, 65, 3],
        differingChannelValues=sum(value != 0 for value in difference),
        maxAbsoluteRgb8Difference=max(difference),
        meanAbsoluteRgb8Difference=sum(difference) / len(difference)))
    if max(difference) > 1:
        raise SystemExit('CPU / software Vulkan difference exceeds one RGB8 code value')
(evidence / 'cpu-vulkan-comparison.json').write_text(json.dumps(results, indent=2) + '\n')
print('PASS FP32 CPU/software-Vulkan comparison; max RGB8 difference <= 1')
PY
