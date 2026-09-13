"""Build a local emulator fixture. Never installs or contacts a physical device."""
from pathlib import Path
import os, subprocess, zipfile, argparse, sys
parser=argparse.ArgumentParser();parser.add_argument("--reader",action="store_true");parser.add_argument("--workout",action="store_true");args=parser.parse_args();reader=args.reader;workout=args.workout
root=Path(__file__).resolve().parent.parent
out=root/('verification/samsung-import/workout' if workout else 'verification/samsung-import/reader' if reader else 'verification/samsung-import/native')
package='com.mani.orbit.localcheck' if workout else 'com.mani.orbit.importcheck' if reader else 'com.sec.android.app.shealth'
activity='WorkoutLiveCheck' if workout else 'HealthImportCheck'
sys.path.insert(0,str(root/'android'));import samsung_deps
sdk=Path.home()/'AppData/Local/Android/Sdk'
java=Path(r'C:\HA\HEALTH APP\.local\toolchains\jdk-17.0.20.1+1')
bt=sdk/'build-tools/36.0.0';platform=sdk/'platforms/android-37.0/android.jar'
for folder in ['classes','dex']: (out/folder).mkdir(parents=True,exist_ok=True)
jars=samsung_deps.prepare(out)
env=dict(os.environ,JAVA_HOME=str(java))
def run(*args):subprocess.run([str(a) for a in args],check=True,env=env)
types=['STEPS','WEIGHT','ACTIVE_CALORIES_BURNED','DISTANCE','HEART_RATE','SLEEP','EXERCISE']
permissions=''.join(f'<uses-permission android:name="android.permission.health.{op}_{kind}"/>' for op in (['READ'] if reader else ['READ','WRITE']) for kind in types)
if workout:
 permissions+=''.join(f'<uses-permission android:name="android.permission.{kind}"/>' for kind in ['INTERNET','FOREGROUND_SERVICE','FOREGROUND_SERVICE_LOCATION','ACCESS_FINE_LOCATION','ACCESS_COARSE_LOCATION','POST_NOTIFICATIONS'])
(out/'AndroidManifest.xml').write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}"><uses-sdk android:minSdkVersion="34" android:targetSdkVersion="35"/><uses-permission android:name="android.permission.health.READ_HEALTH_DATA_HISTORY"/>{permissions}<application android:debuggable="true" android:label="Local health check"><activity android:name="com.mani.orbit.{activity}" android:exported="true"/><activity android:name="com.mani.orbit.HealthPrivacyActivity" android:exported="true" android:permission="android.permission.START_VIEW_PERMISSION_USAGE"><intent-filter><action android:name="android.intent.action.VIEW_PERMISSION_USAGE"/><category android:name="android.intent.category.HEALTH_PERMISSIONS"/></intent-filter></activity></application></manifest>''',encoding='utf-8')
if workout:
 manifest=out/'AndroidManifest.xml'
 manifest.write_text(manifest.read_text(encoding='utf-8').replace('</application>','<service android:name="com.mani.orbit.WorkoutTrackingService" android:exported="false" android:foregroundServiceType="location"/></application>'),encoding='utf-8')
run(bt/'aapt2.exe','compile','--dir',root/'android/res','-o',out/'resources.zip')
run(bt/'aapt2.exe','link','-o',out/'fixture.apk','-I',platform,'--manifest',out/'AndroidManifest.xml',out/'resources.zip')
run(java/'bin/javac.exe','--release','8','-encoding','UTF-8','-classpath',os.pathsep.join(map(str,[platform,*jars])),'-d',out/'classes',*sorted((root/'android/src').rglob('*.java')),root/f'verification/{activity}.java')
run(bt/'d8.bat','--min-api','34','--lib',platform,'--output',out/'dex',*sorted((out/'classes').rglob('*.class')),*jars)
with zipfile.ZipFile(out/'fixture.apk','a',zipfile.ZIP_DEFLATED) as apk:
 for dex in (out/'dex').glob('*.dex'):apk.write(dex,dex.name)
 samsung_deps.resources(apk,jars)
run(bt/'zipalign.exe','-f','4',out/'fixture.apk',out/'aligned.apk')
run(bt/'apksigner.bat','sign','--ks',root/'android/signing/orbit-local.p12','--ks-key-alias','orbit','--ks-pass','pass:orbit-local-build','--out',out/'signed.apk',out/'aligned.apk')
print(out/'signed.apk')
