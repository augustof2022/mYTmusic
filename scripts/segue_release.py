#!/usr/bin/env python3
"""Sign trusted Segue Android builds and publish verified GitHub release assets."""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import subprocess
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

REPOSITORY = "augustof2022/mYTmusic"
CERTIFICATE = "e50ff7735f171b3f2db5bbad079f8872839c94d4d7d49bbd0714bf02dbf4872c"
SIGNER_SHA256 = "3716d9311e55d2b0918a2fd9d54ba9e406c5f6abeea700b287f11259bc163dec"
SECRET_NAMES = ("SEGUE_KEYSTORE_BASE64", "SEGUE_KEYSTORE_PASSWORD", "SEGUE_KEY_ALIAS", "SEGUE_KEY_PASSWORD")
API = f"https://api.github.com/repos/{REPOSITORY}"

def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()

def request(path: str, token: str, *, method="GET", data=None, content_type="application/json"):
    url = path if path.startswith("https://") else API + path
    parsed = urllib.parse.urlparse(url)
    if parsed.hostname not in {"api.github.com", "uploads.github.com"}:
        raise RuntimeError("Unexpected GitHub API host")
    headers = {"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json",
               "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "Segue-release"}
    if data is not None:
        if not isinstance(data, bytes):
            data = json.dumps(data).encode()
        headers["Content-Type"] = content_type
    try:
        with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers, method=method), timeout=180) as response:
            raw = response.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        if error.code == 404 and method == "GET":
            return None
        raise RuntimeError(f"GitHub {method} failed (HTTP {error.code})") from None

def verify_build_run(run: dict) -> None:
    if not run or run.get("repository", {}).get("full_name") != REPOSITORY:
        raise RuntimeError("The build must belong to this repository")
    if run.get("head_repository", {}).get("full_name") != REPOSITORY:
        raise RuntimeError("Fork builds cannot be signed")
    if run.get("head_branch") != "main" or run.get("event") not in {"push", "workflow_dispatch"}:
        raise RuntimeError("Only trusted main-branch builds can be signed")
    if run.get("path", "").split("@")[0] != ".github/workflows/build-apk.yml":
        raise RuntimeError("Unexpected build workflow")
    if run.get("status") != "completed" or run.get("conclusion") != "success":
        raise RuntimeError("The Android build and its tests must succeed first")
    if not re.fullmatch(r"[0-9a-f]{40}", run.get("head_sha", "")):
        raise RuntimeError("Invalid build source commit")

def resolve_run(event: dict, token: str) -> dict:
    run_id = event.get("workflow_run", {}).get("id") or event.get("inputs", {}).get("build_run_id", "").strip()
    if run_id:
        if not str(run_id).isdigit():
            raise RuntimeError("Build run ID must be numeric")
        run = request(f"/actions/runs/{run_id}", token)
    else:
        response = request("/actions/workflows/build-apk.yml/runs?branch=main&status=success&per_page=1", token)
        runs = (response or {}).get("workflow_runs", [])
        if not runs:
            raise RuntimeError("Run Build mYTmusic Android APK on main first")
        run = runs[0]
    verify_build_run(run)
    return run

def apk_metadata(artifacts: Path, source_sha: str) -> tuple[str, int]:
    info = json.loads((artifacts / "build-info.json").read_text())
    if info.get("commit") != source_sha or sha256(artifacts / "mYTmusic-unsigned.apk") != info.get("sha256"):
        raise RuntimeError("APK artifact does not match the tested source commit")
    if sha256(artifacts / "apksigner.jar") != SIGNER_SHA256:
        raise RuntimeError("Android signing utility changed; review and update the pinned SHA-256")
    badging = (artifacts / "apk-badging.txt").read_text()
    match = re.search(r"package: name='com\.renz\.segue' versionCode='([0-9]+)' versionName='([0-9]+\.[0-9]+\.[0-9]+)'", badging)
    if not match or "application-debuggable" in badging:
        raise RuntimeError("Expected a release APK for com.renz.segue with a stable version")
    tests = (artifacts / "timing-test-result.txt").read_text()
    passed = re.search(r"OK \(([0-9]+) tests\)", tests)
    playback = json.loads((artifacts / "playback-selection-test-result.json").read_text())
    if not passed or int(passed[1]) < 34 or playback.get("passed") is not True or len(playback.get("checks", [])) < 4:
        raise RuntimeError("Required Android playback and regression checks are missing")
    return match[2], int(match[1])

