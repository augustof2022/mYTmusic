import copy
import importlib.util
import json
import io
import contextlib
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("segue_release", Path(__file__).parents[1] / "segue_release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)

class ReleaseChecks(unittest.TestCase):
    def run_info(self):
        return {"id": 123, "repository": {"full_name": release.REPOSITORY},
                "head_repository": {"full_name": release.REPOSITORY}, "head_branch": "main",
                "event": "push", "path": ".github/workflows/build-apk.yml",
                "status": "completed", "conclusion": "success", "head_sha": "a" * 40}

    def baseline(self):
        return [{"tag_name": "v2.4.5", "draft": False, "prerelease": False, "body": ""}]

    def test_successful_main_build_is_accepted(self):
        release.verify_build_run(self.run_info())

    def test_fork_cannot_be_signed(self):
        run = self.run_info(); run["head_repository"]["full_name"] = "other/repo"
        with self.assertRaisesRegex(RuntimeError, "Fork"):
            release.verify_build_run(run)

    def test_branch_or_pull_request_cannot_be_signed(self):
        for field, value in [("head_branch", "feature"), ("event", "pull_request")]:
            run = self.run_info(); run[field] = value
            with self.assertRaises(RuntimeError):
                release.verify_build_run(run)

    def test_failed_build_and_different_workflow_are_rejected(self):
        for field, value in [("conclusion", "failure"), ("path", ".github/workflows/other.yml")]:
            run = self.run_info(); run[field] = value
            with self.assertRaises(RuntimeError):
                release.verify_build_run(run)

    def test_existing_published_version_is_not_overwritten(self):
        self.assertEqual("already-published", release.release_plan("2.4.5", 66, self.baseline())[0])

    def test_older_version_is_not_published(self):
        self.assertEqual("older-version", release.release_plan("2.4.4", 65, self.baseline())[0])

    def test_both_version_fields_must_increase(self):
        with self.assertRaisesRegex(RuntimeError, "version-code"):
            release.release_plan("2.4.6", 66, self.baseline())
        self.assertEqual("publish", release.release_plan("2.4.6", 67, self.baseline())[0])

    def test_future_releases_compare_published_version_code(self):
        previous = [{"tag_name": "v2.4.6", "draft": False, "prerelease": False,
                     "body": "<!-- segue-version-code: 67 -->"}]
        with self.assertRaises(RuntimeError):
            release.release_plan("2.4.7", 67, previous)
        self.assertEqual("publish", release.release_plan("2.4.7", 68, previous)[0])

    def test_empty_release_list_cannot_republish_old_baseline(self):
        with self.assertRaises(RuntimeError):
            release.release_plan("2.4.5", 66, [])

    def test_publication_waits_until_all_assets_match(self):
        calls = []
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ["Segue-Android.apk", "Segue-Android-Verified.zip", "segue-release.json", "SHA256SUMS.txt"]:
                (root / name).write_bytes(b"public test asset")
            manifest = {"source_commit": "a" * 40, "version_code": 67, "version": "2.4.6"}
            def api(path, token, **kwargs):
                calls.append((path, kwargs))
                if path == "/releases":
                    return {"id": 1, "upload_url": f"https://uploads.github.com/repos/{release.REPOSITORY}/releases/1/assets{{?name}}"}
                if kwargs.get("method") == "PATCH":
                    return {"html_url": "https://github.com/" + release.REPOSITORY + "/releases/tag/v2.4.6"}
                body = kwargs["data"]
                return {"size": len(body), "digest": "sha256:" + release.hashlib.sha256(body).hexdigest()}
            with patch.object(release, "request", side_effect=api):
                release.publish(root, manifest, "test", None)
            self.assertTrue(calls[0][1]["data"]["draft"])
            self.assertEqual("PATCH", calls[-1][1]["method"])
            self.assertFalse(calls[-1][1]["data"]["draft"])
            calls.clear()
            def corrupted(path, token, **kwargs):
                if path == "/releases":
                    return api(path, token, **kwargs)
                calls.append((path, kwargs))
                return {"size": 1, "digest": "sha256:wrong"}
            with patch.object(release, "request", side_effect=corrupted):
                with self.assertRaisesRegex(RuntimeError, "remains a draft"):
                    release.publish(root, manifest, "test", None)
            self.assertFalse(any(k.get("method") == "PATCH" for _, k in calls))

    def test_draft_for_different_source_is_not_reused(self):
        with self.assertRaisesRegex(RuntimeError, "different build"):
            release.publish(Path("."), {"source_commit": "a"*40, "version_code": 67},
                            "test", {"draft": True, "target_commitish": "b"*40})

    def test_local_setup_sends_secret_values_only_via_stdin(self):
        path = Path(__file__).parents[1] / "setup-release-secrets.py"
        setup_spec = importlib.util.spec_from_file_location("setup_release", path)
        setup = importlib.util.module_from_spec(setup_spec)
        setup_spec.loader.exec_module(setup)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "signing.json").write_text(json.dumps({
                "store_password": "dummy-store-password", "key_password": "dummy-key-password", "alias": "dummy-alias"}))
            (root / "segue-release.jks").write_bytes(b"dummy-key-bytes")
            calls = []
            def fake_run(args, **kwargs):
                calls.append((args, kwargs))
                content = json.dumps({"nameWithOwner": release.REPOSITORY, "viewerPermission": "ADMIN"}).encode() if args[1:3] == ["repo", "view"] else b""
                return subprocess.CompletedProcess(args, 0, content, b"")
            output = io.StringIO()
            with patch.object(setup, "__file__", str(root / "setup.py")), patch.object(setup.shutil, "which", return_value="/mock/gh"), patch.object(setup.subprocess, "run", side_effect=fake_run), contextlib.redirect_stdout(output):
                setup.main()
            secret_calls = [(a, k) for a, k in calls if a[1:3] == ["secret", "set"]]
            self.assertEqual(4, len(secret_calls))
            for args, kwargs in secret_calls:
                self.assertIsInstance(kwargs["input"], bytes)
                self.assertFalse(any("password" in a or "dummy" in a for a in args))
            self.assertNotIn("dummy-store-password", output.getvalue())
            self.assertNotIn("dummy-key-password", output.getvalue())

if __name__ == "__main__":
    unittest.main()
