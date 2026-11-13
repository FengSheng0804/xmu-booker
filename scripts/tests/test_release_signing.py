import base64
import contextlib
import importlib.util
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("release_signing", Path(__file__).resolve().parents[1] / "prepare-release-signing.py")
signing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(signing)


class ReleaseSigningTest(unittest.TestCase):
    def test_missing_and_invalid_secrets_do_not_write_signing_files(self):
        with tempfile.TemporaryDirectory() as temp:
            with patch.dict(os.environ, {"RUNNER_TEMP": temp}, clear=True):
                with self.assertRaises(SystemExit):
                    signing.main()
            values = dict.fromkeys(signing.NAMES, "synthetic")
            values.update(RUNNER_TEMP=temp, XMU_ANDROID_KEYSTORE_BASE64="invalid*")
            with patch.dict(os.environ, values, clear=True):
                with self.assertRaises(SystemExit):
                    signing.main()
            self.assertFalse((Path(temp) / "xmu-release-signing").exists())

    def test_valid_configuration_is_private_escaped_and_cleanup_keeps_other_files(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            unrelated = root / "other.txt"
            unrelated.write_text("keep", encoding="ascii")
            values = dict(RUNNER_TEMP=temp, GITHUB_ENV=str(root / "github-env"),
                          XMU_ANDROID_KEYSTORE_BASE64=base64.b64encode(b"synthetic-key").decode(),
                          XMU_ANDROID_STORE_PASSWORD=" space\\:=\n\u4e2d", XMU_ANDROID_KEY_ALIAS="synthetic",
                          XMU_ANDROID_KEY_PASSWORD="synthetic-password")
            output = io.StringIO()
            with patch.dict(os.environ, values, clear=True), contextlib.redirect_stdout(output):
                signing.main()
                directory = root / "xmu-release-signing"
                self.assertEqual((directory / "release.p12").read_bytes(), b"synthetic-key")
                text = (directory / "signing.properties").read_text(encoding="ascii")
                self.assertIn("storePassword=\\ space\\\\\\:\\=\\n\\u4e2d\n", text)
                self.assertIn("XMU_ANDROID_KEY_PROPERTIES=", (root / "github-env").read_text())
                self.assertNotIn("synthetic-password", output.getvalue())
                signing.main(clean=True)
                self.assertFalse(directory.exists())
                self.assertTrue(unrelated.exists())


if __name__ == "__main__":
    unittest.main()
