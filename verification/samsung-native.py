"""Build a local emulator fixture. Never installs or contacts a physical device."""
from pathlib import Path
import os, subprocess, zipfile, argparse
parser=argparse.ArgumentParser();parser.add_argument("--reader",action="store_true");reader=parser.parse_args().reader
root=Path(__file__).resolve().parent.parent
out=root/('verification/samsung-import/reader' if reader else 'verification/samsung-import/native')
package='com.mani.orbit.importcheck' if reader else 'com.sec.android.app.shealth'
sdk=Path.home()/'AppData/Local/Android/Sdk'
java=Path(r'C:\HA\HEALTH APP\.local\toolchains\jdk-17.0.20.1+1')
bt=sdk/'build-tools/36.0.0';platform=sdk/'platforms/android-37.0/android.jar'
for folder in ['classes','dex']: (out/folder).mkdir(parents=True,exist_ok=True)
env=dict(os.environ,JAVA_HOME=str(java))
def run(*args):subprocess.run([str(a) for a in args],check=True,env=env)
types=['STEPS','WEIGHT','ACTIVE_CALORIES_BURNED','DISTANCE','HEART_RATE','SLEEP','EXERCISE']
permissions=''.join(f'<uses-permission android:name="android.permission.health.{op}_{kind}"/>' for op in (['READ'] if reader else ['READ','WRITE']) for kind in types)
(out/'AndroidManifest.xml').write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}"><uses-sdk android:minSdkVersion="34" android:targetSdkVersion="35"/><uses-permission android:name="android.permission.health.READ_HEALTH_DATA_HISTORY"/>{permissions}<application android:debuggable="true" android:label="Local health check"><activity android:name="com.mani.orbit.HealthImportCheck" android:exported="true"/><activity android:name="com.mani.orbit.HealthPrivacyActivity" android:exported="true" android:permission="android.permission.START_VIEW_PERMISSION_USAGE"><intent-filter><action android:name="android.intent.action.VIEW_PERMISSION_USAGE"/><category android:name="android.intent.category.HEALTH_PERMISSIONS"/></intent-filter></activity></application></manifest>''',encoding='utf-8')
run(bt/'aapt2.exe','link','-o',out/'fixture.apk','-I',platform,'--manifest',out/'AndroidManifest.xml')
run(java/'bin/javac.exe','--release','8','-encoding','UTF-8','-classpath',platform,'-d',out/'classes',*sorted((root/'android/src').rglob('*.java')),root/'verification/HealthImportCheck.java')
run(bt/'d8.bat','--min-api','34','--lib',platform,'--output',out/'dex',*sorted((out/'classes').rglob('*.class')))
with zipfile.ZipFile(out/'fixture.apk','a',zipfile.ZIP_DEFLATED) as apk:apk.write(out/'dex/classes.dex','classes.dex')
run(bt/'zipalign.exe','-f','4',out/'fixture.apk',out/'aligned.apk')
run(bt/'apksigner.bat','sign','--ks',root/'android/signing/orbit-local.p12','--ks-key-alias','orbit','--ks-pass','pass:orbit-local-build','--out',out/'signed.apk',out/'aligned.apk')
print(out/'signed.apk')
