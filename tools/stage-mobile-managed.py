"""Stage and package cesium-loader-mobile-managed-<version>.zip including built-in mobile mods."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import tempfile
import zipfile


MOBILE_OVERRIDDEN_MODS = {
    'cesiumoverlaymod',
    'speedhackmod',
    'freecameramod',
    'languageunlockmod',
}


def stage_managed(
    loader_zip: Path,
    sdk_dll: Path,
    bootstrap_dll: Path,
    overlay_assets: Path,
    repo_assets: Path,
    out_zip: Path,
) -> tuple[int, int]:
    with tempfile.TemporaryDirectory(prefix='cesium-mobile-managed-') as tmp:
        stage = Path(tmp) / 'stage'
        stage.mkdir(parents=True, exist_ok=True)

        (stage / 'bootstrap').mkdir(parents=True, exist_ok=True)
        (stage / 'sdk').mkdir(parents=True, exist_ok=True)
        shutil.copy2(bootstrap_dll, stage / 'bootstrap' / 'CesiumLoader.Mobile.Bootstrap.dll')
        shutil.copy2(sdk_dll, stage / 'sdk' / 'CesiumLoader.SDK.dll')

        loader_version = ''
        mods_dir = stage / 'mods'
        mods_dir.mkdir(parents=True, exist_ok=True)

        with zipfile.ZipFile(loader_zip) as zf:
            for entry in zf.infolist():
                name = entry.filename.replace('\\', '/')
                if entry.is_dir():
                    continue
                if not name.startswith('AstralParty_ModLoader/mods/'):
                    continue
                if name.lower().endswith('.pdb'):
                    continue

                relative = name.removeprefix('AstralParty_ModLoader/mods/')
                parts = relative.split('/')
                if any(p in ('', '.', '..') for p in parts) or '\\' in relative or ':' in relative:
                    raise ValueError(f'Invalid built-in mod entry path: {name}')
                if parts[0].lower() in MOBILE_OVERRIDDEN_MODS:
                    continue

                target = mods_dir / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                with zf.open(entry) as src, open(target, 'wb') as dst:
                    shutil.copyfileobj(src, dst)

            if 'cesium-loader.json' in zf.namelist():
                manifest_text = zf.read('cesium-loader.json').decode('utf-8')
                m = re.search(r'"version"\s*:\s*"([^"]+)"', manifest_text)
                if m:
                    loader_version = m.group(1)

        if not loader_version and (repo_assets / 'loader.json').is_file():
            loader_data = json.loads((repo_assets / 'loader.json').read_text(encoding='utf-8'))
            loader_version = str(loader_data.get('version', '')).strip()

        if not loader_version:
            raise SystemExit('Could not determine loader version from loader zip or repo assets.')

        (stage / 'loader.json').write_text(
            json.dumps({'version': loader_version, 'source': loader_zip.name}) + '\n',
            encoding='utf-8',
        )

        lang_mod = repo_assets / 'mods' / 'LanguageUnlockMod'
        if lang_mod.is_dir():
            shutil.copytree(lang_mod, mods_dir / 'LanguageUnlockMod', dirs_exist_ok=True)

        if overlay_assets.is_dir():
            shutil.copytree(overlay_assets, stage, dirs_exist_ok=True)

        for required in (
            'loader.json',
            'bootstrap/CesiumLoader.Mobile.Bootstrap.dll',
            'sdk/CesiumLoader.SDK.dll',
            'CesiumLoader.Mobile.API.dll',
            'mods/CesiumOverlayMod/CesiumOverlayMod.dll',
            'mods/CardSkinMod/CardSkinMod.dll',
        ):
            if not (stage / required).is_file():
                raise SystemExit(f'Missing required staged file: {required}')

        mod_dirs = sorted(p.name for p in mods_dir.iterdir() if p.is_dir())
        if not mod_dirs:
            raise SystemExit('No built-in mods were staged into mods/.')

        out_zip.parent.mkdir(parents=True, exist_ok=True)
        staged_files = sorted(p for p in stage.rglob('*') if p.is_file())
        with zipfile.ZipFile(out_zip, 'w', compression=zipfile.ZIP_DEFLATED) as zf:
            for file_path in staged_files:
                zf.write(file_path, file_path.relative_to(stage).as_posix())

        digest = hashlib.sha256(out_zip.read_bytes()).hexdigest()
        out_zip.with_suffix('.zip.sha256').write_text(f'{digest}  {out_zip.name}\n', encoding='utf-8')
        return len(mod_dirs), len(staged_files)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--loader-zip', required=True, type=Path)
    parser.add_argument('--sdk', required=True, type=Path)
    parser.add_argument('--bootstrap', required=True, type=Path)
    parser.add_argument('--overlay-assets', required=True, type=Path)
    parser.add_argument('--repo-assets', required=True, type=Path)
    parser.add_argument('--out-zip', required=True, type=Path)
    args = parser.parse_args()

    mods_count, file_count = stage_managed(
        args.loader_zip,
        args.sdk,
        args.bootstrap,
        args.overlay_assets,
        args.repo_assets,
        args.out_zip,
    )
    print(f'Staged {args.out_zip.name}: {mods_count} mods, {file_count} files')


if __name__ == '__main__':
    main()
