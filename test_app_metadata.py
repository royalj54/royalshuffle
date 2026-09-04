import unittest
from unittest.mock import Mock, patch

try:
    import tkinter  # noqa: F401
except ModuleNotFoundError as exc:
    if exc.name == "tkinter":
        raise unittest.SkipTest("Tkinter unavailable; skipping GUI tests") from exc
    raise

import ui
from app_metadata import APP_VERSION, MANAGED_PLAYLIST_DESCRIPTION


class AppMetadataTests(unittest.TestCase):
    def test_application_version_is_current_python_release(self):
        self.assertEqual(APP_VERSION, "0.5.0rc1")

    def test_source_runtime_uses_platform_neutral_version(self):
        with patch.object(ui.sys, "frozen", False, create=True):
            self.assertEqual(ui.application_version_text(), "v0.5.0rc1")

    @patch("ui.add_reviewed_legacy_playlist_id")
    @patch("ui.add_managed_playlist_id")
    @patch("ui.load_reviewed_legacy_playlist_ids", return_value=set())
    @patch("ui.load_managed_playlist_ids", return_value=set())
    @patch("ui.messagebox.askyesno", return_value=True)
    def test_legacy_recovery_uses_canonical_marker(
        self,
        ask_yes_no,
        _managed_ids,
        _reviewed_ids,
        add_managed,
        add_reviewed,
    ):
        playlist = {
            "id": "playlist-id",
            "name": "Recovered",
            "description": MANAGED_PLAYLIST_DESCRIPTION,
        }

        ui.review_legacy_playlists([playlist], Mock())

        ask_yes_no.assert_called_once()
        add_managed.assert_called_once_with("playlist-id")
        add_reviewed.assert_called_once_with("playlist-id")


if __name__ == "__main__":
    unittest.main()
