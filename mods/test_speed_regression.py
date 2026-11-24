"""Compile and run the cross-controller speed regression harness (red before the fix).

Usage:
  python mods/test_speed_regression.py --tools ../cesium-android-min/tools
  python mods/test_speed_regression.py --tools ../cesium-android-min/tools --compile-only
"""
import argparse
import json
import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parent          # mods
project = root.parent                           # aspatoys-android-visual
work = project.parent                           # sibling checkouts live here

parser = argparse.ArgumentParser()
parser.add_argument('--tools', required=True, type=Path)
parser.add_argument('--compile-only', action='store_true')
parser.add_argument('--legacy', type=Path, help='Optional legacy SpeedHackMod source directory')
args = parser.parse_args()

tools = args.tools.resolve()
out = project / 'build/harness-verification/speed-regression'
out.mkdir(parents=True, exist_ok=True)

legacy = args.legacy or work / 'cesium-android-publish-050/src/SpeedHackMod'
sources = [
    root / 'tests/speedregression/Stubs.cs',
    root / 'tests/speedregression/Legacy.cs',
    root / 'tests/speedregression/Program.cs',
    root / 'CesiumLoader.Mobile.API/AndroidApi.cs',
    root / 'CesiumOverlayMod/OverlayTheme.cs',
    root / 'CesiumOverlayMod/UnitySpeedScale.cs',
    root / 'CesiumOverlayMod/AbsoluteSpeedScale.cs',
    root / 'CesiumOverlayMod/FixedStepBudget.cs',
    root / 'CesiumOverlayMod/BuiltinActions.cs',
    root / 'CesiumOverlayMod/OverlayPresentation.cs',
    root / 'CesiumOverlayMod/HudVisibility.cs',
    root / 'CesiumOverlayMod/LocalSpeed.cs',
    root / 'CesiumOverlayMod/SpeedPanel.cs',
    root / 'CesiumOverlayMod/DockState.cs',
    legacy / 'ModEntry.cs',
    legacy / 'UnitySpeedScale.cs',
    legacy / 'FixedStepBudget.cs',
]
missing = [str(path) for path in sources if not path.exists()]
if missing:
    sys.exit('missing sources:\n' + '\n'.join(missing))

options = [
    '-nologo', '-nostdlib+', '-target:exe', '-langversion:latest',
    '-main:SpeedRegression.Program',
    '-out:' + str(out / 'SpeedRegression.dll'),
]
options += ['-r:' + str(path.resolve()) for path in (tools / 'net8ref/ref/net8.0').glob('*.dll')]
options += [str(path) for path in sources]
response = out / 'compile.rsp'
response.write_text('\n'.join('"' + option.replace('"', '') + '"' for option in options), encoding='utf-8')

compile_result = subprocess.run(
    ['dotnet', str(tools / 'csc/tasks/netcore/bincore/csc.dll'), '@' + str(response)],
    capture_output=True, text=True, encoding='utf-8', errors='replace')
print(compile_result.stdout, end='')
if compile_result.stderr:
    print(compile_result.stderr, end='', file=sys.stderr)
if compile_result.returncode != 0:
    sys.exit('compile failed (exit %d)' % compile_result.returncode)

(out / 'SpeedRegression.runtimeconfig.json').write_text(
    '{"runtimeOptions":{"tfm":"net8.0","framework":{"name":"Microsoft.NETCore.App","version":"8.0.0"}}}',
    encoding='utf-8')

if args.compile_only:
    print('compiled: %s' % (out / 'SpeedRegression.dll'))
    sys.exit(0)

sandbox = out / 'sandbox'
# Ask the harness itself for its scenario list so the runner and the checks cannot drift.
listing = subprocess.run(
    ['dotnet', str(out / 'SpeedRegression.dll'), 'list'],
    capture_output=True, text=True, encoding='utf-8', errors='replace')
if listing.returncode != 0:
    sys.exit('could not list scenarios:\n' + listing.stdout + listing.stderr)
scenarios = [line.strip() for line in listing.stdout.splitlines() if line.strip()]

report = {'dll': str(out / 'SpeedRegression.dll'), 'scenarios': [], 'red': False}
print()
for scenario in scenarios:
    result = subprocess.run(
        ['dotnet', str(out / 'SpeedRegression.dll'), scenario, str(sandbox)],
        capture_output=True, text=True, encoding='utf-8', errors='replace')
    console = result.stdout
    if result.stderr:
        console += result.stderr
    print(console, end='')
    failures = [line for line in console.splitlines() if line.startswith('FAIL ')]
    passed = result.returncode == 0 and 'RESULT %s PASS' % scenario in console
    report['scenarios'].append({
        'scenario': scenario,
        'passed': passed,
        'exit': result.returncode,
        'failures': failures,
        'console': console,
    })
    if not passed:
        report['red'] = True

passed_count = sum(1 for item in report['scenarios'] if item['passed'])
report['passedCount'] = passed_count
report['total'] = len(report['scenarios'])
report_path = project / 'build/harness-verification/speed-regression-report.json'
report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')

print()
print('SUMMARY %d/%d scenarios green; report: %s' % (passed_count, len(report['scenarios']), report_path))
if report['red']:
    failed = [item['scenario'] for item in report['scenarios'] if not item['passed']]
    print('RED scenarios: ' + ', '.join(failed))
    sys.exit(1)