def release_plan(version: str, version_code: int, releases: list[dict]) -> tuple[str, dict | None]:
    numbers = tuple(map(int, version.split(".")))
    same = next((r for r in releases if r["tag_name"] == "v" + version), None)
    if same and not same.get("draft"):
        return "already-published", same
    stable = [r for r in releases if not r.get("draft") and not r.get("prerelease") and re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", r.get("tag_name", ""))]
    if stable:
        latest = max(stable, key=lambda r: tuple(map(int, r["tag_name"][1:].split("."))))
        if numbers <= tuple(map(int, latest["tag_name"][1:].split("."))):
            return "older-version", None
        marker = re.search(r"<!-- segue-version-code: ([0-9]+) -->", latest.get("body") or "")
        previous_code = int(marker[1]) if marker else (66 if latest["tag_name"] == "v2.4.5" else None)
        if previous_code is None or version_code <= previous_code:
            raise RuntimeError("Increase version-code as well as version-name before releasing")
    elif numbers <= (2, 4, 5) or version_code <= 66:
        raise RuntimeError("New automatic releases must advance beyond the installed v2.4.5 baseline")
    return "publish", same

def sign_apk(artifacts: Path, output: Path, source_sha: str, secrets: dict) -> dict:
    version, version_code = apk_metadata(artifacts, source_sha)
    if any(not secrets.get(name) for name in SECRET_NAMES):
        raise RuntimeError("Signing is not configured. Run the private setup package; see SEGUE_RELEASES.md")
    try:
        key_bytes = base64.b64decode(secrets["SEGUE_KEYSTORE_BASE64"].strip(), validate=True)
    except ValueError:
        raise RuntimeError("SEGUE_KEYSTORE_BASE64 is not valid Base64") from None
    if not key_bytes or len(key_bytes) > 36_000:
        raise RuntimeError("Unexpected signing key size")
    output.mkdir(parents=True, exist_ok=True)
    apk = output / "Segue-Android.apk"
    jar = artifacts / "apksigner.jar"
    # Match the release signing method that installed successfully on the HONOR phone.
    with tempfile.TemporaryDirectory(prefix="segue-key-") as directory:
        key = Path(directory) / "release.jks"
        key.write_bytes(key_bytes)
        key.chmod(0o600)
        env = {k: v for k, v in os.environ.items() if k not in SECRET_NAMES}
        env["SEGUE_STORE_PASSWORD"] = secrets["SEGUE_KEYSTORE_PASSWORD"]
        env["SEGUE_KEY_PASSWORD"] = secrets["SEGUE_KEY_PASSWORD"]
        signed = subprocess.run(["java", "-jar", str(jar), "sign", "--ks", str(key),
            "--ks-key-alias", secrets["SEGUE_KEY_ALIAS"], "--ks-pass", "env:SEGUE_STORE_PASSWORD",
            "--key-pass", "env:SEGUE_KEY_PASSWORD", "--v4-signing-enabled", "false",
            "--out", str(apk), str(artifacts / "mYTmusic-unsigned.apk")],
            env=env, capture_output=True, text=True)
        if signed.returncode:
            raise RuntimeError("APK signing failed; check the configured key and password secrets")
    verification = subprocess.run(["java", "-jar", str(jar), "verify", "--verbose", "--print-certs", str(apk)],
                                  check=True, capture_output=True, text=True).stdout.lower()
    if CERTIFICATE not in verification or "verified using v2 scheme (apk signature scheme v2): true" not in verification:
        raise RuntimeError("The APK is not signed with the existing Segue release identity")
    legacy = subprocess.run(["java", "-jar", str(jar), "verify", "--verbose", "--min-sdk-version", "23", str(apk)],
                            check=True, capture_output=True, text=True).stdout.lower()
    if "verified using v1 scheme (jar signing): true" not in legacy:
        raise RuntimeError("The APK lacks the previous release's compatibility signature")
    with zipfile.ZipFile(apk) as z:
        if z.testzip():
            raise RuntimeError("Signed APK archive integrity failed")
    manifest = {"version": version, "version_code": version_code, "package": "com.renz.segue",
                "source_commit": source_sha, "sha256": sha256(apk), "size_bytes": apk.stat().st_size,
                "certificate_sha256": CERTIFICATE}
    (output / "segue-release.json").write_text(json.dumps(manifest, indent=2) + "\n")
    archive = output / "Segue-Android-Verified.zip"
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_STORED) as z:
        z.write(apk, "Segue-Android.apk")
        z.writestr("SHA256.txt", manifest["sha256"] + "  Segue-Android.apk\n")
    with zipfile.ZipFile(archive) as z:
        if z.testzip() or hashlib.sha256(z.read("Segue-Android.apk")).hexdigest() != manifest["sha256"]:
            raise RuntimeError("Verified ZIP did not preserve the signed APK")
    (output / "SHA256SUMS.txt").write_text(
        manifest["sha256"] + "  Segue-Android.apk\n" + sha256(archive) + "  Segue-Android-Verified.zip\n")
    return manifest

