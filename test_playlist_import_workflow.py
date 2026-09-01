import sys
import types
import unittest
from unittest.mock import Mock, call

try:
    import requests
except ModuleNotFoundError:
    requests = types.ModuleType("requests")
    requests.HTTPError = type("HTTPError", (Exception,), {})
    requests.get = Mock()
    requests.post = Mock()
    requests.put = Mock()
    sys.modules["requests"] = requests

from playlist_import import PlaylistImportRow
from playlist_import_workflow import (
    CatalogValidationError,
    PlaylistImportPartialWriteError,
    create_imported_playlist,
    preflight_playlist_import,
)
from spotify_client import SpotifyTrackNotFoundError


def row(line_number, marker):
    track_id = str(marker) * 22
    return PlaylistImportRow(
        line_number=line_number,
        uri=f"spotify:track:{track_id}",
        track_id=track_id,
    )


class PlaylistImportWorkflowTests(unittest.TestCase):
    def test_preflight_uses_first_seen_unique_order(self):
        spotify = Mock()
        spotify.get_track.return_value = {"type": "track"}
        rows = [row(2, 1), row(3, 2), row(4, 1), row(5, 3)]

        prepared = preflight_playlist_import(spotify, rows)

        self.assertEqual(prepared.rows, tuple(rows))
        self.assertEqual(
            spotify.get_track.call_args_list,
            [call("1" * 22), call("2" * 22), call("3" * 22)],
        )

    def test_removed_duplicate_maps_to_every_original_row(self):
        spotify = Mock()
        spotify.get_track.side_effect = SpotifyTrackNotFoundError("1" * 22)
        rows = [row(2, 1), row(7, 1)]

        with self.assertRaises(CatalogValidationError) as caught:
            preflight_playlist_import(spotify, rows)

        self.assertEqual(
            [issue.line_number for issue in caught.exception.issues],
            [2, 7],
        )

    def test_unavailable_and_restricted_tracks_accumulate(self):
        spotify = Mock()
        spotify.get_track.side_effect = [
            {"type": "track", "is_playable": False},
            {"type": "track", "restrictions": {"reason": "market"}},
        ]

        with self.assertRaises(CatalogValidationError) as caught:
            preflight_playlist_import(spotify, [row(2, 1), row(3, 2)])

        self.assertEqual(
            [issue.code for issue in caught.exception.issues],
            ["unavailable_track", "restricted_track"],
        )
        spotify.create_playlist.assert_not_called()

    def test_operational_lookup_failure_propagates_and_creates_nothing(self):
        spotify = Mock()
        spotify.get_track.side_effect = RuntimeError("network")

        with self.assertRaises(RuntimeError):
            preflight_playlist_import(spotify, [row(2, 1)])

        spotify.create_playlist.assert_not_called()

    def test_creates_new_private_playlist_with_exact_order_and_duplicates(self):
        spotify = Mock()
        spotify.get_track.return_value = {"type": "track"}
        spotify.create_playlist.return_value = {"id": "new-id"}
        rows = [row(2, 1), row(3, 2), row(4, 1)]
        prepared = preflight_playlist_import(spotify, rows)

        result = create_imported_playlist(spotify, prepared, "CSV Copy")

        spotify.create_playlist.assert_called_once_with(
            name="CSV Copy",
            description="Ordered CSV import created by RoyalShuffle",
            public=False,
        )
        spotify.add_playlist_items.assert_called_once_with(
            "new-id",
            [item.uri for item in rows],
        )
        self.assertEqual(result.playlist_id, "new-id")
        self.assertEqual(result.item_count, 3)

    def test_partial_write_is_structured_and_not_rolled_back(self):
        spotify = Mock()
        spotify.create_playlist.return_value = {"id": "partial-id"}
        failure = RuntimeError("sensitive")
        failure.playlist_id = "partial-id"
        failure.items_written = 100
        failure.total_items = 150
        spotify.add_playlist_items.side_effect = failure
        prepared = Mock(rows=tuple(row(number, 1) for number in range(150)))

        with self.assertRaises(PlaylistImportPartialWriteError) as caught:
            create_imported_playlist(spotify, prepared, "Partial")

        error = caught.exception
        self.assertEqual(error.playlist_id, "partial-id")
        self.assertEqual(error.items_written, 100)
        self.assertEqual(error.total_items, 150)
        self.assertIs(error.cause, failure)
        spotify.clear_playlist.assert_not_called()


if __name__ == "__main__":
    unittest.main()
