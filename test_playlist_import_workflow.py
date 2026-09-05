import sys
import types
import unittest
from unittest.mock import ANY, Mock, patch

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
    PlaylistImportPartialWriteError,
    create_imported_playlist,
    prepare_playlist_import,
)
from spotify_client import SpotifyClient


def row(line_number, marker):
    track_id = str(marker) * 22
    return PlaylistImportRow(
        line_number=line_number,
        uri=f"spotify:track:{track_id}",
        track_id=track_id,
    )


class PlaylistImportWorkflowTests(unittest.TestCase):
    @patch("playlist_import_workflow.log_debug")
    def test_prepare_is_local_and_preserves_rows(self, log_debug):
        rows = [row(2, 1), row(3, 2), row(4, 1), row(5, 3)]

        prepared = prepare_playlist_import(rows)

        self.assertEqual(prepared.rows, tuple(rows))
        messages = [item.args[0] for item in log_debug.call_args_list]
        self.assertIn("CSV import rows parsed=4", messages)
        self.assertIn("CSV import valid track URIs=4", messages)
        self.assertIn("CSV import unique track IDs=3", messages)

    def test_creates_new_private_playlist_with_exact_order_and_duplicates(self):
        spotify = Mock()
        spotify.create_playlist.return_value = {"id": "new-id"}
        rows = [row(2, 1), row(3, 2), row(4, 1)]
        prepared = prepare_playlist_import(rows)

        result = create_imported_playlist(spotify, prepared, "CSV Copy")

        spotify.create_playlist.assert_called_once_with(
            name="CSV Copy",
            description="Ordered CSV import created by RoyalShuffle",
            public=False,
        )
        spotify.add_playlist_items.assert_called_once_with(
            "new-id",
            [item.uri for item in rows],
            progress_callback=ANY,
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

    @patch("spotify_client.requests.get")
    @patch("spotify_client.requests.post")
    def test_500_track_import_uses_one_creation_and_five_population_requests(
        self, post, get
    ):
        def successful_response(url, **_kwargs):
            result = Mock(status_code=201, headers={})
            result.json.return_value = (
                {"id": "large-id"} if url.endswith("/me/playlists") else {}
            )
            return result

        post.side_effect = successful_response
        spotify = SpotifyClient("test-token")
        rows = tuple(
            PlaylistImportRow(
                line_number=index + 2,
                uri=f"spotify:track:{index:022d}",
                track_id=f"{index:022d}",
            )
            for index in range(500)
        )

        result = create_imported_playlist(
            spotify, prepare_playlist_import(rows), "Large Import"
        )

        self.assertEqual(result.item_count, 500)
        get.assert_not_called()
        self.assertEqual(post.call_count, 6)
        self.assertTrue(post.call_args_list[0].args[0].endswith("/me/playlists"))
        batches = [item.kwargs["json"]["uris"] for item in post.call_args_list[1:]]
        self.assertEqual([len(batch) for batch in batches], [100] * 5)
        self.assertEqual([uri for batch in batches for uri in batch], [r.uri for r in rows])


if __name__ == "__main__":
    unittest.main()
