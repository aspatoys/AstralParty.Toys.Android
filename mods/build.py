"""Build the in-game overlay without replacing the official SDK or game payload."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent
parser = argparse.ArgumentParser()
parser.add_argument('--refs', required=True, type=Path, help='Game reference DLL directory')
parser.add_argument('--tools', type=Path, help='Optional csc/tasks + netstandard/build tool directory')
parser.add_argument('--overlay-only', action='store_true', help='Rebuild only the overlay; keep the shipped API, speed adapter and camera unchanged')
parser.add_argument('--sdk', type=Path, help='Optional official SDK DLL path')
args = parser.parse_args()
assets = ROOT.parent / 'app/src/main/assets/cesium'
sdk = args.sdk or assets / 'sdk/CesiumLoader.SDK.dll'
out = ROOT.parent / 'build/builtin-overlay'
out.mkdir(parents=True, exist_ok=True)

if args.tools:
    standard = list((args.tools / 'netstandard/build/netstandard2.0/ref').glob('*.dll'))
    if not standard:
        raise SystemExit('Missing netstandard2.0 reference assemblies')
    compiler = args.tools / 'csc/tasks/netcore/bincore/csc.dll'
    def compile_mod(name, references):
        target = out / (name + '.dll')
        options = ['-nologo', '-nostdlib+', '-langversion:latest', '-deterministic+', '-target:library', '-out:' + str(target)]
        options += ['-r:' + str(p.resolve()) for p in standard + references]
        options += [str(p) for p in (ROOT / name).glob('*.cs')]
        response = out / (name + '.rsp')
        response.write_text('\n'.join('"' + value + '"' for value in options), encoding='utf-8')
        subprocess.run(['dotnet', str(compiler), '@' + str(response)], check=True)
        return target
    api = assets / 'CesiumAndroidApi.dll' if args.overlay_only else compile_mod('CesiumAndroidApi', [args.refs / 'UnityEngine.CoreModule.dll'])
    overlay = compile_mod('CesiumOverlayMod', [sdk, api] + [args.refs / name for name in (
        'UnityEngine.CoreModule.dll', 'AstralParty.Runtime.dll')])
    if not args.overlay_only:
        speed = compile_mod('SpeedHackMod', [sdk, overlay, args.refs / 'UnityEngine.CoreModule.dll'])
        camera = compile_mod('FreeCameraMod', [sdk, args.refs / 'UnityEngine.CoreModule.dll', args.refs / 'AstralParty.Runtime.dll'])
        card = compile_mod('CardSkinMod', [sdk] + [args.refs / name for name in (
            'UnityEngine.CoreModule.dll', 'AstralParty.Runtime.dll', 'UniTask.dll',
            'Google.Protobuf.Runtime.dll')])
else:
    project = 'CesiumOverlayMod' if args.overlay_only else 'SpeedHackMod'
    subprocess.run(['dotnet', 'build', str(ROOT / project), '-c', 'Release', '--nologo',
                    '-p:GameRefs=' + str(args.refs.resolve()), '-p:CesiumSdkDll=' + str(sdk.resolve())], check=True)
    compiled = ROOT / 'CesiumOverlayMod/bin/Release/netstandard2.0'
    api, overlay = compiled / 'CesiumAndroidApi.dll', compiled / 'CesiumOverlayMod.dll'
    if not args.overlay_only:
        speed = ROOT / 'SpeedHackMod/bin/Release/netstandard2.0/SpeedHackMod.dll'
        subprocess.run(['dotnet', 'build', str(ROOT / 'FreeCameraMod'), '-c', 'Release', '--nologo', '-p:GameRefs=' + str(args.refs.resolve()), '-p:CesiumSdkDll=' + str(sdk.resolve())], check=True)
        camera = ROOT / 'FreeCameraMod/bin/Release/netstandard2.0/FreeCameraMod.dll'
        subprocess.run(['dotnet', 'build', str(ROOT / 'CardSkinMod'), '-c', 'Release', '--nologo', '-p:GameRefs=' + str(args.refs.resolve()), '-p:CesiumSdkDll=' + str(sdk.resolve())], check=True)
        card = ROOT / 'CardSkinMod/bin/Release/netstandard2.0/CardSkinMod.dll'

staged = out / 'assets'
target = staged / 'mods/CesiumOverlayMod'
target.mkdir(parents=True, exist_ok=True)
if not args.overlay_only:
    shutil.copy2(api, staged / 'CesiumAndroidApi.dll')
shutil.copy2(overlay, target / overlay.name)
shutil.copy2(ROOT / 'CesiumOverlayMod/overlay.png', target / 'overlay.png')
(target / 'CesiumOverlayMod.json').write_text(json.dumps({
    'id': 'CesiumOverlayMod', 'name': '吉星工具箱悬浮窗', 'version': '0.9.0',
    'author': 'TenkinMori / Cesium Android adaptation',
    'description': '半屏侧边工具箱，首次打开变速页，中文功能分组与游戏内快捷开关。',
    'sdkVersion': '2.8.6', 'permissions': 141, 'dependencies': [],
}, ensure_ascii=False), encoding='utf-8')
licenses = target / 'licenses'
licenses.mkdir(exist_ok=True)
if args.overlay_only:
    # Preserve the exact already-shipped notices, including their original line endings.
    shutil.copytree(assets / 'mods/CesiumOverlayMod/licenses', licenses, dirs_exist_ok=True)
else:
    for license in (ROOT / 'licenses').glob('*'):
        shutil.copy2(license, licenses / license.name)
    shutil.copy2(ROOT.parent / 'LICENSE', licenses / 'Cesium-Android-Direct-AGPL-3.0.txt')
if not args.overlay_only:
    speed_target = staged / 'mods/SpeedHackMod'
    speed_target.mkdir(parents=True, exist_ok=True)
    shutil.copy2(speed, speed_target / 'SpeedHackMod.dll')
    (speed_target / 'SpeedHackMod.json').write_text(json.dumps({
        'id': 'SpeedHackMod', 'name': '变速（仅悬浮窗控制）', 'version': '2.5.5-android-panel',
        'author': 'Cesium Android',
        'description': '每次启动保持 1 倍，忽略历史倍率和键盘热键，仅通过游戏内悬浮窗控制。',
        'sdkVersion': '2.8.6', 'permissions': 132, 'dependencies': [],
    }, ensure_ascii=False), encoding='utf-8')
    shutil.copytree(licenses, speed_target / 'licenses', dirs_exist_ok=True)
    camera_target = staged / 'mods/FreeCameraMod'
    camera_target.mkdir(parents=True, exist_ok=True)
    shutil.copy2(camera, camera_target / 'FreeCameraMod.dll')
    (camera_target / 'FreeCameraMod.json').write_text(json.dumps({
        'id': 'FreeCameraMod', 'name': '自由相机（双指缩放）', 'version': '2.7.2-android-pinch',
        'author': 'Cesium Android', 'description': '仅双指距离变化缩放；双指平移和扭转不旋转镜头。',
        'sdkVersion': '2.8.6', 'permissions': 97, 'dependencies': []
    }, ensure_ascii=False), encoding='utf-8')
    shutil.copytree(licenses, camera_target / 'licenses', dirs_exist_ok=True)
    card_target = staged / 'mods/CardSkinMod'
    card_target.mkdir(parents=True, exist_ok=True)
    shutil.copy2(card, card_target / 'CardSkinMod.dll')
    shutil.copy2(ROOT / 'CardSkinMod/CardSkinMod.json', card_target / 'CardSkinMod.json')
shutil.copytree(staged, assets, dirs_exist_ok=True)
print('Bundled overlay only (existing API, speed adapter and camera retained).' if args.overlay_only else 'Bundled AndroidApi, overlay and panel-only speed adapter (official SDK retained).')
