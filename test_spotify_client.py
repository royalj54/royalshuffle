import sys
import types
import unittest
from unittest.mock import Mock, call, patch

try:
    import requests
except ModuleNotFoundError:
    requests = types.ModuleType("requests")
    requests.HTTPError = type("HTTPError", (Exception,), {})
    requests.get = Mock()
    requests.post = Mock()
    requests.put = Mock()
    sys.modules["requests"] = requests

from spotify_client import SpotifyClient, SpotifyRetryLaterError
from royalshuffle import royal_shuffle


def response(status_code=201, retry_after=None, payload=None):
    result = Mock()
    result.status_code = status_code
    result.headers = {}

    if retry_after is not None:
        result.headers["Retry-After"] = retry_after

    if status_code >= 400:
        result.raise_for_status.side_effect = (
            requests.HTTPError(f"HTTP {status_code}")
        )

    if payload is not None:
        result.json.return_value = payload

    return result


class SpotifyClientTests(unittest.TestCase):
    def setUp(self):
        self.client = SpotifyClient("test-token")

    @patch("spotify_client.log_debug")
    @patch("spotify_client.requests.get")
    def test_playlist_item_fetch_logs_page_count(
        self,
        get,
        log_debug,
    ):
        get.side_effect = [
            response(
                200,
                payload={
                    "items": [],
                    "next": "next-page",
                    "total": 0,
                },
            ),
            response(
                200,
                payload={
                    "items": [],
                    "next": None,
                    "total": 0,
                },
            ),
        ]

        self.client.get_playlist_items("playlist-id")

        self.assertEqual(get.call_count, 2)
        messages = [
            args[0]
            for args, _kwargs in log_debug.call_args_list
        ]
        self.assertTrue(any(
            "items=0; pages=2" in message
            for message in messages
        ))

    @patch("spotify_client.log_debug")
    @patch("spotify_client.time.sleep")
    @patch("spotify_client.requests.post")
    def test_rate_limit_honors_retry_after_and_retries_batch(
        self,
        post,
        sleep,
        _log_debug,
    ):
        post.side_effect = [
            response(429, retry_after="2"),
            response(),
        ]

        self.client.add_playlist_items(
            "playlist-id",
            ["uri"] * 100,
        )

        self.assertEqual(post.call_count, 2)
        sleep.assert_called_once_with(2)

    @patch("spotify_client.log_debug")
    @patch("spotify_client.time.sleep")
    @patch("spotify_client.requests.post")
    def test_quota_exceeded_does_not_sleep_or_retry(
        self,
        post,
        sleep,
        _log_debug,
    ):
        post.return_value = response(
            429,
            retry_after="2",
            payload={
                "error": {
                    "category": "QUOTA_EXCEEDED",
                }
            },
        )

        with self.assertRaisesRegex(
            SpotifyRetryLaterError,
            "quota is currently exhausted",
        ):
            self.client.add_playlist_items(
                "playlist-id",
                ["uri"] * 100,
            )

        post.assert_called_once()
        sleep.assert_not_called()

    @patch("spotify_client.log_debug")
    @patch("spotify_client.time.sleep")
    @patch("spotify_client.requests.post")
    def test_long_retry_after_does_not_sleep_or_retry(
        self,
        post,
        sleep,
        _log_debug,
    ):
        post.return_value = response(429, retry_after="61")

        with self.assertRaisesRegex(
            SpotifyRetryLaterError,
            "wait too long",
        ):
            self.client.add_playlist_items(
                "playlist-id",
                ["uri"] * 100,
            )

        post.assert_called_once()
        sleep.assert_not_called()

    @patch("spotify_client.log_debug")
    @patch("spotify_client.time.sleep")
    @patch("spotify_client.requests.post")
    def test_sixty_second_retry_after_is_honored(
        self,
        post,
        sleep,
        _log_debug,
    ):
        post.side_effect = [
            response(429, retry_after="60"),
            response(),
        ]

        self.client.add_playlist_items(
            "playlist-id",
            ["uri"] * 100,
        )

        self.assertEqual(post.call_count, 2)
        sleep.assert_called_once_with(60)

    @patch("spotify_client.log_debug")
    @patch("spotify_client.time.sleep")
    @patch("spotify_client.requests.post")
    def test_rate_limit_uses_fallback_for_invalid_retry_after(
        self,
        post,
        sleep,
        _log_debug,
    ):
        post.side_effect = [
            response(429, retry_after="invalid"),
            response(),
        ]

        self.client.add_playlist_items(
            "playlist-id",
            ["uri"] * 100,
        )

        self.assertEqual(post.call_count, 2)
        sleep.assert_called_once_with(1)

    @patch("spotify_client.log_debug")
    @patch("spotify_client.time.sleep")
    @patch("spotify_client.requests.post")
    def test_rate_limit_uses_fallback_when_retry_after_is_missing(
        self,
        post,
        sleep,
        _log_debug,
    ):
        post.side_effect = [response(429), response()]

        self.client.add_playlist_items(
            "playlist-id",
            ["uri"] * 100,
        )

        self.assertEqual(post.call_count, 2)
        sleep.assert_called_once_with(1)

    @patch("spotify_client.log_debug")
    @patch("spotify_client.time.sleep")
    @patch("spotify_client.requests.post")
    def test_rate_limit_stops_after_three_retries(
        self,
        post,
        sleep,
        _log_debug,
    ):
        post.side_effect = [response(429, retry_after="2")] * 4

        with self.assertRaises(SpotifyRetryLaterError):
            self.client.add_playlist_items(
                "playlist-id",
                ["uri"] * 100,
            )

        self.assertEqual(post.call_count, 4)
        self.assertEqual(sleep.call_args_list, [call(2)] * 3)

    @patch("spotify_client.log_debug")
    @patch("spotify_client.requests.post")
    def test_network_failure_is_not_retried(
        self,
        post,
        _log_debug,
    ):
        post.side_effect = RuntimeError("network failure")

        with self.assertRaises(RuntimeError):
            self.client.add_playlist_items(
                "playlist-id",
                ["uri"] * 100,
            )

        post.assert_called_once()

    @patch("spotify_client.log_debug")
    @patch("spotify_client.requests.post")
    def test_failure_logs_items_written(
        self,
        post,
        log_debug,
    ):
        post.side_effect = [response(), response(500)]

        with self.assertRaises(requests.HTTPError):
            self.client.add_playlist_items(
                "playlist-id",
                ["uri"] * 200,
            )

        messages = [
            args[0]
            for args, _kwargs in log_debug.call_args_list
        ]
        self.assertTrue(any(
            "items_written=100" in message
            and "exception_type=HTTPError" in message
            for message in messages
        ))


