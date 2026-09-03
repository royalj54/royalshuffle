import sys
import types
import unittest
from unittest.mock import Mock, patch

try:
    import requests
except ModuleNotFoundError:
    requests = types.ModuleType("requests")
    requests.HTTPError = type("HTTPError", (Exception,), {})
    requests.get = Mock()
    requests.post = Mock()
    requests.put = Mock()
    sys.modules["requests"] = requests

import ui
from spotify_client import (
    SPOTIFY_DEVELOPER_QUOTA_MESSAGE,
    SpotifyQuotaExceededError,
)
from playlist_import import (
    PlaylistImportRow,
    PlaylistImportValidationError,
    PlaylistImportValidationIssue,
)
from playlist_import_workflow import (
    PlaylistImportPartialWriteError,
    PlaylistImportResult,
)


ROW = PlaylistImportRow(
    line_number=2,
    uri="spotify:track:" + "1" * 22,
    track_id="1" * 22,
)


class CsvImportUiTests(unittest.TestCase):
    def setUp(self):
        self.parent = Mock()
        self.spotify = Mock()
        self.status = Mock()
        self.button = Mock()

    @patch("ui.tk.Button")
    def test_import_button_starts_disabled(self, button_type):
        button = button_type.return_value
        command = Mock()

        result = ui.create_import_csv_button(self.parent, command)

        self.assertIs(result, button)
        self.assertEqual(button_type.call_args.kwargs["state"], "disabled")
        self.assertIs(button_type.call_args.kwargs["command"], command)
        button.pack.assert_called_once_with(side="left", padx=5)

    @patch("ui.choose_csv_source", return_value="")
    def test_file_dialog_cancellation_makes_no_changes(self, _choose):
        result = ui.import_csv_playlist(
            self.parent, self.spotify, self.status, self.button
        )

        self.assertIsNone(result)
        self.spotify.get_track.assert_not_called()
        self.spotify.create_playlist.assert_not_called()
        self.button.config.assert_not_called()

    @patch("ui.create_imported_playlist")
    @patch("ui.simpledialog.askstring", return_value=None)
    @patch("ui.preflight_playlist_import", return_value=Mock())
    @patch("ui.parse_playlist_csv", return_value=(ROW,))
    @patch("ui.choose_csv_source", return_value="playlist.csv")
    def test_validation_summary_then_name_cancellation_creates_nothing(
        self,
        _choose,
        _parse,
        _preflight,
        _askstring,
        create_imported_playlist,
    ):
        ui.import_csv_playlist(
            self.parent, self.spotify, self.status, self.button
        )

        self.assertIn(
            unittest.mock.call(text="1 tracks found, 1 valid"),
            self.status.config.call_args_list,
        )
        create_imported_playlist.assert_not_called()
        self.assertEqual(self.button.config.call_args_list[-1].kwargs["state"], "normal")

    @patch("ui.create_imported_playlist")
    @patch("ui.simpledialog.askstring", return_value="   ")
    @patch("ui.preflight_playlist_import", return_value=Mock())
    @patch("ui.parse_playlist_csv", return_value=(ROW,))
    @patch("ui.choose_csv_source", return_value="playlist.csv")
    def test_blank_playlist_name_creates_nothing(
        self, _choose, _parse, _preflight, _askstring, create_imported_playlist
    ):
        ui.import_csv_playlist(
            self.parent, self.spotify, self.status, self.button
        )

        create_imported_playlist.assert_not_called()
        self.status.config.assert_any_call(text="Playlist name cannot be empty")

    @patch("ui.messagebox.showerror")
    @patch("ui.parse_playlist_csv")
    @patch("ui.choose_csv_source", return_value="playlist.csv")
    def test_row_numbered_validation_errors_are_displayed(
        self, _choose, parse_playlist_csv, showerror
    ):
        parse_playlist_csv.side_effect = PlaylistImportValidationError([
            PlaylistImportValidationIssue(7, "blank_uri", "Spotify URI is blank."),
            PlaylistImportValidationIssue(12, "local_uri", "Local files cannot be imported."),
        ])

        ui.import_csv_playlist(
            self.parent, self.spotify, self.status, self.button
        )

        message = showerror.call_args.args[1]
        self.assertIn("Row 7: Spotify URI is blank.", message)
        self.assertIn("Row 12: Local files cannot be imported.", message)
        self.spotify.create_playlist.assert_not_called()

    @patch("ui.log_debug")
    @patch("ui.preflight_playlist_import", side_effect=RuntimeError("secret"))
    @patch("ui.parse_playlist_csv", return_value=(ROW,))
    @patch("ui.choose_csv_source", return_value="playlist.csv")
    def test_catalog_operational_error_is_sanitized(
        self, _choose, _parse, _preflight, log_debug
    ):
        ui.import_csv_playlist(
            self.parent, self.spotify, self.status, self.button
        )

        self.status.config.assert_any_call(
            text="CSV import failed. No playlist was created."
        )
        self.assertNotIn("secret", log_debug.call_args.args[0])

    @patch("ui.create_imported_playlist")
    @patch("ui.simpledialog.askstring", return_value="Imported")
    @patch("ui.preflight_playlist_import", return_value=Mock())
    @patch("ui.parse_playlist_csv", return_value=(ROW,))
    @patch("ui.choose_csv_source", return_value="playlist.csv")
    def test_success_message(
        self, _choose, _parse, _preflight, _askstring, create_imported_playlist
    ):
        create_imported_playlist.return_value = PlaylistImportResult(
            "playlist-id", "Imported", 1
        )

        ui.import_csv_playlist(
            self.parent, self.spotify, self.status, self.button
        )

        self.status.config.assert_any_call(text="Created: Imported • 1 tracks")

    @patch("ui.messagebox.showerror")
    @patch("ui.create_imported_playlist")
    @patch("ui.simpledialog.askstring", return_value="Partial")
    @patch("ui.preflight_playlist_import", return_value=Mock())
    @patch("ui.parse_playlist_csv", return_value=(ROW,))
    @patch("ui.choose_csv_source", return_value="playlist.csv")
    def test_partial_write_message_reports_counts_without_rollback(
        self,
        _choose,
        _parse,
        _preflight,
        _askstring,
        create_imported_playlist,
        showerror,
    ):
        create_imported_playlist.side_effect = PlaylistImportPartialWriteError(
            "playlist-id", "Partial", 100, 150, RuntimeError("secret")
        )

        ui.import_csv_playlist(
            self.parent, self.spotify, self.status, self.button
        )

        self.status.config.assert_any_call(
            text="Partially created: Partial • 100/150 tracks added"
        )
        self.assertIn("100 of 150", showerror.call_args.args[1])
        self.assertNotIn("secret", showerror.call_args.args[1])
        self.spotify.clear_playlist.assert_not_called()

    @patch("ui.messagebox.showerror")
    @patch("ui.create_imported_playlist")
    @patch("ui.simpledialog.askstring", return_value="Partial")
    @patch("ui.preflight_playlist_import", return_value=Mock())
    @patch("ui.parse_playlist_csv", return_value=(ROW,))
    @patch("ui.choose_csv_source", return_value="playlist.csv")
    def test_partial_write_quota_message_preserves_partial_details(
        self,
        _choose,
        _parse,
        _preflight,
        _askstring,
        create_imported_playlist,
        showerror,
    ):
        quota_error = SpotifyQuotaExceededError(
            SPOTIFY_DEVELOPER_QUOTA_MESSAGE
        )
        create_imported_playlist.side_effect = PlaylistImportPartialWriteError(
            "playlist-id", "Partial", 100, 150, quota_error
        )

        ui.import_csv_playlist(
            self.parent, self.spotify, self.status, self.button
        )

        status_message = self.status.config.call_args_list[-1].kwargs["text"]
        self.assertIn(SPOTIFY_DEVELOPER_QUOTA_MESSAGE, status_message)
        self.assertIn("100/150 tracks added", status_message)
        message = showerror.call_args.args[1]
        self.assertIn("100 of 150", message)
        self.assertIn(SPOTIFY_DEVELOPER_QUOTA_MESSAGE, message)
        self.spotify.clear_playlist.assert_not_called()

    @patch("ui.preflight_playlist_import")
    @patch("ui.parse_playlist_csv", return_value=(ROW,))
    @patch("ui.choose_csv_source", return_value="playlist.csv")
    def test_preflight_quota_message_is_explicit(
        self,
        _choose,
        _parse,
        preflight,
    ):
        preflight.side_effect = SpotifyQuotaExceededError(
            SPOTIFY_DEVELOPER_QUOTA_MESSAGE
        )

        ui.import_csv_playlist(
            self.parent, self.spotify, self.status, self.button
        )

        self.status.config.assert_any_call(
            text=SPOTIFY_DEVELOPER_QUOTA_MESSAGE
        )

    @patch("ui.LAST_PLAYLIST_FILE")
    @patch("ui.load_managed_playlist_ids", return_value=set())
    @patch("ui.review_legacy_playlists")
    @patch("ui.SpotifyClient")
    def test_connection_enables_import_without_enabling_export(
        self,
        client_type,
        _review,
        _managed_ids,
        last_playlist_file,
    ):
        client_type.return_value.get_playlists.return_value = []
        last_playlist_file.exists.return_value = False
        import_button = Mock()
        export_button = Mock()

        ui.load_spotify_session(
            "token",
            Mock(),
            Mock(),
            Mock(),
            [],
            {},
            {"playlist": None},
            Mock(),
            export_button,
            import_button,
        )

        import_button.config.assert_called_once_with(state="normal")
        export_button.config.assert_not_called()


if __name__ == "__main__":
    unittest.main()
