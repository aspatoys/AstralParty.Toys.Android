"""Run the production overlay protocol/presentation checks with local .NET reference assemblies."""
import argparse
import json
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--compiler', type=Path, required=True, help='Roslyn csc.dll')
parser.add_argument('--refs', type=Path, required=True, help='.NET reference assembly directory')
args = parser.parse_args()
here = Path(__file__).resolve().parent
repo = here.parents[2]
output = repo / 'build/overlay-options-tests'
output.mkdir(parents=True, exist_ok=True)
references = sorted(args.refs.glob('*.dll'))
if not args.compiler.is_file() or not references:
    raise SystemExit('Provide an existing compiler and .NET reference assemblies.')
assembly = output / 'OverlayOptionsTests.dll'
sources = [repo / 'mods/CesiumOverlayMod/OverlayOptions.cs',
           repo / 'mods/CesiumOverlayMod/OverlayPresentation.cs', here / 'OverlayOptionsTests.cs']
options = ['-nologo', '-nostdlib+', '-langversion:latest', '-target:exe', '-out:' + str(assembly)]
options += ['-r:' + str(path.resolve()) for path in references]
options += [str(path) for path in sources]
response = output / 'compile.rsp'
response.write_text('\n'.join('"' + option + '"' for option in options), encoding='utf-8')
subprocess.run(['dotnet', str(args.compiler.resolve()), '@' + str(response)], check=True)
assembly.with_suffix('.runtimeconfig.json').write_text(json.dumps({'runtimeOptions': {
    'tfm': 'net8.0', 'framework': {'name': 'Microsoft.NETCore.App', 'version': '8.0.0'}}}), encoding='utf-8')
raise SystemExit(subprocess.run(['dotnet', str(assembly)]).returncode)