def publish(output: Path, manifest: dict, token: str, draft: dict | None) -> str:
    source = manifest["source_commit"]
    body = f"<!-- segue-version-code: {manifest['version_code']} -->\n\nInstall over your existing Segue app. The APK uses the same release signing identity. A ZIP copy and SHA-256 checksums are also provided."
    if draft:
        if draft.get("target_commitish") != source or not draft.get("draft"):
            raise RuntimeError("Existing draft targets a different build; review it before publishing")
        release = draft
    else:
        release = request("/releases", token, method="POST", data={"tag_name": "v" + manifest["version"],
            "target_commitish": source, "name": "Segue v" + manifest["version"], "draft": True,
            "prerelease": False, "generate_release_notes": True, "body": body})
    upload_base = release["upload_url"].split("{")[0]
    if not upload_base.startswith(f"https://uploads.github.com/repos/{REPOSITORY}/releases/"):
        raise RuntimeError("Unexpected asset upload destination")
    for name, mime in [("Segue-Android.apk", "application/vnd.android.package-archive"),
                       ("Segue-Android-Verified.zip", "application/zip"),
                       ("segue-release.json", "application/json"), ("SHA256SUMS.txt", "text/plain")]:
        for asset in release.get("assets", []):
            if asset["name"] == name:
                request(f"/releases/assets/{asset['id']}", token, method="DELETE")
        path = output / name
        asset = request(upload_base + "?name=" + urllib.parse.quote(name), token,
                        method="POST", data=path.read_bytes(), content_type=mime)
        if asset.get("size") != path.stat().st_size or asset.get("digest") != "sha256:" + sha256(path):
            raise RuntimeError("Uploaded release asset did not match; release remains a draft")
    # Publish only after every uploaded asset has the expected bytes.
    published = request(f"/releases/{release['id']}", token, method="PATCH",
                        data={"draft": False, "prerelease": False, "make_latest": "true"})
    return published["html_url"]

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["resolve", "release"])
    parser.add_argument("--artifacts", type=Path, default=Path("apk-artifacts"))
    parser.add_argument("--output", type=Path, default=Path("signed-apk"))
    args = parser.parse_args()
    if os.environ.get("GITHUB_REPOSITORY") != REPOSITORY:
        raise RuntimeError("This signing workflow is restricted to the Segue repository")
    token = os.environ.get("GH_TOKEN", "")
    if args.command == "resolve":
        event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
        run = resolve_run(event, token)
        with Path(os.environ["GITHUB_OUTPUT"]).open("a") as out:
            out.write(f"run_id={run['id']}\nsource_sha={run['head_sha']}\n")
        print(f"Using successful main build {run['id']} at {run['head_sha']}")
        return
    source = os.environ["SEGUE_SOURCE_SHA"]
    version, code = apk_metadata(args.artifacts, source)
    validate_only = os.environ.get("SEGUE_VALIDATE_ONLY", "false").lower() == "true"
    releases = request("/releases?per_page=100", token) or []
    plan, draft = ("validation", None) if validate_only else release_plan(version, code, releases)
    if plan == "older-version":
        print(f"No new release: {version} is {plan.replace('-', ' ')}")
        return
    manifest = sign_apk(args.artifacts, args.output, source, os.environ)
    print(f"Signed Segue {version}; certificate matched; APK SHA-256: {manifest['sha256']}")
    if validate_only or plan == "already-published":
        print("Signing validation passed. Existing published versions were not replaced.")
    else:
        print("Published: " + publish(args.output, manifest, token, draft))

if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Never dump secrets, request headers, signing configuration, or command environments.
        print("Release stopped: " + str(error))
        raise SystemExit(1)
