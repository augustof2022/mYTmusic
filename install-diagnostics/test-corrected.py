import subprocess
from pathlib import Path

def run(label, *args):
    result = subprocess.run(args, capture_output=True, text=True, timeout=180)
    report = f"{label}: exit={result.returncode}\n{result.stdout}\n{result.stderr}\n"
    print(report)
    with Path("diagnostic/install-result.txt").open("a") as file:
        file.write(report)
    if result.returncode:
        raise RuntimeError(f"{label} failed")
    return result.stdout

run("Install previous Segue", "adb", "install", "diagnostic/previous.apk")
previous = run("Check previous version", "adb", "shell", "dumpsys", "package", "com.renz.segue")
assert "versionCode=65" in previous and "versionName=2.4.4" in previous
run("Install corrected Segue over the previous version", "adb", "install", "-r", "diagnostic/corrected.apk")
updated = run("Check updated version", "adb", "shell", "dumpsys", "package", "com.renz.segue")
assert "versionCode=66" in updated and "versionName=2.4.5" in updated
run("Launch corrected app", "adb", "shell", "am", "start", "-W", "-n", "com.renz.segue/com.maxrave.simpmusic.MainActivity")
print("Previous-to-corrected Android update installed and launched successfully")
