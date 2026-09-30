import csv
import io
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch
from contextlib import ExitStack

import cli
import test_ui_playlist_discovery as discovery
from artist_separation import separate_artists
from playlist_export import export_playlist_csv
from royalshuffle import royal_shuffle, RoyalShuffleResult, SessionLengthError
import playlist_registry as registry
from spotify_client import SpotifyClient, get_unsupported_item_count


def track(identifier="a", **extra):
    return {"type": "track", "uri": f"spotify:track:{identifier}",
            "name": identifier, "artists": [{"id": identifier, "name": "Artist " + identifier}],
            "duration_ms": 180000, **extra}


def episode(**extra):
    return {"type": "episode", "uri": "spotify:episode:example",
            "artists": [{"id": "show-id", "name": None, "uri": "spotify:show:show-id"}],
            **extra}


def parse(*pages):
    responses = [Mock(json=Mock(return_value={
        "items": [{"item": item} for item in page],
        "next": "next-page" if index < len(pages) - 1 else None,
    })) for index, page in enumerate(pages)]
    with patch("spotify_client.log_debug"), \
         patch.object(SpotifyClient, "_request", side_effect=responses):
        return SpotifyClient("test-only").get_playlist_items("source")


class UnsupportedSourceTests(unittest.TestCase):
    def test_zero_eligible_membership_stops_before_all_output_and_registry_operations(self):
        sources = {
            "empty": [],
            "episode": parse([episode()]),
            "local": parse([track(uri="spotify:local:a:b:c:1", is_local=True)]),
        }
        for minutes in (None, 30, 60, 90, 240):
            for existing in (False, True):
                for label, items in sources.items():
                    with self.subTest(minutes=minutes, existing=existing, source=label), \
                         tempfile.TemporaryDirectory() as directory, ExitStack() as stack:
                        path = Path(directory) / "registry.json"
                        stack.enter_context(patch.object(registry, "REGISTRY_FILE", path))
                        if existing:
                            registry.register_timed_output("source", 30, "existing")
                        before = path.read_bytes() if path.exists() else None
                        registry_methods = [stack.enter_context(patch("royalshuffle." + name))
                                            for name in ("load_managed_playlist_ids", "timed_output_id",
                                                         "remove_timed_output", "register_timed_output",
                                                         "full_output_id", "register_full_output", "remove_full_output")]
                        stack.enter_context(patch("royalshuffle.log_debug"))
                        separate = stack.enter_context(patch("royalshuffle.separate_artists"))
                        client = Mock()
                        client.get_playlist_items.return_value = items
                        client.find_playlists_by_name.return_value = (
                            [{"id": "existing", "name": "Output"}] if existing else [])
                        statuses = []
                        with self.assertRaisesRegex(SessionLengthError, "No eligible tracks remain") as caught:
                            royal_shuffle(client, {"id": "source", "name": "Source"},
                                          session_minutes=minutes, artist_separation=True,
                                          status_callback=statuses.append)
                        self.assertEqual([call[0] for call in client.method_calls], ["get_playlist_items"])
                        for method in registry_methods:
                            method.assert_not_called()
                        separate.assert_not_called()
                        self.assertEqual(path.read_bytes() if path.exists() else None, before)
                        self.assertEqual(statuses[-1], str(caught.exception))
                        self.assertIn("choose another source playlist", str(caught.exception))
                        if label == "episode":
                            self.assertIn("Skipped 1 unsupported", str(caught.exception))

    def test_mixed_pages_filter_before_artist_parsing_and_keep_track_metadata(self):
        items = parse([track(), episode()], [track("b"), episode(type="show")])
        self.assertEqual(len(items), 2)
        self.assertEqual(get_unsupported_item_count(items), 2)
        self.assertEqual([i["playlist_position"] for i in items], [1, 3])
        self.assertEqual([i["primary_artist_id"] for i in items], ["a", "b"])
        self.assertEqual([i["artists"] for i in items], ["Artist a", "Artist b"])
        self.assertEqual([i["duration_ms"] for i in items], [180000, 180000])

    def test_missing_type_uri_fallback_and_conflicting_nontrack_identity(self):
        legacy = track()
        del legacy["type"]
        local = track("local", uri="spotify:local:artist:album:track:123", is_local=True)
        del local["type"]
        unknown = episode()
        del unknown["type"]
        items = parse([legacy, local, unknown, episode(type="track"),
                       episode(uri="spotify:track:misleading")])
        self.assertEqual(len(items), 2)
        self.assertEqual(get_unsupported_item_count(items), 3)
        self.assertTrue(items[1]["is_local"])
        self.assertEqual(get_unsupported_item_count(parse([track()])), 0)
        self.assertEqual(get_unsupported_item_count([]), 0)

    def test_generation_and_separation_only_receive_supported_nonlocal_tracks(self):
        items = parse([track(), episode(), track("b"),
                       track("local", uri="spotify:local:a:b:c:1", is_local=True)])
        for minutes in (None, 30):
            with self.subTest(minutes=minutes):
                client = Mock()
                client.get_playlist_items.return_value = items
                client.find_playlists_by_name.return_value = []
                client.create_playlist.return_value = {"id": "output"}
                client.add_playlist_items.side_effect = lambda _, uris: len(uris)
                statuses = []
                with patch("royalshuffle.log_debug"), \
                     patch("royalshuffle.full_output_id", return_value=None), \
                     patch("royalshuffle.timed_output_id", return_value=None), \
                     patch("royalshuffle.register_full_output"), \
                     patch("royalshuffle.register_timed_output"), \
                     patch("royalshuffle.separate_artists", wraps=separate_artists) as separate:
                    result = royal_shuffle(client, {"id": "source", "name": "Source"},
                                           session_minutes=minutes, artist_separation=True,
                                           status_callback=statuses.append)
                self.assertEqual({i["primary_artist_id"] for i in separate.call_args.args[0]}, {"a", "b"})
                self.assertCountEqual(client.add_playlist_items.call_args.args[1],
                                      ["spotify:track:a", "spotify:track:b"])
                self.assertEqual(result.unsupported_item_count, 1)
                self.assertEqual(result.skipped_item_count, 1)
                self.assertIn("Skipped 1 unsupported playlist item(s).", statuses)
                self.assertTrue(any("Skipping 1 local Spotify" in s for s in statuses))

    def test_cli_csv_export_writes_supported_rows_and_reports_count(self):
        items = parse([track(), episode(), track("b")])
        client = Mock()
        client.get_playlist_items.return_value = items
        output = io.StringIO()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "export.csv"
            with patch("cli.restore_spotify_client", return_value=client), \
                 patch("cli._resolve_playlist", return_value={"id": "source", "name": "Source"}), \
                 patch("cli._export_destination", return_value=path):
                cli.export_playlist("source", str(path), output)
            with path.open(encoding="utf-8-sig", newline="") as file:
                rows = list(csv.DictReader(file))
            self.assertEqual([row["Spotify URI"] for row in rows], ["spotify:track:a", "spotify:track:b"])
        self.assertIn("Skipped 1 unsupported playlist item(s).", output.getvalue())


class UnsupportedSourceUiTests(unittest.TestCase):
    setUp = discovery.PlaylistDiscoveryTests.setUp

    def test_export_status_reports_unsupported_count(self):
        items = parse([track(), episode()])
        self.client.get_playlist_items.return_value = items
        with patch("ui.choose_csv_destination", return_value="export.csv"), \
             patch("ui.export_playlist_csv", return_value=1) as export:
            self.export.invoke()
        export.assert_called_once_with("export.csv", items)
        self.assertIn("1 unsupported skipped", self.status.config.call_args.kwargs["text"])

    def test_generation_status_distinguishes_local_and_unsupported_counts(self):
        result = RoyalShuffleResult("Source", "source", "Output", "out", 1, 1, 2,
                                    "created", unsupported_item_count=3)
        with patch("ui.simpledialog.askstring", return_value="Output"), \
             patch("ui.royal_shuffle", return_value=result):
            self.shuffle.invoke()
        text = self.status.config.call_args.kwargs["text"]
        self.assertIn("2 local skipped", text)
        self.assertIn("3 unsupported skipped", text)