class RoyalShuffleWorkflowTests(unittest.TestCase):
    @patch("royalshuffle.add_managed_playlist_id")
    @patch("royalshuffle.load_managed_playlist_ids", return_value=set())
    @patch("royalshuffle.shuffle_items", side_effect=lambda items: items)
    @patch("royalshuffle.log_debug")
    def test_local_items_are_skipped_before_population(
        self,
        _log_debug,
        _shuffle_items,
        _load_managed_playlist_ids,
        _add_managed_playlist_id,
    ):
        spotify = Mock()
        spotify.get_playlist_items.return_value = [
            {
                "uri": "spotify:track:copyable",
                "is_local": False,
            },
            {
                "uri": "spotify:episode:copyable",
                "is_local": False,
            },
            {
                "uri": "spotify:local:artist:album:track:123",
                "is_local": True,
            },
            {
                "uri": "spotify:local:other:album:track:456",
            },
        ]
        spotify.find_playlists_by_name.return_value = []
        spotify.create_playlist.return_value = {"id": "output-id"}
        status_messages = []

        result = royal_shuffle(
            spotify,
            {"id": "source-id", "name": "Source"},
            status_callback=status_messages.append,
        )

        spotify.add_playlist_items.assert_called_once_with(
            "output-id",
            [
                "spotify:track:copyable",
                "spotify:episode:copyable",
            ],
        )
        self.assertEqual(result["item_count"], 2)
        self.assertEqual(result["skipped_item_count"], 2)
        self.assertTrue(any(
            "Skipping 2 local Spotify items" in message
            for message in status_messages
        ))


if __name__ == "__main__":
    unittest.main()
