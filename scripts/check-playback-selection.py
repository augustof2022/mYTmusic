#!/usr/bin/env python3
"""Run picker and real offline playback checks on the Android emulator."""
from pathlib import Path
import json
import subprocess
import time

out = Path('ui-smoke')
out.mkdir(exist_ok=True)
package = 'com.renz.segue.dev'
activity = package + '/com.maxrave.simpmusic.PlaybackSelectionProbeActivity'

def adb(*args, check=True):
    result = subprocess.run(['adb', *args], check=check, capture_output=True, text=True, timeout=50)
    return result.stdout

files = list(Path('androidApp/build/outputs/apk/debug').glob('*.apk'))
assert files, 'No debug APK was built'
apk = next((p for p in files if 'universal' in p.name), None) or next((p for p in files if 'x86_64' in p.name), files[0])
adb('install', '-r', str(apk))
report = {'android_api': adb('shell', 'getprop', 'ro.build.version.sdk').strip(), 'passed': False, 'checks': []}

try:
    adb('shell', 'am', 'force-stop', package)
    adb('logcat', '-c')
    adb('shell', 'am', 'start', '-W', '-n', activity)
    deadline = time.monotonic() + 150
    while time.monotonic() < deadline:
        logs = adb('logcat', '-d', '-s', 'SeguePlaybackProbe:I')
        for line in logs.splitlines():
            if 'SeguePlaybackProbe: FAIL ' in line:
                raise AssertionError(line.split('SeguePlaybackProbe: FAIL ', 1)[1])
            if 'SeguePlaybackProbe: PASS ' in line:
                report['checks'] = json.loads(line.split('SeguePlaybackProbe: PASS ', 1)[1])
                assert len(report['checks']) == 4, report
                report['passed'] = True
                break
        if report['passed']:
            break
        time.sleep(1)
    assert report['passed'], 'Timed out waiting for the offline playback test'
finally:
    (out / 'playback-selection-result.json').write_text(json.dumps(report, indent=2))
    (out / 'playback-selection-logcat.txt').write_text(adb('logcat', '-d', check=False))
print(json.dumps(report, indent=2))
