# Segue repository instructions

Read CLAUDE.md for project architecture. The personal Android package is
`com.renz.segue`; retain the existing GPL notices and credits.

## Installable updates

- Never ask a user to paste private signing keys or passwords in chat.
  Never create a replacement signing identity for an existing Segue update.
- The existing release certificate SHA-256 is
  `e50ff7735f171b3f2db5bbad079f8872839c94d4d7d49bbd0714bf02dbf4872c`.
- GitHub Actions signs through repository secrets. See SEGUE_RELEASES.md.
  Code changes do not require an agent to download the private key.
- For a new release, increase BOTH `version-name` and `version-code` in
  gradle/libs.versions.toml. Merge/push the authorized change to main.
  The Android build and playback tests must pass; Sign and release Segue
  then signs and publishes a newer version automatically.
- Monitor both workflows. A code commit or unsigned artifact is not a
  completed release. Return the actual APK/ZIP asset link only after publication.
- To share an already published download, use its existing release assets.
  No new signing step or signing credential is needed.
- Existing published versions are not overwritten by the release workflow.
  If the signer reports missing secrets, use the one-time private setup package
  and repository Settings > Secrets and variables > Actions; do not put
  secrets in commits, issue comments, build logs, or chat messages.
- Run `python3 -m unittest discover -s scripts/tests -p test_segue_release.py -v`
  for release automation changes. App builds remain appropriate for actual
  app changes and final release verification.
