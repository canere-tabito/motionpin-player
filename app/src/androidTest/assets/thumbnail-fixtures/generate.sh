#!/usr/bin/env bash
# Original lavfi artwork, no downloaded footage. Run from any directory.
# Rebuild only these tiny owned test assets; no app/user video is read or modified.
set -euo pipefail
fixture_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i 'color=c=black:s=320x180:r=12:d=0.5' \
  -f lavfi -i 'testsrc2=s=320x180:r=12:d=3.5' \
  -filter_complex '[0:v][1:v]concat=n=2:v=1:a=0[out]' -map '[out]' \
  -an -c:v libx264 -preset veryslow -crf 30 -profile:v baseline -level 3.0 \
  -pix_fmt yuv420p -g 6 -keyint_min 6 -sc_threshold 0 -bf 0 \
  -map_metadata -1 -fflags +bitexact -flags:v +bitexact \
  -movflags +faststart "$fixture_dir/black-lead.mp4"
ffmpeg -hide_banner -loglevel error -y -i "$fixture_dir/black-lead.mp4" \
  -map 0:v:0 -c copy -an -map_metadata -1 -fflags +bitexact \
  "$fixture_dir/black-lead.mkv"
python3 - "$fixture_dir" <<'PY'
import hashlib, json, pathlib, subprocess, sys
root = pathlib.Path(sys.argv[1])
entries = []
for name in ('black-lead.mp4', 'black-lead.mkv'):
    path = root / name
    probe = json.loads(subprocess.check_output([
        'ffprobe', '-v', 'error', '-show_entries',
        'stream=codec_name,width,height,avg_frame_rate,nb_frames:format=duration',
        '-of', 'json', str(path)
    ]))
    entries.append({'file': name, 'bytes': path.stat().st_size,
                    'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'ffprobe': probe})
manifest = {'provenance': 'Original ffmpeg lavfi black lead and testsrc2 artwork. No external footage.',
            'purpose': 'Android thumbnail fallback/container regression; not playback/HDR quality evidence.',
            'generator': 'generate.sh',
            'ffmpeg_version': subprocess.check_output(['ffmpeg', '-version'], text=True).splitlines()[0],
            'assets': entries}
(root / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(json.dumps(entries, indent=2))
PY
