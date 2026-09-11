"""Build the separate native read-only music check with the existing SDK and signing key."""
from pathlib import Path
import os
import subprocess
import zipfile

root = Path(__file__).resolve().parent.parent
build = Path((root / 'android/last-build.txt').read_text(encoding='utf-8').strip())
check = build / 'music-runtime-check'
sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'AppData/Local/Android/Sdk')))
jdk = Path(os.environ.get('JAVA_HOME', r'C:\HA\HEALTH APP\.local\toolchains\jdk-17.0.20.1+1'))
bt = sdk / 'build-tools/36.0.0'
platform = sdk / 'platforms/android-37.0/android.jar'
for name in ['classes', 'dex']:
    (check / name).mkdir(parents=True, exist_ok=True)
manifest = check / 'AndroidManifest.xml'
manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.mani.orbit.uxcheck">
<uses-sdk android:minSdkVersion="30" android:targetSdkVersion="35"/>
<application android:label="Orbit local verification"/>
<instrumentation android:name="com.mani.orbit.MusicRuntimeCheck" android:targetPackage="com.mani.orbit"/>
</manifest>''', encoding='utf-8')

def run(*args):
    subprocess.run([str(arg) for arg in args], check=True, env=dict(os.environ, JAVA_HOME=str(jdk)))

run(jdk / 'bin/javac.exe', '--release', '8', '-encoding', 'UTF-8', '-classpath', str(build / 'classes') + ';' + str(platform), '-d', check / 'classes', root / 'verification/MusicRuntimeCheck.java')
run(bt / 'd8.bat', '--release', '--min-api', '30', '--lib', platform, '--classpath', build / 'classes', '--output', check / 'dex', *sorted((check / 'classes').rglob('*.class')))
run(bt / 'aapt2.exe', 'link', '-o', check / 'unsigned.apk', '-I', platform, '--manifest', manifest)
with zipfile.ZipFile(check / 'unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as apk:
    apk.write(check / 'dex/classes.dex', 'classes.dex')
run(bt / 'zipalign.exe', '-f', '4', check / 'unsigned.apk', check / 'aligned.apk')
run(bt / 'apksigner.bat', 'sign', '--ks', root / 'android/signing/orbit-local.p12', '--ks-key-alias', 'orbit', '--ks-pass', 'pass:orbit-local-build', '--out', check / 'check.apk', check / 'aligned.apk')
print(check / 'check.apk')
