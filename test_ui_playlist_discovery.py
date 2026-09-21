import unittest
from copy import deepcopy
from unittest.mock import Mock, patch

try:
    import tkinter  # noqa: F401
except ModuleNotFoundError as exc:
    if exc.name == "tkinter":
        raise unittest.SkipTest("Tkinter unavailable; skipping GUI tests") from exc
    raise

import ui


class PlaylistDiscoveryTests(unittest.TestCase):
    def setUp(self):
        def patched(name, **kwargs):
            patcher = patch("ui." + name, **kwargs)
            self.addCleanup(patcher.stop)
            return patcher.start()

        self.source = [
            {"id": "z", "name": "Zulu"},
            {"id": "a1", "name": "alpha"},
            {"id": "a2", "name": "ALPHA"},
            {"id": "b", "name": "Beta Mix"},
            {"id": "unicode", "name": "Straße"},
            {"id": "ordinary", "name": "Ordinary - RANDOM"},
            {"id": "managed", "name": "Hidden output"},
        ]
        self.original = deepcopy(self.source)
        self.client = patched("SpotifyClient").return_value
        self.client.get_playlists.return_value = self.source
        self.review = patched("review_legacy_playlists")
        patched("load_managed_playlist_ids", return_value={"managed"})
        self.saved = patched("LAST_PLAYLIST_FILE")
        self.saved.exists.return_value = True
        self.saved.read_text.return_value = "z\n"
        patched("log_debug")
        patched("configure_window_icon")
        patched("create_open_folder_button")
        patched("load_token_data", return_value={"refresh_token": "token"})
        patched("refresh_saved_token_data", return_value={"access_token": "token"})
        self.root = patched("tk.Tk").return_value
        patched("tk.Frame")
        patched("tk.Scrollbar")
        self.labels = []
        def make_label(*args, **kwargs):
            label = Mock()
            self.labels.append((kwargs, label))
            return label
        patched("tk.Label", side_effect=make_label)
        self.buttons = {}
        def make_button(*args, **kwargs):
            button = Mock()
            button.invoke = kwargs.get("command", Mock())
            self.buttons[kwargs["text"]] = button
            return button
        patched("tk.Button", side_effect=make_button)
        self.entry = patched("tk.Entry").return_value
        self.variable = patched("tk.StringVar").return_value
        self.variable.get.return_value = ""
        self.listbox_type = patched("tk.Listbox")
        self.listbox = self.listbox_type.return_value
        self.rows = []
        self.highlight = ()
        def delete(*args):
            self.rows.clear()
            self.highlight = ()
        def select(index):
            self.highlight = (index,)
        self.listbox.delete.side_effect = delete
        self.listbox.insert.side_effect = lambda _end, name: self.rows.append(name)
        self.listbox.selection_set.side_effect = select
        self.listbox.curselection.side_effect = lambda: self.highlight
        ui.main()
        self.status = next(label for kwargs, label in self.labels
                           if kwargs.get("text") == "Not connected")
        self.search_callback = self.variable.trace_add.call_args.args[1]
        self.bindings = dict(call.args for call in self.listbox.bind.call_args_list)
        self.load = self.root.after.call_args.args[1]
        self.shuffle = self.buttons["Royal Shuffle"]
        self.export = self.buttons["Export CSV..."]
        self.load()

    def search(self, text):
        self.variable.get.return_value = text
        self.search_callback("variable", "", "write")

    def commit(self, index, event="<Return>"):
        self.highlight = (index,)
        self.bindings[event](None)

    def assert_actions(self, state):
        self.assertEqual(self.shuffle.config.call_args.kwargs["state"], state)
        self.assertEqual(self.export.config.call_args.kwargs["state"], state)

    def test_sort_stability_exclusion_and_original_records(self):
        self.assertEqual(self.rows, ["alpha", "ALPHA", "Beta Mix",
                                    "Ordinary - RANDOM", "Straße", "Zulu"])
        self.assertEqual(self.source, self.original)
        self.review.assert_called_once_with(self.source, self.listbox.winfo_toplevel())
        self.commit(0)
        self.saved.write_text.assert_called_with("a1")
        self.commit(1, "<Double-Button-1>")
        self.saved.write_text.assert_called_with("a2")

    def test_substrings_unicode_literal_spaces_and_empty_query(self):
        for query, expected in [
            ("mIX", ["Beta Mix"]),
            ("STRASSE", ["Straße"]),
            (" ", ["Beta Mix", "Ordinary - RANDOM"]),
            (" alpha", []),
            ("ALP", ["alpha", "ALPHA"]),
        ]:
            with self.subTest(query=query):
                self.search(query)
                self.assertEqual(self.rows, expected)
        self.search("")
        self.assertEqual(self.rows, ["alpha", "ALPHA", "Beta Mix",
                                    "Ordinary - RANDOM", "Straße", "Zulu"])
        self.assertNotIn("Hidden output", self.rows)
        self.assertEqual(self.source, self.original)

    def test_filtered_row_commit_uses_visible_index_and_persists_id(self):
        self.search("mix")
        self.commit(0)
        self.saved.write_text.assert_called_once_with("b")
        self.assert_actions("normal")
        self.search("")
        self.assertEqual(self.highlight, (2,))
        self.assertIn("Selected: Beta Mix", self.status.config.call_args.kwargs["text"])

    def test_recall_survives_sorting_and_rename(self):
        self.assertEqual(self.highlight, (5,))
        self.assert_actions("normal")
        self.source[0]["name"] = "A renamed source"
        self.load()
        self.assertEqual(self.highlight, (0,))
        self.assertEqual(self.rows[0], "A renamed source")
        self.saved.write_text.assert_not_called()

    def test_hidden_commit_is_retained_and_restored_by_id(self):
        self.search("alpha")
        self.assert_actions("disabled")
        self.assertEqual(self.highlight, ())
        self.assertIn("Selected: Zulu", self.status.config.call_args.kwargs["text"])
        self.assertIn("Hidden by filter", self.status.config.call_args.kwargs["text"])
        self.search("zul")
        self.assertEqual(self.highlight, (0,))
        self.assert_actions("normal")
        self.search("")
        self.assertEqual(self.highlight, (5,))
        self.saved.write_text.assert_not_called()

    def test_no_matches_have_no_fake_row_and_report_hidden_target(self):
        self.search("missing")
        self.assertEqual(self.rows, [])
        self.assertEqual(self.highlight, ())
        self.assert_actions("disabled")
        self.assertIn("No matching playlists", self.status.config.call_args.kwargs["text"])
        self.assertIn("Hidden by filter", self.status.config.call_args.kwargs["text"])

    def test_missing_or_managed_recall_never_falls_back_to_same_name(self):
        self.source[0]["name"] = "Hidden output"
        for remembered in ["missing", "managed"]:
            with self.subTest(remembered=remembered):
                self.saved.read_text.return_value = remembered
                self.load()
                self.assertEqual(self.highlight, ())
                self.assert_actions("disabled")
                self.search("Hidden output")
                self.assertEqual(self.rows, ["Hidden output"])
                self.assertEqual(self.highlight, ())
                self.assert_actions("disabled")
                self.search("")
        self.saved.write_text.assert_not_called()

    def test_search_makes_no_spotify_calls_or_persistence_writes(self):
        self.client.reset_mock()
        self.saved.reset_mock()
        for query in ["a", "al", "alp", "missing", "", "Straße"]:
            self.search(query)
        self.assertEqual(self.client.mock_calls, [])
        self.assertEqual(self.saved.mock_calls, [])

    def test_highlight_navigation_does_not_commit(self):
        self.highlight = (0,)
        self.bindings["<Button-1>"](None)
        self.saved.write_text.assert_not_called()
        self.assertNotIn("<<ListboxSelect>>", self.bindings)
        self.search("")
        self.assertEqual(self.highlight, (5,))
        self.assertFalse(self.listbox_type.call_args.kwargs["exportselection"])

    @patch("ui.export_playlist_csv", return_value=2)
    @patch("ui.choose_csv_destination", return_value="output.csv")
    @patch("ui.royal_shuffle")
    @patch("ui.simpledialog.askstring", return_value="Output")
    def test_actions_use_committed_record_and_reject_hidden_target(
        self, ask, shuffle, choose, export_csv
    ):
        self.search("mix")
        # Even direct callback invocation cannot act on the hidden Zulu target.
        self.shuffle.invoke()
        self.export.invoke()
        ask.assert_not_called()
        choose.assert_not_called()
        self.commit(0, "<KP_Enter>")
        self.search("")
        self.highlight = (0,)  # Navigation is not commitment.
        self.shuffle.invoke()
        self.assertIs(shuffle.call_args.args[1], self.source[3])
        self.export.invoke()
        self.client.get_playlist_items.assert_called_once_with("b")
        choose.assert_called_once_with(self.root, "Beta Mix")
        export_csv.assert_called_once_with(
            "output.csv", self.client.get_playlist_items.return_value
        )

    def test_import_stays_available_without_visible_selection(self):
        self.search("missing")
        self.assert_actions("disabled")
        self.assertEqual(self.buttons["Import CSV..."].config.call_args.kwargs["state"], "normal")


if __name__ == "__main__":
    unittest.main()
