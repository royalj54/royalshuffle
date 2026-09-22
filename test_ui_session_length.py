import unittest
from unittest.mock import Mock, patch

import test_ui_playlist_discovery as discovery
from royalshuffle import SessionLengthError, RoyalShufflePartialWriteError, RoyalShuffleResult


class SessionLengthUiTests(unittest.TestCase):
    setUp = discovery.PlaylistDiscoveryTests.setUp
    search = discovery.PlaylistDiscoveryTests.search
    assert_actions = discovery.PlaylistDiscoveryTests.assert_actions

    def test_default_and_options(self):
        import ui
        self.assertEqual(ui.tk.StringVar.call_args.kwargs["value"], "Full Playlist")
        self.assertEqual(self.option_menu.call_args.args[2:], ("Full Playlist", "30M", "60M", "90M"))

    @patch("ui.simpledialog.askstring")
    @patch("ui.royal_shuffle")
    def test_timed_options_use_committed_source_and_report_duration(self, shuffle, ask):
        for minutes in (30, 60, 90):
            self.client.reset_mock()
            self.saved.reset_mock()
            self.session_variable.get.return_value = f"{minutes}M"
            self.highlight = (0,)
            shuffle.return_value = RoyalShuffleResult("Zulu", "z", "Output", "out", 1, 1, 0, "created",
                                                     minutes * 60000, 60000, True)
            self.shuffle.invoke()
            self.assertEqual(shuffle.call_args.args[1]["id"], "z")
            self.assertEqual(shuffle.call_args.kwargs["session_minutes"], minutes)
            message = self.status.config.call_args.kwargs["text"]
            self.assertIn(f"Requested {minutes}M", message)
            self.assertIn("duration 1.00 min", message)
            self.assertIn("Entire eligible source used", message)
            self.assertEqual(self.client.mock_calls, [])
            self.saved.write_text.assert_not_called()
        ask.assert_not_called()

    @patch("ui.royal_shuffle")
    @patch("ui.simpledialog.askstring", return_value="Edited Full name")
    def test_full_name_dialog_and_option_snapshot(self, ask, shuffle):
        ask.side_effect = lambda *args, **kwargs: (
            setattr(self.session_variable.get, "return_value", "30M") or "Edited Full name"
        )
        self.shuffle.invoke()
        self.assertNotIn("session_minutes", shuffle.call_args.kwargs)
        self.assertEqual(shuffle.call_args.kwargs["output_playlist_name"], "Edited Full name")

    @patch("ui.royal_shuffle")
    def test_hidden_commit_guard_and_reappearance(self, shuffle):
        self.session_variable.get.return_value = "60M"
        self.search("alpha")
        self.assert_actions("disabled")
        self.shuffle.invoke()
        shuffle.assert_not_called()
        self.search("")
        self.assert_actions("normal")
        self.assertEqual(self.highlight, (5,))

    @patch("ui.export_playlist_csv", return_value=1)
    @patch("ui.choose_csv_destination", return_value="output.csv")
    def test_export_unaffected_by_timed_option(self, choose, export):
        self.session_variable.get.return_value = "90M"
        self.export.invoke()
        self.client.get_playlist_items.assert_called_once_with("z")
        export.assert_called_once_with("output.csv", self.client.get_playlist_items.return_value)

    @patch("ui.royal_shuffle")
    def test_actionable_errors_and_orphan_id(self, shuffle):
        self.session_variable.get.return_value = "30M"
        shuffle.side_effect = SessionLengthError("Invalid duration. Full Playlist remains available.")
        self.shuffle.invoke()
        self.assertIn("Full Playlist remains available", self.status.config.call_args.kwargs["text"])
        result = RoyalShuffleResult("Zulu", "z", "Out", "orphan-id", 2, 0, 0, "created")
        shuffle.side_effect = RoyalShufflePartialWriteError(result, OSError("disk full"))
        self.shuffle.invoke()
        self.assertIn("orphan-id", self.status.config.call_args.kwargs["text"])
        self.assertIn("0/2", self.status.config.call_args.kwargs["text"])
