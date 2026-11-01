import subprocess
from pathlib import Path

out = Path("diagnostic")
def run(label, *args, timeout=180):
    r = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    text = f"$ {label}\nexit={r.returncode}\n{r.stdout}\n{r.stderr}\n"
    with (out / "install-results.txt").open("a") as f:
        f.write(text)
    print(text)
    return r

run("device", "adb", "shell", "getprop", "ro.build.version.release")
run("clear logs", "adb", "logcat", "-c")
old = run("install previous", "adb", "install", "-r", "diagnostic/previous.apk")
if old.returncode == 0:
    run("install reported APK as update", "adb", "install", "-r", "diagnostic/Segue-Android.apk")
else:
    print("Previous APK could not be installed; update check cannot run.")
log = subprocess.run(["adb", "logcat", "-d", "-v", "threadtime"], capture_output=True, text=True, timeout=60)
(out / "update-install-log.txt").write_text(log.stdout)
run("uninstall from test emulator", "adb", "uninstall", "com.renz.segue")
run("install reported APK fresh", "adb", "install", "diagnostic/Segue-Android.apk")
