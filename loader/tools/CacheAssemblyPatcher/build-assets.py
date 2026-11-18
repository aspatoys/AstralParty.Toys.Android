"""Build the offline browser runtime used by the Android cache editor."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request
from zipfile import ZipFile

PROJECT = Path(__file__).resolve().parent
VERSION = "8.0.33"
PACKAGE = "microsoft.netcore.app.runtime.mono.browser-wasm"
PACKAGE_SHA256 = "5590e796491706bdfeec683520d9c44e4250fa40b0709eb337d9fffaba29391c"
NATIVE = "runtimes/browser-wasm/native/"
MANAGED = "runtimes/browser-wasm/lib/net8.0/"

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--dotnet", default="dotnet")
parser.add_argument("--runtime-package", type=Path)
args = parser.parse_args()
output = args.output.resolve()
output.mkdir(parents=True, exist_ok=True)

with tempfile.TemporaryDirectory() as temporary:
    work = Path(temporary)
    package = args.runtime_package or work / "runtime.nupkg"
    if not args.runtime_package:
        url = f"https://api.nuget.org/v3-flatcontainer/{PACKAGE}/{VERSION}/{PACKAGE}.{VERSION}.nupkg"
        with urllib.request.urlopen(url, timeout=90) as response, package.open("wb") as local:
            shutil.copyfileobj(response, local)
    if hashlib.sha256(package.read_bytes()).hexdigest() != PACKAGE_SHA256:
        raise SystemExit("Browser runtime package hash mismatch")

    build = work / "managed"
    subprocess.run([args.dotnet, "build", str(PROJECT), "-c", "Release", "-o", str(build),
                    "-p:CopyLocalLockFileAssemblies=true", "--nologo"], check=True)
    assemblies = {}

    def take(name, data):
        (output / name).write_bytes(data)
        return "sha256-" + base64.b64encode(hashlib.sha256(data).digest()).decode()

    with ZipFile(package) as runtime:
        for name in runtime.namelist():
            if name.startswith(MANAGED) and name.endswith(".dll"):
                dll = Path(name).name
                assemblies[dll] = take(dll, runtime.read(name))
        assemblies["System.Private.CoreLib.dll"] = take(
            "System.Private.CoreLib.dll", runtime.read(NATIVE + "System.Private.CoreLib.dll")
        )
        native = {name: take(name, runtime.read(NATIVE + name)) for name in (
            "dotnet.js", "dotnet.native.js", "dotnet.runtime.js", "dotnet.native.wasm"
        )}
        for name in ("LICENSE.TXT", "THIRD-PARTY-NOTICES.TXT"):
            take("dotnet-" + name, runtime.read(name))

    for name in ("CacheAssemblyPatcher.dll", "Mono.Cecil.dll"):
        assemblies[name] = take(name, (build / name).read_bytes())
    for name in ("index.html", "worker.mjs", "Mono.Cecil-LICENSE.txt"):
        shutil.copyfile(PROJECT / name, output / name)
    resources = {
        "assembly": assemblies,
        "jsModuleNative": {"dotnet.native.js": native["dotnet.native.js"]},
        "jsModuleRuntime": {"dotnet.runtime.js": native["dotnet.runtime.js"]},
        "wasmNative": {"dotnet.native.wasm": native["dotnet.native.wasm"]},
    }
    config = {"mainAssemblyName": "CacheAssemblyPatcher.dll", "globalizationMode": "invariant",
              "cacheBootResources": False, "enableDownloadRetry": False, "resources": resources}
    (output / "mono-config.json").write_text(json.dumps(config), encoding="utf-8")
    (output / "package.json").write_text('{"type":"module"}\n', encoding="utf-8")
