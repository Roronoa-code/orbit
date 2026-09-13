"""Use the locally downloaded Samsung SDK and the existing Android dependency cache."""
from pathlib import Path
import io
import os
import zipfile


def prepare(build):
    archive = Path(os.environ.get('SAMSUNG_HEALTH_SDK', str(Path.home() / 'Downloads/samsung-health-data-sdk-1.1.0.zip')))
    cache = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle'))) / 'caches/modules-2/files-2.1'
    dependencies = [('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.21'),
                    ('org.jetbrains.kotlin', 'kotlin-parcelize-runtime', '1.9.22'),
                    ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.10.2'),
                    ('org.jetbrains.kotlinx', 'kotlinx-coroutines-android', '1.10.2'),
                    ('com.google.code.gson', 'gson', '2.13.1')]
    jars = []
    for group, name, version in dependencies:
        matches = sorted((cache / group / name / version).glob(f'*/{name}-{version}.jar'))
        if not matches:
            raise SystemExit(f'Missing installed Samsung SDK dependency: {group}:{name}:{version}')
        jars.append(matches[0])
    if not archive.is_file():
        raise SystemExit('Download Samsung Health Data SDK 1.1.0 to Downloads, or set SAMSUNG_HEALTH_SDK to its zip path.')
    with zipfile.ZipFile(archive) as sdk:
        with zipfile.ZipFile(io.BytesIO(sdk.read('1.1.0/libs/samsung-health-data-api-1.1.0.aar'))) as aar:
            library = build / 'samsung-health.jar'
            library.write_bytes(aar.read('classes.jar'))
        (build / 'assets').mkdir(exist_ok=True)
        (build / 'assets/Samsung-OPEN-SOURCE.txt').write_bytes(sdk.read('1.1.0/ANNOUNCEMENT.txt'))
    return [library, *jars]


def resources(apk, jars):
    # D8 packages code only. Coroutines discovers its Android dispatcher through ServiceLoader.
    services = {}
    for jar in jars:
        with zipfile.ZipFile(jar) as source:
            for name in source.namelist():
                if name.startswith('META-INF/services/') and not name.endswith('/'):
                    services.setdefault(name, set()).update(source.read(name).decode('utf-8').splitlines())
    for name, lines in services.items():
        apk.writestr(name, '\n'.join(sorted(lines)) + '\n')
