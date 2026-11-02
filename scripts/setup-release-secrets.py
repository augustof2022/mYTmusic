#!/usr/bin/env python3
"""Run locally beside the private key backup. Secret values go to gh via stdin."""
import base64
import json
import shutil
import subprocess
from pathlib import Path

REPO = "augustof2022/mYTmusic"

def run(*args, data=None):
    result = subprocess.run(["gh", *args], input=data, capture_output=True)
    if result.returncode:
        # Do not print subprocess output from commands handling signing credentials.
        raise RuntimeError("GitHub command failed. Check your login and repository permissions.")
    return result.stdout

def main():
    if not shutil.which("gh"):
        raise RuntimeError("Install GitHub CLI from https://cli.github.com/ and run this script again.")
    if subprocess.run(["gh", "auth", "status"], capture_output=True).returncode:
        subprocess.run(["gh", "auth", "login", "--hostname", "github.com", "--web"], check=True)
    repo = json.loads(run("repo", "view", REPO, "--json", "nameWithOwner,viewerPermission"))
    if repo["nameWithOwner"] != REPO or repo["viewerPermission"] not in {"ADMIN", "MAINTAIN", "WRITE"}:
        raise RuntimeError("Log in with an account allowed to manage this repository's Actions secrets.")
    root = Path(__file__).resolve().parent
    key = root / "segue-release.jks"
    cfg = json.loads((root / "signing.json").read_text())
    values = {
        "SEGUE_KEYSTORE_BASE64": base64.b64encode(key.read_bytes()).decode(),
        "SEGUE_KEYSTORE_PASSWORD": cfg["store_password"],
        "SEGUE_KEY_ALIAS": cfg["alias"],
        "SEGUE_KEY_PASSWORD": cfg["key_password"],
    }
    print("Saving four signing credentials directly to " + REPO + " Actions secrets.")
    for name, value in values.items():
        run("secret", "set", name, "--repo", REPO, data=value.encode())
        print("Saved " + name)
    run("workflow", "run", "segue-release.yml", "--repo", REPO, "--ref", "main",
        "-f", "validation_only=false")
    print("Signing/release run started. Check:")
    print("https://github.com/" + REPO + "/actions/workflows/segue-release.yml")
    print("After a successful test, future newer main builds can be signed and published automatically.")

if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print("Setup stopped: " + str(error))
        raise SystemExit(1)
