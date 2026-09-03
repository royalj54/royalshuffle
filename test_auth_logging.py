import tempfile
import unittest
import json
import os
from pathlib import Path
from unittest.mock import patch

import auth


class AuthLoggingTests(unittest.TestCase):
    def setUp(self):
        auth._active_debug_log_file = None

    def tearDown(self):
        auth._active_debug_log_file = None

    def test_diagnostics_log_is_appended_and_legacy_log_is_untouched(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            diagnostics = root / "Diagnostics"
            diagnostics.mkdir()
            destination_log = diagnostics / "royalshuffle_debug.log"
            destination_log.write_text("existing diagnostics\n")
            legacy_log = root / ".royalshuffle_debug.log"
            legacy_log.write_text("legacy diagnostics\n")

            with patch.object(auth, "LEGACY_LOG_FILE", legacy_log), patch(
                "auth.ensure_diagnostics_folder",
                return_value=diagnostics,
            ):
                auth.log_debug("new message")

            self.assertTrue(
                destination_log.read_text().startswith(
                    "existing diagnostics\n"
                )
            )
            self.assertIn(" new message\n", destination_log.read_text())
            self.assertEqual(
                legacy_log.read_text(),
                "legacy diagnostics\n",
            )

    def test_unavailable_diagnostics_falls_back_to_legacy_log(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            legacy_log = (
                Path(temporary_directory) / ".royalshuffle_debug.log"
            )
            legacy_log.write_text("existing legacy\n")

            with patch.object(auth, "LEGACY_LOG_FILE", legacy_log), patch(
                "auth.ensure_diagnostics_folder",
                return_value=None,
            ):
                auth.log_debug("fallback message")

            contents = legacy_log.read_text()
            self.assertTrue(contents.startswith("existing legacy\n"))
            self.assertIn(" fallback message\n", contents)

    def test_logging_failure_does_not_escape(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            unwritable_destination = root / "not-a-directory"
            unwritable_destination.write_text("blocking file")
            legacy_log = root / "also-a-directory"
            legacy_log.mkdir()

            with patch.object(auth, "LEGACY_LOG_FILE", legacy_log), patch(
                "auth.ensure_diagnostics_folder",
                return_value=unwritable_destination,
            ):
                auth.log_debug("suppressed failure")


class TokenPersistenceTests(unittest.TestCase):
    def test_missing_token_file_returns_none(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            token_file = Path(temporary_directory) / "missing.json"
            with patch.object(auth, "TOKEN_FILE", token_file):
                self.assertIsNone(auth.load_token_data())

    def test_corrupt_token_file_returns_none(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            token_file = Path(temporary_directory) / "token.json"
            token_file.write_text("not json", encoding="utf-8")
            with patch.object(auth, "TOKEN_FILE", token_file), patch(
                "auth.log_debug"
            ):
                self.assertIsNone(auth.load_token_data())

    def test_token_write_is_atomic_and_replaces_existing_data(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            token_file = Path(temporary_directory) / "config" / "token.json"
            token_data = {
                "access_token": "new-access",
                "refresh_token": "new-refresh",
            }
            with patch.object(auth, "TOKEN_FILE", token_file), patch(
                "auth.os.replace",
                wraps=os.replace,
            ) as replace:
                auth.save_token_data(token_data)

            replace.assert_called_once()
            self.assertEqual(
                json.loads(token_file.read_text(encoding="utf-8")),
                token_data,
            )
            self.assertEqual(list(token_file.parent.glob("*.tmp")), [])

    def test_posix_token_write_requests_owner_only_permissions(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            token_file = Path(temporary_directory) / "token.json"
            with patch.object(auth, "TOKEN_FILE", token_file), patch(
                "auth._is_windows",
                return_value=False,
            ), patch("auth.os.chmod") as chmod:
                auth.save_token_data({"access_token": "access"})

            chmod.assert_called_once()
            self.assertEqual(chmod.call_args.args[1], 0o600)


if __name__ == "__main__":
    unittest.main()
