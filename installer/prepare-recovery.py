"""Stage the pinned, original game cache for offline recovery; game binaries remain ignored build assets."""
import argparse
import hashlib
import io
from pathlib import Path
import urllib.request
import zipfile

ORIGINAL_SHA256 = '77da946bf7d331351a93ff16707aab426f534b7577a771f21267478b7bc70c87'
ARCHIVE_SHA256 = 'a29738326a9f9d68d209e1be8dc776409621bbf1be25d7f21208d78099dc6d83'
URL = 'https://jx.mhpd.fans/loader/android-originals/original-' + ARCHIVE_SHA256 + '.zip'
root = Path(__file__).resolve().parent.parent
target = root / 'app/src/main/assets/cesium/recovery/original.bundle'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--bundle', type=Path, help='Local original __data file')
parser.add_argument('--archive', type=Path, help='Local pinned original ZIP')
parser.add_argument('--download', action='store_true', help='Download the pinned archive when no local bundle is supplied')
args = parser.parse_args()

def digest(data):
    return hashlib.sha256(data).hexdigest()

if args.bundle:
    original = args.bundle.read_bytes()
elif target.is_file() and digest(target.read_bytes()) == ORIGINAL_SHA256:
    original = target.read_bytes()
else:
    if args.archive:
        archive = args.archive.read_bytes()
    elif args.download:
        with urllib.request.urlopen(URL, timeout=90) as response:
            archive = response.read(32 * 1024 * 1024 + 1)
    else:
        raise SystemExit('Provide --bundle, --archive or --download to prepare the offline recovery resource.')
    if digest(archive) != ARCHIVE_SHA256:
        raise SystemExit('Original archive SHA-256 mismatch; existing recovery assets were not changed.')
    with zipfile.ZipFile(io.BytesIO(archive)) as package:
        entries = [name for name in package.namelist() if name.endswith('/__data') or name == '__data']
        if len(entries) != 1:
            raise SystemExit('Original archive must contain exactly one __data entry.')
        original = package.read(entries[0])

if digest(original) != ORIGINAL_SHA256:
    raise SystemExit('Original bundle SHA-256 mismatch; existing recovery assets were not changed.')
target.parent.mkdir(parents=True, exist_ok=True)
temporary = target.with_suffix('.bundle.tmp')
temporary.write_bytes(original)
temporary.replace(target)
print('Offline original ready:', len(original), 'B, sha256=' + ORIGINAL_SHA256)
