import json
import unittest
from unittest.mock import patch
import playlist_registry as registry
import test_session_length as session
import test_ui_playlist_discovery as discovery
from royalshuffle import royal_shuffle, SessionLengthError, RoyalShuffleResult
from test_spotify_client import response


class CustomCoreTests(unittest.TestCase):
    setUp = session.SessionLengthTests.setUp

    def test_targets_crossing_and_larger_than_source(self):
        items = [session.item(900000, name) for name in ("one", "two", "three")]
        self.spotify.get_playlist_items.return_value = items
        for minutes, count in ((None, 3), (1, 1), (16, 2), (30, 2), (120, 3), (240, 3), (480, 3), (10**100, 3)):
            with self.subTest(minutes=minutes), patch("royalshuffle.shuffle_items", return_value=items):
                self.spotify.create_playlist.return_value = {"id": f"out-{minutes}"}
                result = royal_shuffle(self.spotify, self.source, session_minutes=minutes)
                self.assertEqual(self.spotify.add_playlist_items.call_args.args[1], [x["uri"] for x in items[:count]])
                self.assertEqual(result.source_shorter_than_target, minutes is not None and minutes > 45)

    def test_invalid_core_and_registry_minutes(self):
        for minutes in (True, False, 0, -1, 60.0, "060", "60", [], {}):
            with self.subTest(minutes=minutes):
                with self.assertRaises(SessionLengthError):
                    royal_shuffle(self.spotify, self.source, session_minutes=minutes)
                self.assertEqual(self.spotify.method_calls, [])
                for operation in (lambda: registry.register_timed_output("source", minutes, "out"),
                                  lambda: registry.timed_output_id("source", minutes),
                                  lambda: registry.remove_timed_output("source", minutes, "out")):
                    with self.assertRaises(ValueError):
                        operation()
                self.assertFalse(self.registry_path.exists())

    def test_legacy_and_custom_reuse_without_registry_rewrite(self):
        durations = (30, 60, 90, 120, 240, 480)
        for minutes in durations:
            registry.register_timed_output("source", minutes, f"out-{minutes}")
        before = self.registry_path.read_bytes()
        self.spotify.get_playlist_items.return_value = [session.item(1000)]
        self.spotify.get_playlists.return_value = [{"id": f"out-{m}", "name": f"Edited {m}"} for m in durations]
        for minutes in durations:
            result = royal_shuffle(self.spotify, self.source, session_minutes=minutes)
            self.assertEqual(result.output_id, f"out-{minutes}")
            self.assertEqual(result.output_name, f"Edited {minutes}")
        self.spotify.create_playlist.assert_not_called()
        self.assertEqual(self.registry_path.read_bytes(), before)
        self.assertEqual(set(json.loads(before)["source_outputs"]["source"]), {str(m) for m in durations})

    def test_noncanonical_keys_rejected_without_rewrite(self):
        for key in ("", "0", "-1", "1.0", "060", "+60", " 60", "abc", chr(0xff16)+chr(0xff10)):
            with self.subTest(key=key):
                self.registry_path.write_text(json.dumps({"playlist_ids": ["out"], "source_outputs": {"source": {key: "out"}}}))
                before = self.registry_path.read_bytes()
                with self.assertRaises(ValueError):
                    registry.load_managed_playlist_ids()
                self.assertEqual(self.registry_path.read_bytes(), before)

    def test_custom_stale_recovery_keeps_legacy_binding(self):
        registry.register_timed_output("source", 240, "old")
        registry.register_timed_output("source", 30, "legacy")
        self.spotify.get_playlist_items.return_value = [session.item(1000)]
        self.spotify.get_playlists.return_value = []
        self.spotify.get_playlist.side_effect = response(404).raise_for_status.side_effect
        result = royal_shuffle(self.spotify, self.source, session_minutes=240)
        self.assertEqual(result.output_id, "new")
        self.assertEqual(registry.timed_output_id("source", 240), "new")
        self.assertEqual(registry.timed_output_id("source", 30), "legacy")


class CustomUiTests(unittest.TestCase):
    def setUp(self):
        discovery.PlaylistDiscoveryTests.setUp(self)
        self.session_variable.set.side_effect = lambda value: setattr(self.session_variable.get, "return_value", value)
        self.choose = self.option_menu.call_args.kwargs["command"]

    def select(self, value):
        self.session_variable.set(value)
        self.choose(value)

    def test_values_display_normalize_and_reopen(self):
        for text, minutes in (("240", 240), ("060", 60), ("30", 30), ("90", 90), ("480", 480)):
            with self.subTest(text=text), patch("ui.simpledialog.askstring", return_value=text), patch("ui.royal_shuffle") as shuffle:
                shuffle.return_value = RoyalShuffleResult("Source", "source", "Out", "out", 1, 1, 0, "updated", duration_ms=1000)
                self.select("Custom...")
                self.assertEqual(self.session_variable.get(), f"{minutes}M")
                self.artist_variable.get.return_value = True
                self.shuffle.invoke()
                self.assertEqual(shuffle.call_args.kwargs["session_minutes"], minutes)
                self.assertTrue(shuffle.call_args.kwargs["artist_separation"])
        self.saved.write_text.assert_not_called()

    def test_invalid_entry_can_be_corrected(self):
        for text in ("", "   ", "0", "-1", "1.5", "abc"):
            with self.subTest(text=text):
                self.select("60M")
                answers = iter((text, "240"))
                def answer(*args, **kwargs):
                    self.assertEqual(self.session_variable.get(), "60M")
                    return next(answers)
                with patch("ui.simpledialog.askstring", side_effect=answer) as ask, patch("ui.messagebox.showerror") as error:
                    self.select("Custom...")
                self.assertEqual(ask.call_count, 2)
                error.assert_called_once()
                self.assertEqual(self.session_variable.get(), "240M")

    def test_cancel_preserves_previous_selection(self):
        for prior in ("Full Playlist", "60M", "240M"):
            if prior == "240M":
                with patch("ui.simpledialog.askstring", return_value="240"):
                    self.select("Custom...")
            else:
                self.select(prior)
            with patch("ui.simpledialog.askstring", return_value=None):
                self.select("Custom...")
            self.assertEqual(self.session_variable.get(), prior)
        with patch("ui.simpledialog.askstring", side_effect=["bad", None]), patch("ui.messagebox.showerror"):
            self.select("Custom...")
        self.assertEqual(self.session_variable.get(), "240M")
