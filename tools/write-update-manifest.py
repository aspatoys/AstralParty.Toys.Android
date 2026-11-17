"""Create the static Android release manifest consumed by in-app updates."""
import argparse
import hashlib
import json
from pathlib import Path
import re

parser = argparse.ArgumentParser()
parser.add_argument('--version', required=True)
parser.add_argument('--apk', required=True, type=Path)
parser.add_argument('--out', required=True, type=Path)
args = parser.parse_args()
if not re.fullmatch(r'\d+\.\d+\.\d+(?:-dev\.\d+)?', args.version):
    raise SystemExit('A major.minor.patch release or CI dev version is required.')
name = f'aspatoys-{args.version}.apk'
if args.apk.name != name:
    raise SystemExit(f'Expected APK filename {name}.')
repo = 'higashitaniyume/AstralParty.Toys.Android'
manifest = {
    'version': args.version,
    'releaseTag': f'v{args.version}',
    'releaseNotes': '',
    'packages': {'androidApk': {
        'fileName': name,
        'url': f'https://github.com/{repo}/releases/download/v{args.version}/{name}',
        'sha256': hashlib.sha256(args.apk.read_bytes()).hexdigest(),
        'size': args.apk.stat().st_size,
    }},
}
args.out.parent.mkdir(parents=True, exist_ok=True)
args.out.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
