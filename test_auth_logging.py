import tempfile
import unittest
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


if __name__ == "__main__":
    unittest.main()
