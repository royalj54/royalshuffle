import unittest
from pathlib import Path
from unittest.mock import patch

import requests
import playlist_registry as registry
from royalshuffle import royal_shuffle, SessionLengthError
from spotify_client import SpotifyClient, SpotifyRetryLaterError
from test_session_length import SessionLengthTests, item
from test_spotify_client import response


class StaleOutputRecoveryTests(unittest.TestCase):
    def setUp(self):
        SessionLengthTests.setUp(self)
        registry.register_timed_output("source", 30, "old")
        self.before = self.registry_path.read_bytes()
        self.spotify.get_playlist_items.return_value = [item(1000)]
        self.spotify.get_playlists.return_value = []

    def http_error(self, status):
        return response(status).raise_for_status.side_effect

    def run_shuffle(self):
        return royal_shuffle(self.spotify, self.source, session_minutes=30)

    def assert_unmutated(self):
        self.assertEqual(self.registry_path.read_bytes(), self.before)
        self.spotify.create_playlist.assert_not_called()
        self.spotify.clear_playlist.assert_not_called()
        self.spotify.add_playlist_items.assert_not_called()

    def test_direct_404_removes_before_creation_and_registers_replacement(self):
        self.spotify.get_playlist.side_effect = self.http_error(404)
        def create(**kwargs):
            self.assertIsNone(registry.timed_output_id("source", 30))
            self.assertNotIn("old", registry.load_managed_playlist_ids())
            return {"id": "new"}
        def clear(output_id):
            self.assertEqual(output_id, "new")
            self.assertEqual(registry.timed_output_id("source", 30), "new")
        self.spotify.create_playlist.side_effect = create
        self.spotify.clear_playlist.side_effect = clear
        result = self.run_shuffle()
        self.spotify.get_playlist.assert_called_once_with("old")
        self.assertEqual(result.output_id, "new")
        self.assertEqual(result.action, "created")
        self.assertEqual(registry.load_managed_playlist_ids(), {"new"})
        self.spotify.clear_playlist.assert_called_once_with("new")
        self.spotify.add_playlist_items.assert_called_once_with("new", ["spotify:track:one"])

    def test_direct_success_reuses_exact_id_without_removal(self):
        self.spotify.get_playlist.return_value = {"id": "old", "name": "Renamed output"}
        with patch("royalshuffle.remove_timed_output") as remove:
            result = self.run_shuffle()
        remove.assert_not_called()
        self.spotify.create_playlist.assert_not_called()
        self.spotify.clear_playlist.assert_called_once_with("old")
        self.assertEqual(result.output_name, "Renamed output")
        self.assertEqual(result.action, "updated")
        self.assertEqual(self.registry_path.read_bytes(), self.before)

    def test_non_404_errors_preserve_binding_and_abort(self):
        failures = [self.http_error(status) for status in (400, 401, 403, 429, 500, 503)]
        failures += [requests.ConnectionError("private transport details"),
                     SpotifyRetryLaterError("rate limited"), ValueError("invalid JSON")]
        for failure in failures:
            with self.subTest(error=type(failure).__name__, status=getattr(failure, "response", None)):
                self.spotify.reset_mock()
                self.spotify.get_playlist.side_effect = failure
                with patch("royalshuffle.remove_timed_output") as remove, \
                     patch("royalshuffle.log_debug") as log:
                    with self.assertRaises(type(failure)):
                        self.run_shuffle()
                remove.assert_not_called()
                self.assertNotIn("private transport details", str(log.call_args_list))
                self.assert_unmutated()

    def test_invalid_success_payload_cannot_redirect_output(self):
        for payload in (None, {}, {"id": "source", "name": "Source"}):
            with self.subTest(payload=payload):
                self.spotify.reset_mock()
                self.spotify.get_playlist.return_value = payload
                with self.assertRaises(SessionLengthError):
                    self.run_shuffle()
                self.assert_unmutated()

    def test_atomic_removal_persistence_failure_prevents_replacement(self):
        self.spotify.get_playlist.side_effect = self.http_error(404)
        with patch.object(Path, "replace", side_effect=OSError("disk error")):
            with self.assertRaises(OSError):
                self.run_shuffle()
        self.assert_unmutated()
        self.assertEqual(registry.timed_output_id("source", 30), "old")

    def test_changed_expected_binding_prevents_replacement(self):
        self.spotify.get_playlist.side_effect = self.http_error(404)
        with patch("royalshuffle.remove_timed_output", side_effect=ValueError("binding changed")):
            with self.assertRaises(ValueError):
                self.run_shuffle()
        self.assert_unmutated()

    def test_source_id_aborts_before_lookup_or_removal(self):
        # Exercise workflow defense even though the registry loader also rejects this.
        with patch("royalshuffle.timed_output_id", return_value="source"), \
             patch("royalshuffle.remove_timed_output") as remove:
            with self.assertRaisesRegex(SessionLengthError, "source playlist"):
                self.run_shuffle()
        remove.assert_not_called()
        self.spotify.get_playlists.assert_not_called()
        self.spotify.get_playlist.assert_not_called()
        self.assert_unmutated()

    def test_listed_output_reuse_does_not_lookup_or_remove(self):
        self.spotify.get_playlists.return_value = [{"id": "old", "name": "Output"}]
        with patch("royalshuffle.remove_timed_output") as remove:
            result = self.run_shuffle()
        remove.assert_not_called()
        self.spotify.get_playlist.assert_not_called()
        self.spotify.create_playlist.assert_not_called()
        self.spotify.clear_playlist.assert_called_once_with("old")
        self.assertEqual(result.output_id, "old")
        self.assertEqual(self.registry_path.read_bytes(), self.before)


class DirectPlaylistLookupTests(unittest.TestCase):
    @patch("spotify_client.log_debug")
    @patch("spotify_client.requests.get")
    def test_authenticated_exact_endpoint_and_404_status(self, get, _log):
        client = SpotifyClient("test-only")
        get.return_value = response(200, payload={"id": "registered"})
        self.assertEqual(client.get_playlist("registered"), {"id": "registered"})
        get.assert_called_once_with("https://api.spotify.com/v1/playlists/registered",
                                    headers=client.headers)
        get.return_value = response(404)
        with self.assertRaises(requests.HTTPError) as caught:
            client.get_playlist("registered")
        self.assertEqual(caught.exception.response.status_code, 404)

    @patch("spotify_client.log_debug")
    @patch("spotify_client.time.sleep")
    @patch("spotify_client.requests.get")
    def test_lookup_uses_existing_429_retry_behavior(self, get, sleep, _log):
        get.side_effect = [response(429, retry_after="2"),
                           response(200, payload={"id": "registered"})]
        self.assertEqual(SpotifyClient("test-only").get_playlist("registered"),
                         {"id": "registered"})
        self.assertEqual(get.call_count, 2)
        sleep.assert_called_once_with(2)
