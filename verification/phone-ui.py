"""Small guarded UI helper for the authorised Orbit check on the named Galaxy only."""
from pathlib import Path
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
sys.stdout.reconfigure(encoding='utf-8')

adb = Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk/platform-tools/adb.exe'
device = '192.168.0.210:39389'
out = Path(__file__).resolve().parent / 'seven-ux-phone'
out.mkdir(exist_ok=True)

def run(*args, binary=False):
    return subprocess.check_output([str(adb), '-s', device, *args], text=not binary, encoding=None if binary else 'utf-8')

def guard():
    focus = run('shell', 'dumpsys', 'window')
    if not any('mCurrentFocus=' in line and 'com.mani.orbit/' in line for line in focus.splitlines()):
        raise SystemExit('Orbit is not foreground; no phone input sent.')

guard()
run('shell', 'rm', '-f', '/data/local/tmp/orbit-seven-ui.xml')
dump = run('shell', 'uiautomator', 'dump', '/data/local/tmp/orbit-seven-ui.xml')
if 'dumped to:' not in dump:
    raise SystemExit('Android did not return a fresh UI snapshot; no phone input sent.')
xml = run('shell', 'cat', '/data/local/tmp/orbit-seven-ui.xml')
nodes = [n for n in ET.fromstring(xml).iter('node') if n.get('package') == 'com.mani.orbit']
if len(sys.argv) > 1 and sys.argv[1] == 'tap':
    matches = [n for n in nodes if sys.argv[2] in [n.get('text'), n.get('content-desc'), n.get('resource-id')]]
    if len(matches) != 1:
        raise SystemExit('Expected one exact control, found ' + str(len(matches)))
    x1,y1,x2,y2 = map(int,re.findall(r'\d+',matches[0].get('bounds')))
    guard()
    run('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
    print('Tapped:',sys.argv[2])
elif len(sys.argv) > 1 and sys.argv[1] == 'capture':
    name = sys.argv[2]
    if not re.fullmatch(r'[a-z0-9-]+', name): raise SystemExit('Use a simple capture name')
    guard()
    (out / (name + '.xml')).write_text(xml, encoding='utf-8')
    (out / (name + '.png')).write_bytes(run('exec-out','screencap','-p',binary=True))
    print(out / (name + '.png'))
else:
    for node in nodes:
        if node.get('text') or node.get('content-desc'):
            print(node.get('text'), '|', node.get('content-desc'), '|', node.get('bounds'))
