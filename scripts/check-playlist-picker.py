#!/usr/bin/env python3
"""Exercise the actual shared picker on Android with generated data and no Google account."""
from pathlib import Path
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET

OUT = Path('ui-smoke')
OUT.mkdir(exist_ok=True)
PACKAGE = 'com.renz.segue.dev'
ACTIVITY = PACKAGE + '/com.maxrave.simpmusic.PlaylistPickerProbeActivity'

def adb(*args, check=True):
    return subprocess.run(['adb', *args], check=check, capture_output=True, timeout=50).stdout.decode(errors='replace')

def dump(name='current'):
    output = adb('shell', 'uiautomator', 'dump', '/sdcard/picker-window.xml', check=False)
    data = adb('shell', 'cat', '/sdcard/picker-window.xml', check=False)
    # A cold-start splash/IME transition can temporarily have no accessibility root.
    # Retry in node(), rather than treating a missing dump as an application crash.
    if '<?xml' not in data:
        with (OUT / 'uiautomator-retries.txt').open('a') as log:
            log.write(output + '\n' + data + '\n')
        return None
    (OUT / (name + '.xml')).write_text(data)
    return ET.fromstring(data[data.index('<?xml'):])

def matching(root, label=None, editable=False):
    if root is None:
        return []
    return [n for n in root.iter('node') if n.get('package') == PACKAGE and
            ((editable and n.get('class') == 'android.widget.EditText') or
             (not editable and (n.get('text') == label or n.get('content-desc') == label))) and
            n.get('bounds') not in [None, '[0,0][0,0]']]

def node(label=None, editable=False, timeout=90):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        root = dump()
        found = matching(root, label, editable)
        if found:
            return found[0]
        if matching(root, 'RESTART APP'):
            raise AssertionError('The picker opened the crash screen')
        time.sleep(0.5)
    raise AssertionError('UI control not found: ' + str(label or 'editable field'))

def tap(label=None, editable=False):
    item = node(label, editable)
    left, top, right, bottom = map(int, re.findall(r'\d+', item.get('bounds')))
    adb('shell', 'input', 'tap', str((left + right) // 2), str((top + bottom) // 2))
    time.sleep(0.3)

def launch(legacy=False):
    adb('shell', 'am', 'force-stop', PACKAGE)
    adb('shell', 'am', 'start', '-W', '-n', ACTIVITY, '--ez', 'legacy', str(legacy).lower())
    node('Open picker')
    time.sleep(1)

def screenshot(name):
    result = subprocess.run(['adb', 'exec-out', 'screencap', '-p'], capture_output=True, check=True)
    (OUT / name).write_bytes(result.stdout)

files = list(Path('androidApp/build/outputs/apk/debug').glob('*.apk'))
apk = next((p for p in files if 'universal' in p.name), next((p for p in files if 'x86_64' in p.name), files[0]))
adb('install', '-r', str(apk))
adb('logcat', '-c')
report = {'android_api': adb('shell', 'getprop', 'ro.build.version.sdk').strip(), 'checks': []}
try:
    # Record the old path separately; vendor/IME-specific failures need not reproduce on every device.
    launch(legacy=True)
    tap('Open picker')
    time.sleep(4)
    old = dump('legacy')
    report['legacy_crash_observed'] = bool(matching(old, 'RESTART APP'))
    (OUT / 'legacy-logcat.txt').write_text(adb('logcat', '-d', '-s', 'AndroidRuntime:E'))
    screenshot('legacy.png')
    launch()
    adb('logcat', '-c')
    tap('Open picker')
    tap('YouTube')
    node('YouTube playlist 1')  # Data arrives after opening while the prior search had the IME up.
    report['checks'].append('First-time picker survives delayed local/YouTube data with search keyboard')
    screenshot('picker.png')
    tap('+ Create new playlist')
    tap(editable=True)
    adb('shell', 'input', 'text', 'FirstPlaylist')
    assert node(editable=True).get('text') == 'FirstPlaylist'
    screenshot('create-keyboard.png')
    tap('Create')
    node('CREATED_YOUTUBE_FirstPlaylist')
    tap('Add next song')
    node('QUICK_ADD_YOUTUBE')
    report['checks'].append('Create YouTube destination without a previous choice; subsequent add reuses it')
    tap('Change destination')
    tap('Local')
    tap('Local playlist 1')
    node('CHOSEN_LOCAL')
    tap('Add next song')
    node('QUICK_ADD_LOCAL')
    report['checks'].append('Change destination chooses a local playlist and updates subsequent adds')
    launch()
    tap('Open picker')
    tap('YouTube')
    tap('YouTube playlist 1')
    node('CHOSEN_YOUTUBE')
    report['checks'].append('First-time picker selects an existing YouTube playlist')
    errors = adb('logcat', '-d', '-s', 'AndroidRuntime:E')
    (OUT / 'fixed-logcat.txt').write_text(errors)
    assert 'FATAL EXCEPTION' not in errors, errors
    report['passed'] = True
finally:
    (OUT / 'result.json').write_text(json.dumps(report, indent=2))
    screenshot('final.png')
    (OUT / 'all-logcat.txt').write_text(adb('logcat', '-d'))
print(json.dumps(report, indent=2))
