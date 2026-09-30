import unittest
from unittest.mock import Mock, patch

from artist_separation import ArtistSeparationError
from royalshuffle import royal_shuffle


class ArtistSeparationGuardTests(unittest.TestCase):
    def setUp(self):
        self.spotify = Mock()
        self.spotify.find_playlists_by_name.return_value = []
        self.spotify.get_playlists.return_value = [{"id": "existing", "name": "Output"}]
        self.spotify.create_playlist.return_value = {"id": "new"}
        self.spotify.add_playlist_items.side_effect = lambda _, uris: len(uris)
        self.source = {"id": "source", "name": "Source"}
        self.registry_calls = []
        for name, value in (
            ("load_managed_playlist_ids", {"existing"}),
            ("timed_output_id", "existing"),
            ("add_managed_playlist_id", None),
            ("register_timed_output", None),
        ):
            patcher = patch("royalshuffle." + name, return_value=value)
            self.registry_calls.append(patcher.start())
            self.addCleanup(patcher.stop)
        patcher = patch("royalshuffle.log_debug")
        self.log = patcher.start()
        self.addCleanup(patcher.stop)

    def tracks(self, artists):
        return [{"uri": f"spotify:track:{i}", "primary_artist_id": artist,
                 "duration_ms": 600000} for i, artist in enumerate(artists)]

    def run_shuffle(self, items, separate, minutes=None, enabled=True):
        self.spotify.get_playlist_items.return_value = items
        with patch("royalshuffle.shuffle_items", return_value=items), \
             patch("royalshuffle.separate_artists", side_effect=separate) as sequencer:
            result = royal_shuffle(self.spotify, self.source,
                                   session_minutes=minutes, artist_separation=enabled)
        return result, sequencer

    def assert_rejected(self, items, separate, invariant, minutes=None):
        with self.assertRaises(ArtistSeparationError) as caught:
            self.run_shuffle(items, separate, minutes)
        message = str(caught.exception)
        self.assertIn(invariant, message)
        for field in ("selected_count=", "separated_count=",
                      "expected_minimum_adjacency=", "actual_adjacency="):
            self.assertIn(field, message)
        self.assertIn("No output playlist or registry changes", message)
        self.log.assert_called_with(message)
        self.assertEqual([call[0] for call in self.spotify.method_calls],
                         ["get_playlist_items"])
        for mock in self.registry_calls:
            mock.assert_not_called()
        return message

    def test_valid_zero_adjacency_passes_in_exact_returned_order(self):
        items = self.tracks("AAB")
        separated = [items[1], items[2], items[0]]
        self.run_shuffle(items, lambda _: separated)
        self.spotify.add_playlist_items.assert_called_once_with(
            "new", [item["uri"] for item in separated])

    def test_unavoidable_minimum_and_singleton_pass(self):
        for artists in ("AABA", "AAAA", "A"):
            with self.subTest(artists=artists):
                self.spotify.reset_mock()
                items = self.tracks(artists)
                self.run_shuffle(items, lambda selected: selected)
                self.spotify.add_playlist_items.assert_called_once_with(
                    "new", [item["uri"] for item in items])

    def test_avoidable_adjacency_fails_before_new_or_existing_output_resolution(self):
        for existing in (False, True):
            for minutes in (None, 30, 60, 90, 240):
                with self.subTest(existing=existing, minutes=minutes):
                    self.spotify.reset_mock()
                    self.spotify.find_playlists_by_name.return_value = (
                        [{"id": "existing", "name": "Output"}] if existing else [])
                    self.registry_calls[1].return_value = "existing" if existing else None
                    items = self.tracks("AAB")
                    message = self.assert_rejected(
                        items, lambda selected: selected,
                        "minimum same-primary-artist adjacency", minutes)
                    self.assertIn("expected_minimum_adjacency=0", message)
                    self.assertIn("actual_adjacency=1", message)

    def test_missing_added_substituted_and_deduplicated_occurrences_fail(self):
        for fault in ("missing", "added", "equal_copy", "replaced_by_duplicate"):
            with self.subTest(fault=fault):
                self.spotify.reset_mock()
                items = self.tracks("ABA")
                # Distinct occurrences of exactly the same track/metadata.
                items[2] = dict(items[0])
                outputs = {
                    "missing": items[:2],
                    "added": items + [dict(items[1])],
                    "equal_copy": [dict(items[0]), items[1], items[2]],
                    "replaced_by_duplicate": [items[0], items[1], items[0]],
                }
                self.assert_rejected(items, lambda _: outputs[fault],
                                     "exact occurrence preservation")

    def test_duplicate_tracks_and_repeated_references_preserve_multiplicity(self):
        for same_reference in (False, True):
            with self.subTest(same_reference=same_reference):
                self.spotify.reset_mock()
                items = self.tracks("ABA")
                items[2] = items[0] if same_reference else dict(items[0])
                self.run_shuffle(items, lambda selected: selected[::-1])
                self.spotify.add_playlist_items.assert_called_once_with(
                    "new", [items[0]["uri"], items[1]["uri"], items[0]["uri"]])

    def test_input_list_or_occurrence_mutation_cannot_change_baseline(self):
        for fault in ("list", "metadata"):
            with self.subTest(fault=fault):
                self.spotify.reset_mock()
                items = self.tracks("ABA")
                def corrupt(selected):
                    if fault == "list":
                        selected.pop()
                    else:
                        selected[0]["uri"] = "spotify:track:substitution"
                    return selected
                self.assert_rejected(items, corrupt, "exact occurrence preservation")

    def test_off_bypasses_guard_and_preserves_existing_behavior(self):
        items = self.tracks("AAA")
        for item in items:
            item.pop("primary_artist_id")
        with patch("royalshuffle._validate_artist_separation") as guard:
            _, separate = self.run_shuffle(items, lambda _: [], enabled=False)
        guard.assert_not_called()
        separate.assert_not_called()
        self.spotify.add_playlist_items.assert_called_once_with(
            "new", [item["uri"] for item in items])
