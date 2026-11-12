"""Assemble a fresh-history public source tree from an explicit allowlist."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
ALLOWED = ['README.md', 'LICENSE', '.gitignore', '.gitattributes', '.github/workflows/mobile.yml',
           'apps/mobile/pubspec.yaml', 'apps/mobile/pubspec.lock', 'apps/mobile/analysis_options.yaml',
           'apps/mobile/lib', 'apps/mobile/test', 'apps/mobile/assets', 'apps/mobile/android/app/src',
           'apps/mobile/android/app/schemas', 'apps/mobile/android/app/build.gradle.kts',
           'apps/mobile/android/app/capture-proguard-rules.pro', 'apps/mobile/android/settings.gradle.kts',
           'apps/mobile/android/build.gradle.kts', 'apps/mobile/android/gradle.properties',
           'apps/mobile/android/gradle', 'apps/mobile/android/gradlew', 'apps/mobile/android/gradlew.bat',
           'apps/mobile/production.json', 'scripts/build-mobile.ps1', 'scripts/export-public.py', 'docs/public']
DENIED = ['BEGIN PRIVATE KEY', 'BEGIN RSA PRIVATE KEY',
          'C:\\Users\\', 'F:\\desktop\\', 'root@', 'API_BASE_URL', 'api_base_url',
          'admin_token', 'postgresql+asyncpg']


def assemble(destination: Path):
    destination = destination.resolve()
    if not destination.is_relative_to(ROOT / 'work') or destination.exists():
        raise SystemExit('Use a new destination below this workspace work directory')
    destination.mkdir(parents=True)
    for name in ALLOWED:
        source, target = ROOT / name, destination / name
        if source.is_dir():
            shutil.copytree(source, target, ignore=shutil.ignore_patterns('__pycache__', '*.pyc', '.DS_Store'))
        elif source.is_file():
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
        else:
            raise RuntimeError('Missing public source: ' + name)
    manifest = {}
    for item in destination.rglob('*'):
        if not item.is_file():
            continue
        name = item.relative_to(destination).as_posix()
        if item.suffix.lower() in {'.pem', '.p12', '.jks', '.har', '.db', '.sqlite', '.env'}:
            raise RuntimeError('Forbidden public artifact: ' + name)
        data = item.read_bytes()
        if item.suffix.lower() not in {'.png', '.onnx', '.jar', '.ico', '.ttf'}:
            text = data.decode('utf-8', errors='replace')
            # This scanner contains its own denylist, not any actual private material.
            if name != 'scripts/export-public.py' and any(value in text for value in DENIED):
                raise RuntimeError('Private reference in: ' + name)
        manifest[name] = hashlib.sha256(data).hexdigest()
    # Git metadata is deliberately created only after source scanning.
    subprocess.run(['git', 'init', '-b', 'main', str(destination)], check=True, capture_output=True)
    manifest_path = ROOT / 'docs/codex/reviews/R41/evidence/public-manifest.json'
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    print(f'Assembled {len(manifest)} public files; fresh Git repository; no legacy history')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('destination', type=Path)
    assemble(parser.parse_args().destination)
