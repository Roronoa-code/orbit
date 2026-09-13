"""Build Orbit with the installed Android tools, local Samsung SDK and dependency cache."""
from datetime import datetime
from pathlib import Path
import os
import shutil
import subprocess
import zipfile
import argparse
import samsung_deps

parser = argparse.ArgumentParser()
parser.add_argument('--audit', action='store_true', help='Build a separate debuggable Orbit Audit app with isolated data')
audit = parser.parse_args().audit

root = Path(__file__).resolve().parent
sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'AppData/Local/Android/Sdk')))
java = Path(os.environ.get('JAVA_HOME', r'C:\HA\HEALTH APP\.local\toolchains\jdk-17.0.20.1+1'))
bt = sdk / 'build-tools/36.0.0'
platform = sdk / 'platforms/android-37.0/android.jar'
build = root / 'build' / (datetime.now().strftime('%Y%m%d-%H%M%S') + ('-audit' if audit else ''))
for name in ['classes', 'assets', 'dex']:
    (build / name).mkdir(parents=True)
jars = samsung_deps.prepare(build)
env = dict(os.environ, JAVA_HOME=str(java))

def run(*args):
    subprocess.run([str(a) for a in args], check=True, env=env, cwd=build)

html = (root.parent / 'index.html').read_text(encoding='utf-8')

def inline(reference, path, attributes=''):
    """Inline one local asset for offline packaging, and fail the build if its reference is missing."""
    global html
    if reference not in html:
        raise SystemExit(f'index.html no longer references {path}; update the bundler before building.')
    source = (root.parent / path).read_text(encoding='utf-8')
    opening = '<style>' if path.endswith('.css') else f'<script{attributes}>'
    closing = '</style>' if path.endswith('.css') else '</script>'
    html = html.replace(reference, opening + source + closing)

# Every local stylesheet and script the page loads must be inlined here; nothing may remain a network reference.
inline('<link rel="stylesheet" href="glass-material.css">', 'glass-material.css')
inline('<link rel="stylesheet" href="health-pages.css">', 'health-pages.css')
for name in ['health-data.js', 'settings-store.js', 'surface-motion.js', 'orbit-interaction.js', 'hero-dots.js', 'sleep-timeline.js', 'blob-track.js', 'liquid-glass.js', 'workout-focus.js', 'workout-details.js', 'orbit-settings.js', 'signal-orb.js']:
    inline(f'<script src="{name}"></script>', name)
inline('<script src="health-pages.js"></script>', 'health-pages.js', ' id="health-pages"')
import re as _re
left_over = _re.findall(r'<script src="([^"]+)"></script>|<link rel="stylesheet" href="([^"]+)">', html)
left_over = [name for pair in left_over for name in pair if name and '//' not in name]
if left_over:
    raise SystemExit(f'Unbundled local assets remain in the packaged page: {left_over}')
native_style = '<style>.status,.home-indicator{display:none}.utility-island{top:calc(var(--safe-top) + 62px)}.screen{height:100dvh!important;min-height:0!important}.phone{max-width:none;margin:0;padding:0;border:0;box-shadow:none}.screen{border-radius:0;padding-top:var(--safe-top)}.copy-label{margin-bottom:16px}.masthead{padding-top:12px}body{background:#0a0a0c}</style>'
html = html.replace('</head>', native_style + '</head>')
(build / 'assets/index.html').write_text(html, encoding='utf-8')
shutil.copy2(root.parent / 'Manrope-OFL.txt', build / 'assets/Manrope-OFL.txt')
shutil.copy2(root.parent / 'licenses/Kyant-backdrop-LICENSE.txt', build / 'assets/Kyant-backdrop-LICENSE.txt')
shutil.copy2(root.parent / 'THIRD-PARTY-NOTICES.md', build / 'assets/THIRD-PARTY-NOTICES.md')
run(bt / 'aapt2.exe', 'compile', '--dir', root / 'res', '-o', build / 'resources.zip')
manifest = root / 'AndroidManifest.xml'
if audit:
    diagnostic_manifest = manifest.read_text(encoding='utf-8').replace('package="com.mani.orbit"', 'package="com.mani.orbit.audit"').replace('<application android:label="Orbit"', '<application android:debuggable="true" android:label="Orbit Audit"')
    manifest = build / 'AndroidManifest.xml'
    manifest.write_text(diagnostic_manifest, encoding='utf-8')
run(bt / 'aapt2.exe', 'link', '-o', build / 'unsigned.apk', '-I', platform, '--manifest', manifest, '-A', build / 'assets', build / 'resources.zip')
run(java / 'bin/javac.exe', '--release', '8', '-encoding', 'UTF-8', '-classpath', os.pathsep.join(map(str, [platform, *jars])), '-d', build / 'classes', *sorted((root / 'src').rglob('*.java')))
run(bt / 'd8.bat', '--release', '--min-api', '30', '--lib', platform, '--output', build / 'dex', *sorted((build / 'classes').rglob('*.class')), *jars)
with zipfile.ZipFile(build / 'unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as apk:
    for dex in sorted((build / 'dex').glob('*.dex')):
        apk.write(dex, dex.name)
    samsung_deps.resources(apk, jars)
run(bt / 'zipalign.exe', '-p', '4', build / 'unsigned.apk', build / 'aligned.apk')
key = root / 'signing/orbit-local.p12'
key.parent.mkdir(exist_ok=True)
if not key.exists():
    run(java / 'bin/keytool.exe', '-genkeypair', '-keystore', key, '-alias', 'orbit', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000', '-storepass', 'orbit-local-build', '-keypass', 'orbit-local-build', '-dname', 'CN=Orbit Local Build')
run(bt / 'apksigner.bat', 'sign', '--ks', key, '--ks-key-alias', 'orbit', '--ks-pass', 'pass:orbit-local-build', '--out', build / 'Orbit.apk', build / 'aligned.apk')
run(bt / 'apksigner.bat', 'verify', '--verbose', build / 'Orbit.apk')
dist = root.parent / 'dist'
dist.mkdir(exist_ok=True)
output_name = 'Orbit-Audit.apk' if audit else 'Orbit.apk'
if (dist / output_name).exists():
    shutil.copy2(dist / output_name, build / ('previous-' + output_name))
shutil.copy2(build / 'Orbit.apk', dist / output_name)
(root / ('last-audit-build.txt' if audit else 'last-build.txt')).write_text(str(build), encoding='utf-8')
print('APK:', dist / output_name)
