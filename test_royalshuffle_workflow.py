import unittest
from unittest.mock import Mock, patch

from app_metadata import MANAGED_PLAYLIST_DESCRIPTION
from royalshuffle import RoyalShufflePartialWriteError, royal_shuffle
from spotify_client import SpotifyQuotaExceededError


class RoyalShuffleStructuredWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.spotify = Mock()
        self.spotify.get_playlist_items.return_value = [
            {"uri": "spotify:track:one", "is_local": False},
            {"uri": "spotify:track:one", "is_local": False},
            {"uri": "spotify:local:a:b:c:1", "is_local": True},
        ]
        self.spotify.find_playlists_by_name.return_value = []
        self.spotify.create_playlist.return_value = {"id": "output-id"}
        self.spotify.add_playlist_items.return_value = 2
        self.source = {"id": "source-id", "name": "Source"}

    @patch("royalshuffle.add_managed_playlist_id")
    @patch("royalshuffle.load_managed_playlist_ids", return_value=set())
    @patch("royalshuffle.shuffle_items", side_effect=lambda items: items)
    def test_new_output_is_private_registered_then_populated(
        self, shuffle_items, _load, add_id
    ):
        result = royal_shuffle(self.spotify, self.source)

        shuffle_items.assert_called_once()
        shuffled = shuffle_items.call_args.args[0]
        self.assertEqual([item["uri"] for item in shuffled], [
            "spotify:track:one", "spotify:track:one"
        ])
        self.spotify.create_playlist.assert_called_once_with(
            name="Source - RANDOM",
            description=MANAGED_PLAYLIST_DESCRIPTION,
            public=False,
        )
        add_id.assert_called_once_with("output-id")
        self.spotify.clear_playlist.assert_called_once_with("output-id")
        self.spotify.add_playlist_items.assert_called_once_with(
            "output-id", ["spotify:track:one", "spotify:track:one"]
        )
        self.assertEqual(result.source_id, "source-id")
        self.assertEqual(result.output_id, "output-id")
        self.assertEqual(result.items_written, 2)
        self.assertEqual(result.total_items, 2)
        self.assertEqual(result.skipped_item_count, 1)
        self.assertEqual(result.action, "created")

    @patch("royalshuffle.load_managed_playlist_ids", return_value=set())
    @patch("royalshuffle.shuffle_items", side_effect=lambda items: items)
    def test_registration_occurs_before_clear(self, _shuffle, _load):
        events = []
        self.spotify.clear_playlist.side_effect = lambda _playlist_id: events.append("clear")
        with patch(
            "royalshuffle.add_managed_playlist_id",
            side_effect=lambda _playlist_id: events.append("register"),
        ):
            royal_shuffle(self.spotify, self.source)
        self.assertEqual(events, ["register", "clear"])

    @patch("royalshuffle.add_managed_playlist_id", side_effect=OSError("disk full"))
    @patch("royalshuffle.load_managed_playlist_ids", return_value=set())
    @patch("royalshuffle.shuffle_items", side_effect=lambda items: items)
    def test_registration_failure_reports_created_output_without_writing(
        self, _shuffle, _load, _add_id
    ):
        with self.assertRaises(RoyalShufflePartialWriteError) as caught:
            royal_shuffle(self.spotify, self.source)
        self.assertEqual(caught.exception.result.output_id, "output-id")
        self.assertEqual(caught.exception.result.items_written, 0)
        self.assertEqual(caught.exception.result.total_items, 2)
        self.spotify.clear_playlist.assert_not_called()
        self.spotify.add_playlist_items.assert_not_called()

    @patch("royalshuffle.add_managed_playlist_id")
    @patch("royalshuffle.load_managed_playlist_ids", return_value={"existing-id"})
    @patch("royalshuffle.shuffle_items", side_effect=lambda items: items)
    def test_existing_managed_output_is_reused_without_registration(
        self, _shuffle, _load, add_id
    ):
        self.spotify.find_playlists_by_name.return_value = [
            {"id": "existing-id", "name": "Source - RANDOM"}
        ]

        result = royal_shuffle(self.spotify, self.source)

        self.spotify.create_playlist.assert_not_called()
        add_id.assert_not_called()
        self.spotify.clear_playlist.assert_called_once_with("existing-id")
        self.assertEqual(result.action, "updated")
        self.assertEqual(result.output_id, "existing-id")

    @patch("royalshuffle.add_managed_playlist_id")
    @patch("royalshuffle.load_managed_playlist_ids", return_value=set())
    @patch("royalshuffle.shuffle_items", side_effect=lambda items: items)
    def test_failure_before_creation_is_not_reported_as_partial(
        self, _shuffle, _load, add_id
    ):
        self.spotify.create_playlist.side_effect = RuntimeError("create failed")
        with self.assertRaisesRegex(RuntimeError, "create failed"):
            royal_shuffle(self.spotify, self.source)
        add_id.assert_not_called()
        self.spotify.clear_playlist.assert_not_called()

    @patch("royalshuffle.add_managed_playlist_id")
    @patch("royalshuffle.load_managed_playlist_ids", return_value=set())
    @patch("royalshuffle.shuffle_items", side_effect=lambda items: items)
    def test_population_failure_reports_confirmed_total_and_preserves_output(
        self, _shuffle, _load, add_id
    ):
        failure = RuntimeError("write failed")
        failure.items_written = 1
        failure.total_items = 2
        self.spotify.add_playlist_items.side_effect = failure

        with self.assertRaises(RoyalShufflePartialWriteError) as caught:
            royal_shuffle(self.spotify, self.source)

        result = caught.exception.result
        self.assertEqual(result.output_id, "output-id")
        self.assertEqual(result.items_written, 1)
        self.assertEqual(result.total_items, 2)
        self.assertIs(caught.exception.cause, failure)
        add_id.assert_called_once_with("output-id")
        self.spotify.clear_playlist.assert_called_once()

    @patch("royalshuffle.add_managed_playlist_id")
    @patch("royalshuffle.load_managed_playlist_ids", return_value=set())
    @patch("royalshuffle.shuffle_items", side_effect=lambda items: items)
    def test_quota_during_population_is_structured_partial(
        self, _shuffle, _load, _add_id
    ):
        self.spotify.add_playlist_items.side_effect = SpotifyQuotaExceededError("quota")
        with self.assertRaises(RoyalShufflePartialWriteError) as caught:
            royal_shuffle(self.spotify, self.source)
        self.assertIsInstance(caught.exception.cause, SpotifyQuotaExceededError)
        self.assertEqual(caught.exception.result.items_written, 0)

    @patch("royalshuffle.add_managed_playlist_id")
    @patch("royalshuffle.load_managed_playlist_ids", return_value=set())
    @patch("royalshuffle.shuffle_items", side_effect=lambda items: items)
    def test_interrupt_during_population_is_structured_without_rollback(
        self, _shuffle, _load, _add_id
    ):
        self.spotify.add_playlist_items.side_effect = KeyboardInterrupt()
        with self.assertRaises(RoyalShufflePartialWriteError) as caught:
            royal_shuffle(self.spotify, self.source)
        self.assertIsInstance(caught.exception.cause, KeyboardInterrupt)
        self.spotify.clear_playlist.assert_called_once_with("output-id")


if __name__ == "__main__":
    unittest.main()
