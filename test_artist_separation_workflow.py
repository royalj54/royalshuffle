import json
import random
import unittest
from unittest.mock import patch

from artist_separation import ArtistSeparationError, separate_artists
from royalshuffle import apply_session_length, royal_shuffle, RoyalShufflePartialWriteError
import playlist_registry as registry
import test_session_length as session


class ArtistSeparationWorkflowTests(unittest.TestCase):
    setUp = session.SessionLengthTests.setUp

    def tracks(self):
        return [{"uri": f"spotify:track:{index}", "duration_ms": 700000,
                 "primary_artist_id": "A" if index % 3 else "B"} for index in range(12)]

    def test_membership_duration_and_pipeline_once_for_every_session_option(self):
        for minutes in (None, 30, 60, 90, 240):
            with self.subTest(minutes=minutes):
                items = self.tracks()
                self.spotify.get_playlist_items.return_value = items[::-1]
                expected, duration = (items, None) if minutes is None else apply_session_length(items, minutes * 60000)
                events = []
                def shuffle(_items):
                    events.append("shuffle")
                    return items
                def duration_selection(selected, target):
                    events.append("duration")
                    return apply_session_length(selected, target)
                def separate(selected):
                    events.append("separate")
                    self.assertEqual([id(item) for item in selected], [id(item) for item in expected])
                    return separate_artists(selected, random.Random(0))
                with patch("royalshuffle.shuffle_items", side_effect=shuffle) as shuffler, \
                     patch("royalshuffle.apply_session_length", side_effect=duration_selection) as selector, \
                     patch("royalshuffle.separate_artists", side_effect=separate) as sequencer:
                    result = royal_shuffle(self.spotify, self.source, session_minutes=minutes, artist_separation=True)
                shuffler.assert_called_once_with(items[::-1])
                self.assertEqual(selector.call_count, int(minutes is not None))
                sequencer.assert_called_once()
                self.assertEqual(events, ["shuffle", "separate"] if minutes is None else ["shuffle", "duration", "separate"])
                self.assertCountEqual(self.spotify.add_playlist_items.call_args.args[1], [item["uri"] for item in expected])
                self.assertEqual(result.total_items, len(expected))
                self.assertEqual(result.duration_ms, duration)
                # Each session gets a distinct output ID, just as in production.
                self.spotify.create_playlist.return_value = {"id": f"next-{minutes}"}

    def test_missing_selected_id_fails_before_any_output_lookup_or_mutation(self):
        for minutes in (None, 30, 60, 90, 240):
            with self.subTest(minutes=minutes):
                self.spotify.reset_mock()
                items = self.tracks()
                items[0].pop("primary_artist_id")
                self.spotify.get_playlist_items.return_value = items
                with patch("royalshuffle.shuffle_items", return_value=items):
                    with self.assertRaisesRegex(ArtistSeparationError, "ordinary True Random"):
                        royal_shuffle(self.spotify, self.source, session_minutes=minutes, artist_separation=True)
                self.assertEqual([call[0] for call in self.spotify.method_calls], ["get_playlist_items"])
                self.assertFalse(self.registry_path.exists())

    def test_missing_id_outside_selected_prefix_does_not_fail(self):
        items = self.tracks()
        items[-1].pop("primary_artist_id")
        self.spotify.get_playlist_items.return_value = items
        with patch("royalshuffle.shuffle_items", return_value=items):
            result = royal_shuffle(self.spotify, self.source, session_minutes=30, artist_separation=True)
        self.assertEqual(result.total_items, 3)
        self.assertCountEqual(self.spotify.add_playlist_items.call_args.args[1], [item["uri"] for item in items[:3]])

    def test_off_does_not_validate_or_reorder_artist_metadata(self):
        items = self.tracks()
        for item in items:
            item.pop("primary_artist_id")
        self.spotify.get_playlist_items.return_value = items
        for minutes in (None, 30, 60, 90, 240):
            with self.subTest(minutes=minutes), \
                 patch("royalshuffle.shuffle_items", return_value=items), \
                 patch("royalshuffle.separate_artists") as separate:
                royal_shuffle(self.spotify, self.source, session_minutes=minutes)
                separate.assert_not_called()
                expected = items if minutes is None else apply_session_length(items, minutes * 60000)[0]
                self.assertEqual(self.spotify.add_playlist_items.call_args.args[1], [item["uri"] for item in expected])
                self.spotify.create_playlist.return_value = {"id": f"next-{minutes}"}

    def test_on_off_reuses_existing_full_and_timed_output_ids_without_registry_changes(self):
        items = self.tracks()
        self.spotify.get_playlist_items.return_value = items
        for minutes in (None, 30, 60, 90, 240):
            output_id = f"out-{minutes}"
            self.spotify.create_playlist.return_value = {"id": output_id}
            first = royal_shuffle(self.spotify, self.source, session_minutes=minutes)
            before = self.registry_path.read_bytes()
            output = {"id": output_id, "name": first.output_name}
            self.spotify.find_playlists_by_name.return_value = [output]
            self.spotify.get_playlists.return_value = [output]
            self.spotify.reset_mock()
            second = royal_shuffle(self.spotify, self.source, session_minutes=minutes, artist_separation=True)
            self.assertEqual(second.output_id, first.output_id)
            self.assertEqual(second.output_name, first.output_name)
            self.spotify.create_playlist.assert_not_called()
            self.assertEqual(self.registry_path.read_bytes(), before)
            self.assertEqual(set(json.loads(before)), {"playlist_ids"} if minutes is None else {"playlist_ids", "source_outputs"})

    def test_separated_write_still_registers_first_and_reports_partial_failure(self):
        self.spotify.get_playlist_items.return_value = self.tracks()
        def clear(output_id):
            self.assertEqual(registry.timed_output_id("source", 30), output_id)
        self.spotify.clear_playlist.side_effect = clear
        failure = RuntimeError("ambiguous write")
        failure.items_written = 1
        self.spotify.add_playlist_items.side_effect = failure
        with self.assertRaises(RoyalShufflePartialWriteError) as caught:
            royal_shuffle(self.spotify, self.source, session_minutes=30, artist_separation=True)
        self.assertEqual(caught.exception.result.items_written, 1)
        self.assertEqual(registry.timed_output_id("source", 30), "new")
        self.spotify.add_playlist_items.assert_called_once()
