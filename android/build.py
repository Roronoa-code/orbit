"""Build Orbit using the installed Android SDK and Java; no downloaded dependencies."""
from datetime import datetime
from pathlib import Path
import os
import shutil
import subprocess
import zipfile

root = Path(__file__).resolve().parent
sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'AppData/Local/Android/Sdk')))
java = Path(os.environ.get('JAVA_HOME', r'C:\HA\HEALTH APP\.local\toolchains\jdk-17.0.20.1+1'))
bt = sdk / 'build-tools/36.0.0'
platform = sdk / 'platforms/android-37.0/android.jar'
build = root / 'build' / datetime.now().strftime('%Y%m%d-%H%M%S')
for name in ['classes', 'assets', 'dex']:
    (build / name).mkdir(parents=True)
env = dict(os.environ, JAVA_HOME=str(java))

def run(*args):
    subprocess.run([str(a) for a in args], check=True, env=env, cwd=build)

html = (root.parent / 'index.html').read_text(encoding='utf-8')
health_pages = (root.parent / 'health-pages.js').read_text(encoding='utf-8')
html = html.replace('<script src="health-pages.js"></script>', '<script id="health-pages">' + health_pages + '</script>')
html = html.replace('<link rel="stylesheet" href="health-pages.css">', '<style>' + (root.parent / 'health-pages.css').read_text(encoding='utf-8') + '</style>')
html = html.replace('<script src="surface-motion.js"></script>', '<script>' + (root.parent / 'surface-motion.js').read_text(encoding='utf-8') + '</script>')
html = html.replace('<script src="hero-dots.js"></script>', '<script>' + (root.parent / 'hero-dots.js').read_text(encoding='utf-8') + '</script>')
html = html.replace('<script src="sleep-timeline.js"></script>', '<script>' + (root.parent / 'sleep-timeline.js').read_text(encoding='utf-8') + '</script>')
html = html.replace('<script src="workout-focus.js"></script>', '<script>' + (root.parent / 'workout-focus.js').read_text(encoding='utf-8') + '</script>')
html = html.replace('<script src="workout-details.js"></script>', '<script>' + (root.parent / 'workout-details.js').read_text(encoding='utf-8') + '</script>')
html = html.replace('<script src="signal-orb.js"></script>', '<script>' + (root.parent / 'signal-orb.js').read_text(encoding='utf-8') + '</script>')
native_style = '<style>.status,.home-indicator{display:none}.utility-island{top:47px}.health-page{top:0}.screen{height:100dvh!important;min-height:0!important}.phone{max-width:none;margin:0;padding:0;border:0;box-shadow:none}.screen{border-radius:0;padding-top:0}.copy-label{margin-bottom:16px}.masthead{padding-top:12px}body{background:#0a0a0c}</style>'
html = html.replace('</head>', native_style + '</head>')
(build / 'assets/index.html').write_text(html, encoding='utf-8')
shutil.copy2(root.parent / 'Manrope-OFL.txt', build / 'assets/Manrope-OFL.txt')
run(bt / 'aapt2.exe', 'compile', '--dir', root / 'res', '-o', build / 'resources.zip')
run(bt / 'aapt2.exe', 'link', '-o', build / 'unsigned.apk', '-I', platform, '--manifest', root / 'AndroidManifest.xml', '-A', build / 'assets', build / 'resources.zip')
run(java / 'bin/javac.exe', '--release', '8', '-encoding', 'UTF-8', '-classpath', platform, '-d', build / 'classes', *sorted((root / 'src').rglob('*.java')))
run(bt / 'd8.bat', '--release', '--min-api', '30', '--lib', platform, '--output', build / 'dex', *sorted((build / 'classes').rglob('*.class')))
with zipfile.ZipFile(build / 'unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as apk:
    apk.write(build / 'dex/classes.dex', 'classes.dex')
run(bt / 'zipalign.exe', '-p', '4', build / 'unsigned.apk', build / 'aligned.apk')
key = root / 'signing/orbit-local.p12'
key.parent.mkdir(exist_ok=True)
if not key.exists():
    run(java / 'bin/keytool.exe', '-genkeypair', '-keystore', key, '-alias', 'orbit', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000', '-storepass', 'orbit-local-build', '-keypass', 'orbit-local-build', '-dname', 'CN=Orbit Local Build')
run(bt / 'apksigner.bat', 'sign', '--ks', key, '--ks-key-alias', 'orbit', '--ks-pass', 'pass:orbit-local-build', '--out', build / 'Orbit.apk', build / 'aligned.apk')
run(bt / 'apksigner.bat', 'verify', '--verbose', build / 'Orbit.apk')
dist = root.parent / 'dist'
dist.mkdir(exist_ok=True)
if (dist / 'Orbit.apk').exists():
    shutil.copy2(dist / 'Orbit.apk', build / 'previous-Orbit.apk')
shutil.copy2(build / 'Orbit.apk', dist / 'Orbit.apk')
(root / 'last-build.txt').write_text(str(build), encoding='utf-8')
print('APK:', dist / 'Orbit.apk')
