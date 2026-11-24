"""Create the static Android release manifest consumed by in-app updates."""
import argparse
import hashlib
import json
from pathlib import Path
import re

parser = argparse.ArgumentParser()
parser.add_argument('--version', required=True)
parser.add_argument('--apk', required=True, type=Path)
parser.add_argument(
    '--managed',
    type=Path,
    help='托管载荷 zip（引导 DLL / SDK / 悬浮窗 API），给不想解包 APK 的下游工具用',
)
parser.add_argument('--out', required=True, type=Path)
args = parser.parse_args()
if not re.fullmatch(r'\d+\.\d+\.\d+(?:-dev\.\d+)?', args.version):
    raise SystemExit('A major.minor.patch release or CI dev version is required.')
name = f'aspatoys-{args.version}.apk'
if args.apk.name != name:
    raise SystemExit(f'Expected APK filename {name}.')
repo = 'higashitaniyume/AstralParty.Toys.Android'


def package(file_name: str, path: Path) -> dict:
    return {
        'fileName': file_name,
        'url': f'https://github.com/{repo}/releases/download/v{args.version}/{file_name}',
        'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
        'size': path.stat().st_size,
    }


manifest = {
    'version': args.version,
    'releaseTag': f'v{args.version}',
    'releaseNotes': '',
    'packages': {'androidApk': package(name, args.apk)},
}
if args.managed is not None:
    managed_name = f'cesium-loader-android-managed-{args.version}.zip'
    if args.managed.name != managed_name:
        raise SystemExit(f'Expected managed payload filename {managed_name}.')
    manifest['packages']['loaderManaged'] = package(managed_name, args.managed)
args.out.parent.mkdir(parents=True, exist_ok=True)
args.out.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
