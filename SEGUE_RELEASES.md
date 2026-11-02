# Segue signed updates

The Android build workflow tests main and creates an unsigned candidate.
The **Sign and release Segue** workflow then signs and publishes a newer
version using the existing private release key stored in GitHub Actions
repository secrets. The agent making code changes does not need the key.

## One-time private setup

Download and extract the private signing setup package provided in the
conversation. Keep it private; it contains the release keystore and passwords.
Install GitHub CLI from https://cli.github.com/ on your own computer.

- Windows: run START-WINDOWS.cmd from the extracted folder. It writes and opens\n  SETUP-STATUS.txt, with an explicit success or failure stage. The final keypress\n  only closes the command window. If setup stops, share SETUP-STATUS.txt for help;\n  it records controlled messages and secret names, never private values.
- macOS/Linux: run `python3 setup-release-secrets.py` in that folder.

The setup opens GitHub's sign-in flow when needed, confirms access to
`augustof2022/mYTmusic`, and sends four values directly to GitHub CLI through
stdin (not command arguments or chat):

- SEGUE_KEYSTORE_BASE64
- SEGUE_KEYSTORE_PASSWORD
- SEGUE_KEY_ALIAS
- SEGUE_KEY_PASSWORD

They appear under repository **Settings > Secrets and variables > Actions**.
The setup starts a signing run, which also publishes a pending newer tested
version if available. It verifies signing without replacing an existing
published version when the version has not changed. Wait for that run to pass:
https://github.com/augustof2022/mYTmusic/actions/workflows/segue-release.yml

The setup does not change GitHub repository membership or expose secret
values to future chats. Only use trusted accounts with repository write access;
people allowed to change main's workflow code can influence how secrets are used.

## Each new app release

1. Make and test the app change from any authorized Codex task/account.
2. Increase both version fields in gradle/libs.versions.toml
   (the first automatic update after 2.4.5/66 is at least 2.4.6/67).
3. Push or merge to main. Wait for **Build mYTmusic Android APK** and then
   **Sign and release Segue** to succeed.
4. The release contains Segue-Android.apk, Segue-Android-Verified.zip,
   SHA256SUMS.txt and segue-release.json. The existing published version is
   never overwritten; a code-only commit at the same version is not a new update.

The signer checks the successful main build, source commit, candidate hash,
package/version, regression/playback results, the pinned Android signing tool,
and the existing certificate. Release assets are uploaded as a draft and
checked before publication. Temporary keystore files are deleted by the job.

To retry a signing/publishing failure without rebuilding, use **Run workflow**
on Sign and release Segue, optionally supplying a successful main build run ID.
Build artifacts expire after seven days; run the Android build again if needed.
Use validation_only=true to test signing without creating or replacing a release.

## In-app behavior

Segue checks published stable releases of this repository. It offers only a
newer version containing the exact Segue-Android.apk asset. Auto-check runs at
startup when enabled; Settings also supports a manual check. Android still
requires installation confirmation. A missing release or offline connection
leaves the app usable.
