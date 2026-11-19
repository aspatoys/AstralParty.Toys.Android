"""Extract only verified v0.5.0 channel runtime references into APK assets."""
import argparse
import hashlib
from io import BytesIO
import json
from pathlib import Path
import re
from zipfile import ZipFile

SOURCE = "https://github.com/Aperfish/CesiumLoader-Android-Direct/releases/tag/v0.5.0"
APK_HASH = "3e658b387cbf56e7d2ef0be472816fb2e0355059ff7c3b899d7029c0d8ec8f9d"
HASH = re.compile(r"[a-f0-9]{64}")
ID = re.compile(r"[a-f0-9]{32}")

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--apk", type=Path, required=True)
parser.add_argument("--output", type=Path, default=Path("app/src/main/assets/cesium/channel"))
args = parser.parse_args()

if hashlib.sha256(args.apk.read_bytes()).hexdigest() != APK_HASH:
    raise SystemExit("Unexpected Android Direct v0.5.0 APK digest")

with ZipFile(args.apk) as apk, ZipFile(BytesIO(apk.read("assets/payload.zip"))) as payload:
    index = json.loads(payload.read("manifest.json"))
    if index["version"] != "0.5.0" or index["channelCompatibility"] != 1 or len(index["profiles"]) != 4:
        raise SystemExit("Unexpected channel reference index")
    files = {}
    profiles = []
    for profile in index["profiles"]:
        identifier = profile["id"]
        ref = profile["channelRuntime"]
        if not ID.fullmatch(identifier) or not HASH.fullmatch(ref["originalSha256"]) or identifier in files:
            raise SystemExit("Invalid channel reference ID or digest")
        dll = payload.read(f"compatibility/{identifier}/runtime.patched.dll")
        if dll[:2] != b"MZ" or hashlib.sha256(dll).hexdigest() != ref["sha256"]:
            raise SystemExit(f"Channel reference digest mismatch: {identifier}")
        files[identifier] = dll
        profiles.append({
            "id": identifier, "originalSha256": ref["originalSha256"],
            "originalBytes": ref["originalBytes"], "patchedSha256": ref["sha256"],
        })

args.output.mkdir(parents=True, exist_ok=True)
for identifier, dll in files.items():
    (args.output / f"{identifier}.dll").write_bytes(dll)
(args.output / "references.json").write_text(
    json.dumps({"source": SOURCE, "profiles": profiles}, indent=2) + "\n", encoding="utf-8"
)
print(f"Verified and staged {len(profiles)} channel references from Android Direct v0.5.0")
