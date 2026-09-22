import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from royalshuffle import royal_shuffle, apply_session_length, SessionLengthError, RoyalShufflePartialWriteError
import playlist_registry as registry


def item(duration, uri="spotify:track:one"):
    return {"uri": uri, "duration_ms": duration}


class SessionLengthTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.registry_path = Path(temp.name) / "registry.json"
        for name, value in [("playlist_registry.REGISTRY_FILE", self.registry_path),
                            ("royalshuffle.log_debug", Mock())]:
            patcher = patch(name, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        self.spotify = Mock()
        self.spotify.create_playlist.return_value = {"id": "new"}
        self.spotify.add_playlist_items.side_effect = lambda _id, uris: len(uris)
        self.spotify.find_playlists_by_name.return_value = []
        self.source = {"id": "source", "name": "Source"}

    def test_targets_equality_crossing_short_first_long_and_duplicates(self):
        cases = [(30, [900000, 900000, 1], 2),
                 (60, [2000000, 2000000, 1], 2),
                 (90, [2000000, 2000000, 2000000, 1], 3),
                 (30, [1000, 1000], 2), (30, [2000000, 1], 1)]
        for minutes, durations, count in cases:
            with self.subTest(minutes=minutes, durations=durations):
                items = [item(d) for d in durations]
                prefix, total = apply_session_length(items, minutes * 60000)
                self.assertEqual(prefix, items[:count])
                self.assertEqual(total, sum(durations[:count]))
                for left, right in zip(prefix, items):
                    self.assertIs(left, right)

    def test_one_shuffle_unchanged_occurrences_and_no_lookahead(self):
        first, second, third = item(1100000, "first"), item(1000000, "second"), item(700000, "third")
        local = item(None, "spotify:local:x")
        self.spotify.get_playlist_items.return_value = [third, local, first, second]
        with patch("royalshuffle.shuffle_items", return_value=[first, second, third]) as shuffle:
            result = royal_shuffle(self.spotify, self.source, session_minutes=30)
        shuffle.assert_called_once_with([third, first, second])
        self.spotify.add_playlist_items.assert_called_once_with("new", ["first", "second"])
        self.assertEqual(result.duration_ms, 2100000)
        self.assertFalse(result.source_shorter_than_target)
        self.assertEqual([call[0] for call in self.spotify.method_calls],
                         ["get_playlist_items", "create_playlist", "clear_playlist", "add_playlist_items"])

    def test_invalid_duration_anywhere_rejected_before_mutation(self):
        for invalid in (None, True, False, "123", 1.0, 0, -1):
            with self.subTest(invalid=invalid):
                self.spotify.reset_mock()
                items = [item(2000000), item(invalid)]
                self.spotify.get_playlist_items.return_value = items
                with patch("royalshuffle.shuffle_items", return_value=items) as shuffle:
                    with self.assertRaisesRegex(SessionLengthError, "Full Playlist remains available"):
                        royal_shuffle(self.spotify, self.source, session_minutes=30)
                shuffle.assert_called_once_with(items)
                self.assertEqual([call[0] for call in self.spotify.method_calls], ["get_playlist_items"])

    def test_empty_timed_source_has_no_mutation(self):
        self.spotify.get_playlist_items.return_value = [item(None, "spotify:local:x")]
        with self.assertRaisesRegex(SessionLengthError, "No eligible tracks"):
            royal_shuffle(self.spotify, self.source, session_minutes=30)
        self.spotify.create_playlist.assert_not_called()
        self.spotify.clear_playlist.assert_not_called()

    def test_full_empty_source_retains_existing_behavior(self):
        self.spotify.get_playlist_items.return_value = []
        result = royal_shuffle(self.spotify, self.source)
        self.assertEqual(result.total_items, 0)
        self.spotify.clear_playlist.assert_called_once_with("new")
        self.spotify.add_playlist_items.assert_called_once_with("new", [])

    def test_binding_is_durable_before_clear(self):
        self.spotify.get_playlist_items.return_value = [item(1000)]
        def check_binding(output_id):
            self.assertEqual(registry.timed_output_id("source", 30), output_id)
            self.assertIn(output_id, registry.load_managed_playlist_ids())
        self.spotify.clear_playlist.side_effect = check_binding
        royal_shuffle(self.spotify, self.source, session_minutes=30)

    def test_creation_failure_does_not_register_populate_or_retry(self):
        self.spotify.get_playlist_items.return_value = [item(1000)]
        self.spotify.create_playlist.side_effect = RuntimeError("creation outcome unknown")
        with self.assertRaisesRegex(RuntimeError, "creation outcome unknown"):
            royal_shuffle(self.spotify, self.source, session_minutes=30)
        self.spotify.create_playlist.assert_called_once()
        self.spotify.clear_playlist.assert_not_called()
        self.spotify.add_playlist_items.assert_not_called()
        self.assertIsNone(registry.timed_output_id("source", 30))

    def test_full_keeps_name_resolution_and_does_not_validate_durations(self):
        self.spotify.get_playlist_items.return_value = [item(None), item(False)]
        result = royal_shuffle(self.spotify, self.source, output_playlist_name="Custom")
        self.spotify.find_playlists_by_name.assert_called_once_with("Custom")
        self.assertIsNone(result.duration_ms)
        self.assertEqual(result.items_written, 2)
        self.assertNotIn("source_outputs", json.loads(self.registry_path.read_text()))

    def test_variants_reuse_exact_ids_after_source_and_output_renames(self):
        registry.add_managed_playlist_id("full")
        self.spotify.get_playlist_items.return_value = [item(1000), item(1000)]
        for minutes in (30, 60, 90):
            self.spotify.create_playlist.return_value = {"id": str(minutes)}
            first = royal_shuffle(self.spotify, self.source, session_minutes=minutes)
            self.assertTrue(first.source_shorter_than_target)
            self.assertEqual(first.items_written, 2)
        self.source["name"] = "Renamed source"
        self.spotify.get_playlists.return_value = [
            {"id": str(m), "name": f"Renamed output {m}"} for m in (30, 60, 90)
        ]
        for minutes in (30, 60, 90):
            self.spotify.reset_mock()
            result = royal_shuffle(self.spotify, self.source, session_minutes=minutes)
            self.spotify.create_playlist.assert_not_called()
            self.spotify.find_playlists_by_name.assert_not_called()
            self.spotify.clear_playlist.assert_called_once_with(str(minutes))
            self.assertEqual(result.output_name, f"Renamed output {minutes}")
            self.assertEqual(result.output_id, str(minutes))
        self.assertEqual(registry.load_managed_playlist_ids(), {"full", "30", "60", "90"})

    def test_same_names_never_adopt_another_output(self):
        self.spotify.get_playlist_items.return_value = [item(1000)]
        self.spotify.find_playlists_by_name.return_value = [{"id": "unrelated", "name": "Source - RANDOM 30M"}]
        for source_id in ("first-source", "second-source"):
            self.spotify.create_playlist.return_value = {"id": source_id + "-out"}
            result = royal_shuffle(self.spotify, {"id": source_id, "name": "Source"}, session_minutes=30)
            self.assertEqual(result.output_id, source_id + "-out")
        self.spotify.find_playlists_by_name.assert_not_called()

    def test_missing_bound_output_does_not_replace(self):
        registry.register_timed_output("source", 30, "missing")
        self.spotify.get_playlist_items.return_value = [item(1000)]
        self.spotify.get_playlists.return_value = [{"id": "other", "name": "Source - RANDOM 30M"}]
        with self.assertRaisesRegex(SessionLengthError, "missing or inaccessible"):
            royal_shuffle(self.spotify, self.source, session_minutes=30)
        self.spotify.create_playlist.assert_not_called()
        self.spotify.clear_playlist.assert_not_called()

    def test_registry_conflict_fails_before_creation(self):
        self.registry_path.write_text(json.dumps({"playlist_ids": [], "source_outputs": {"source": {"30": "unknown"}}}))
        self.spotify.get_playlist_items.return_value = [item(1000)]
        with self.assertRaises(ValueError):
            royal_shuffle(self.spotify, self.source, session_minutes=30)
        self.spotify.create_playlist.assert_not_called()
        self.spotify.clear_playlist.assert_not_called()

    def test_registration_failure_reports_created_id_without_population(self):
        self.spotify.get_playlist_items.return_value = [item(1000)]
        with patch("royalshuffle.register_timed_output", side_effect=OSError("disk full")):
            with self.assertRaises(RoyalShufflePartialWriteError) as caught:
                royal_shuffle(self.spotify, self.source, session_minutes=30)
        self.assertEqual(caught.exception.result.output_id, "new")
        self.spotify.clear_playlist.assert_not_called()
        self.spotify.add_playlist_items.assert_not_called()

    def test_partial_write_keeps_binding_and_confirmed_counts(self):
        self.spotify.get_playlist_items.return_value = [item(1000), item(1000)]
        failure = RuntimeError("ambiguous write")
        failure.items_written = 1
        self.spotify.add_playlist_items.side_effect = failure
        with self.assertRaises(RoyalShufflePartialWriteError) as caught:
            royal_shuffle(self.spotify, self.source, session_minutes=30)
        self.assertEqual(registry.timed_output_id("source", 30), "new")
        self.assertEqual(caught.exception.result.items_written, 1)
        self.spotify.clear_playlist.assert_called_once_with("new")
        self.spotify.add_playlist_items.assert_called_once()
