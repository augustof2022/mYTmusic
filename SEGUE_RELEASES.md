# Segue Android releases

Segue checks the latest stable GitHub release in `augustof2022/mYTmusic`.
Pushing code alone does not create an update: the release must be published
and contain the signed, universal Android APK named exactly `Segue-Android.apk`.
Drafts, prereleases, malformed version tags and missing or misdirected APK
assets are ignored. The installed version must be older than the release.

For each release:

1. Increase `version-name` (for example, `2.4.6`) and `version-code` in
   `gradle/libs.versions.toml`; commit the source and wait for its Android
   build, checks and playback test to pass.
2. Sign the build with the existing private Segue release key. Never commit
   the key or passwords to GitHub. Verify package `com.renz.segue`, the
   version, and the certificate against the existing installed build.
3. In this repository, create a GitHub release tagged `v2.4.6` (substitute
   the version from step 1) at the commit that was built. Upload the signed
   universal APK as `Segue-Android.apk`; add release notes and publish it
   as a regular release, not a draft or prerelease.

When auto-checking is enabled, Segue checks during startup and offers the
download if the release is newer. Android asks the user to approve installation;
the app does not install updates silently. A missing release or an offline
connection leaves the app usable. Users can also check in Settings.

The first version with this updater still needs to be installed manually over
the previous Segue version; older builds cannot use a feature they do not have.
