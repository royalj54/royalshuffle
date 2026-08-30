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

from spotify_client import SpotifyClient


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
    @patch("spotify_client.time.sleep")
    @patch("spotify_client.requests.post")
    def test_nineteen_write_batches_are_not_paced(
        self,
        post,
        sleep,
        _log_debug,
    ):
        post.return_value = response()

        self.client.add_playlist_items(
            "playlist-id",
            ["uri"] * 1900,
        )

        self.assertEqual(post.call_count, 19)
        sleep.assert_not_called()

    @patch("spotify_client.log_debug")
    @patch("spotify_client.time.sleep")
    @patch("spotify_client.requests.post")
    def test_twenty_write_batches_with_no_more_work_are_not_paced(
        self,
        post,
        sleep,
        _log_debug,
    ):
        post.return_value = response()

        self.client.add_playlist_items(
            "playlist-id",
            ["uri"] * 2000,
        )

        self.assertEqual(post.call_count, 20)
        sleep.assert_not_called()

    @patch("spotify_client.log_debug")
    @patch("spotify_client.time.sleep")
    @patch("spotify_client.requests.post")
    def test_twenty_completed_write_batches_with_more_work_are_paced(
        self,
        post,
        sleep,
        _log_debug,
    ):
        post.return_value = response()

        self.client.add_playlist_items(
            "playlist-id",
            ["uri"] * 2001,
        )

        self.assertEqual(post.call_count, 21)
        self.assertEqual(sleep.call_args_list, [call(1)])

    @patch("spotify_client.log_debug")
    @patch("spotify_client.time.sleep")
    @patch("spotify_client.requests.get")
    def test_twenty_completed_read_pages_with_more_work_are_paced(
        self,
        get,
        sleep,
        log_debug,
    ):
        get.side_effect = [
            response(
                200,
                payload={
                    "items": [],
                    "next": (
                        f"next-page-{page + 1}"
                        if page < 21
                        else None
                    ),
                    "total": 2001,
                },
            )
            for page in range(1, 22)
        ]

        self.client.get_playlist_items("playlist-id")

        self.assertEqual(get.call_count, 21)
        self.assertEqual(sleep.call_args_list, [call(1)])
        messages = [
            args[0]
            for args, _kwargs in log_debug.call_args_list
        ]
        self.assertTrue(any(
            "items=0; pages=21" in message
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
    def test_rate_limit_stops_after_three_retries(
        self,
        post,
        sleep,
        _log_debug,
    ):
        post.side_effect = [response(429, retry_after="2")] * 4

        with self.assertRaises(requests.HTTPError):
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


if __name__ == "__main__":
    unittest.main()
