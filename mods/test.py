"""Run managed regression checks with a .NET Runtime and a Roslyn tool directory."""
import argparse
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent
parser = argparse.ArgumentParser()
parser.add_argument('--tools', required=True, type=Path)
args = parser.parse_args()
out = root.parent / 'build/overlay-tests'
out.mkdir(parents=True, exist_ok=True)
sources = [root / name for name in ('tests/Program.cs', 'tests/UnityStubs.cs',
           'CesiumAndroidApi/AndroidApi.cs', 'CesiumOverlayMod/OverlayTheme.cs',
           'CesiumOverlayMod/UnitySpeedScale.cs', 'CesiumOverlayMod/FixedStepBudget.cs')]
options = ['-nologo', '-nostdlib+', '-target:exe', '-out:' + str(out / 'OverlayTests.dll')]
options += ['-r:' + str(p.resolve()) for p in (args.tools / 'net8ref/ref/net8.0').glob('*.dll')]
options += [str(p) for p in sources]
response = out / 'compile.rsp'
response.write_text('\n'.join('"' + option + '"' for option in options), encoding='utf-8')
subprocess.run(['dotnet', str(args.tools / 'csc/tasks/netcore/bincore/csc.dll'), '@' + str(response)], check=True)
(out / 'OverlayTests.runtimeconfig.json').write_text('{"runtimeOptions":{"tfm":"net8.0","framework":{"name":"Microsoft.NETCore.App","version":"8.0.0"}}}')
subprocess.run(['dotnet', str(out / 'OverlayTests.dll'), str(out / 'sandbox')], check=True)
