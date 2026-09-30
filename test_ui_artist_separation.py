import unittest
from unittest.mock import patch

from artist_separation import ArtistSeparationError
from royalshuffle import RoyalShuffleResult
import test_ui_playlist_discovery as discovery


class ArtistSeparationUiTests(unittest.TestCase):
    setUp = discovery.PlaylistDiscoveryTests.setUp
    search = discovery.PlaylistDiscoveryTests.search
    assert_actions = discovery.PlaylistDiscoveryTests.assert_actions

    def test_checkbox_defaults_off_without_callbacks_or_persistence(self):
        self.assertIs(self.artist_variable_type.call_args.kwargs["value"], False)
        self.assertEqual(self.artist_checkbox.call_args.kwargs["text"], "Artist Separation")
        self.assertIs(self.artist_checkbox.call_args.kwargs["variable"], self.artist_variable)
        self.assertNotIn("command", self.artist_checkbox.call_args.kwargs)
        self.artist_variable.trace_add.assert_not_called()
        self.client.reset_mock()
        self.saved.reset_mock()
        self.highlight = (0,)
        self.artist_variable.get.return_value = True
        self.search("")
        self.assertEqual(self.highlight, (5,))
        self.assertEqual(self.client.mock_calls, [])
        self.assertEqual(self.saved.mock_calls, [])

    @patch("ui.simpledialog.askstring", return_value="Output")
    @patch("ui.royal_shuffle")
    def test_enabled_for_all_sessions_and_off_omits_option(self, shuffle, ask):
        for enabled in (False, True):
            for option in ("Full Playlist", "60M"):
                self.artist_variable.get.return_value = enabled
                self.session_variable.get.return_value = option
                shuffle.return_value = RoyalShuffleResult("Zulu", "z", "Output", "out", 2, 2, 0, "updated",
                                                         duration_ms=1800000)
                self.shuffle.invoke()
                self.assertEqual(shuffle.call_args.args[1]["id"], "z")
                self.assertEqual(shuffle.call_args.kwargs.get("artist_separation", False), enabled)
                if not enabled:
                    self.assertNotIn("artist_separation", shuffle.call_args.kwargs)
                self.assertNotIn("collision", self.status.config.call_args.kwargs["text"].lower())

    @patch("ui.royal_shuffle")
    @patch("ui.simpledialog.askstring")
    def test_checkbox_and_session_snapshot_precede_dialog(self, ask, shuffle):
        self.artist_variable.get.return_value = True
        def during_dialog(*args, **kwargs):
            self.artist_variable.get.return_value = False
            self.session_variable.get.return_value = "60M"
            self.highlight = (0,)
            return "Output"
        ask.side_effect = during_dialog
        def create(*args, **kwargs):
            name = kwargs["output_name_callback"](kwargs["output_playlist_name"])
            return RoyalShuffleResult("Zulu", "z", name, "out", 1, 1, 0, "created")
        shuffle.side_effect = create
        self.shuffle.invoke()
        self.assertTrue(shuffle.call_args.kwargs["artist_separation"])
        self.assertNotIn("session_minutes", shuffle.call_args.kwargs)
        self.assertEqual(shuffle.call_args.args[1]["id"], "z")

    @patch("ui.royal_shuffle")
    def test_enabled_does_not_bypass_hidden_selection(self, shuffle):
        self.artist_variable.get.return_value = True
        self.session_variable.get.return_value = "60M"
        self.search("alpha")
        self.shuffle.invoke()
        shuffle.assert_not_called()
        self.assert_actions("disabled")
        self.search("")
        self.assert_actions("normal")
        self.assertEqual(self.highlight, (5,))
        self.saved.write_text.assert_not_called()

    @patch("ui.export_playlist_csv", return_value=1)
    @patch("ui.choose_csv_destination", return_value="output.csv")
    def test_export_ignores_checkbox(self, choose, export):
        self.artist_variable.get.return_value = True
        self.session_variable.get.return_value = "60M"
        self.export.invoke()
        self.client.get_playlist_items.assert_called_once_with("z")
        export.assert_called_once_with("output.csv", self.client.get_playlist_items.return_value)

    @patch("ui.royal_shuffle", side_effect=ArtistSeparationError(
        "Missing first-credited Spotify artist ID. Disable Artist Separation to use ordinary True Random."))
    def test_metadata_error_is_actionable_and_actions_recover(self, shuffle):
        self.artist_variable.get.return_value = True
        self.session_variable.get.return_value = "60M"
        self.shuffle.invoke()
        self.assertIn("Disable Artist Separation", self.status.config.call_args.kwargs["text"])
        self.assertEqual(self.shuffle.config.call_args.kwargs["state"], "normal")
