#!/usr/bin/env python3
"""Run picker and real offline playback checks on the Android emulator."""
from pathlib import Path
import json
import subprocess
import sys
import time

subprocess.run([sys.executable, 'scripts/check-playlist-picker.py'], check=True)
out = Path('ui-smoke')
out.mkdir(exist_ok=True)
package = 'com.renz.segue.dev'
activity = package + '/com.maxrave.simpmusic.PlaybackSelectionProbeActivity'
report = {'passed': False, 'checks': []}

def adb(*args, check=True):
    result = subprocess.run(['adb', *args], check=check, capture_output=True, text=True, timeout=50)
    return result.stdout

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
