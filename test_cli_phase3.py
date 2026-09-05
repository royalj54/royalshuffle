import csv
import io
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import Mock, call, patch

import requests

import cli
from playlist_export import COLUMNS, export_playlist_csv
from playlist_import import (
    PlaylistImportRow,
    PlaylistImportValidationError,
    PlaylistImportValidationIssue,
)
from playlist_import_workflow import (
    PlaylistImportPartialWriteError,
    PlaylistImportResult,
)
from playlist_service import resolve_source_playlist
from spotify_client import SpotifyQuotaExceededError


TRACK_A = "spotify:track:" + "1" * 22
TRACK_B = "spotify:track:" + "2" * 22


class Phase3CliTests(unittest.TestCase):
    def run_cli(self, arguments):
        stdout = io.StringIO()
        stderr = io.StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            result = cli.main(arguments)
        return result, stdout.getvalue(), stderr.getvalue()

    def temporary_path(self, name="playlist.csv"):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        return Path(folder.name) / name

    def test_export_resolver_accepts_id_uri_url_and_unique_exact_name(self):
        playlist = {"id": "abc123", "name": "Exact Name"}
        references = (
            "abc123",
            "spotify:playlist:abc123",
            "https://open.spotify.com/playlist/abc123?si=value",
            "Exact Name",
        )
        for reference in references:
            with self.subTest(reference=reference):
                self.assertIs(resolve_source_playlist([playlist], reference, set()), playlist)

    @patch("cli.export_playlist_csv", return_value=2)
    @patch("cli._resolve_playlist")
    @patch("cli.restore_spotify_client")
    def test_export_explicit_path_reports_success(self, restore, resolve, export):
        destination = self.temporary_path("nested/export.csv")
        source = {"id": "source-id", "name": "Source"}
        resolve.return_value = source
        restore.return_value.get_playlist_items.return_value = [Mock(), Mock()]

        result, stdout, stderr = self.run_cli(
            ["export", "spotify:playlist:source-id", "--output", str(destination)]
        )

        self.assertEqual(result, cli.EXIT_SUCCESS)
        self.assertEqual(stderr, "")
        self.assertIn("Source: Source", stdout)
        self.assertIn("Rows exported: 2", stdout)
        self.assertIn(str(destination.resolve()), stdout)
        self.assertTrue(destination.parent.is_dir())
        export.assert_called_once_with(destination, restore.return_value.get_playlist_items.return_value)

    @patch("cli.export_playlist_csv", return_value=1)
    @patch("cli.ensure_exports_folder")
    @patch("cli._resolve_playlist", return_value={"id": "id", "name": "A/B"})
    @patch("cli.restore_spotify_client")
    def test_export_default_uses_platform_folder_and_safe_name(
        self, restore, _resolve, ensure, export
    ):
        folder = self.temporary_path("Exports")
        folder.mkdir()
        ensure.return_value = folder

        result, _stdout, stderr = self.run_cli(["export", "id"])

        self.assertEqual(result, 0)
        self.assertEqual(stderr, "")
        export.assert_called_once_with(folder / "A_B.csv", restore.return_value.get_playlist_items.return_value)

    def test_export_refuses_existing_file_without_fetching_items(self):
        destination = self.temporary_path()
        destination.write_text("keep", encoding="utf-8")
        with patch("cli._resolve_playlist", return_value={"id": "id", "name": "Name"}), patch(
            "cli.restore_spotify_client"
        ) as restore:
            result, stdout, stderr = self.run_cli(
                ["export", "id", "--output", str(destination)]
            )
        self.assertEqual(result, cli.EXIT_LOCAL_STATE)
        self.assertEqual(stdout, "")
        self.assertIn("already exists", stderr)
        self.assertEqual(destination.read_text(encoding="utf-8"), "keep")
        restore.return_value.get_playlist_items.assert_not_called()

    def test_export_maps_network_and_quota_failures(self):
        for failure, expected in (
            (requests.ConnectionError("offline"), cli.EXIT_API),
            (SpotifyQuotaExceededError("quota"), cli.EXIT_RETRY_LATER),
        ):
            with self.subTest(failure=type(failure).__name__), patch(
                "cli.export_playlist", side_effect=failure
            ):
                result, stdout, stderr = self.run_cli(["export", "id"])
            self.assertEqual(result, expected)
            self.assertEqual(stdout, "")
            self.assertTrue(stderr)

    @patch("cli.create_imported_playlist")
    @patch("cli.prepare_playlist_import")
    @patch("cli.parse_playlist_csv")
    @patch("cli.restore_spotify_client")
    def test_import_preflights_then_creates_and_reports_counts(
        self, restore, parse, prepare, create
    ):
        rows = (
            PlaylistImportRow(2, TRACK_A, "1" * 22),
            PlaylistImportRow(3, TRACK_B, "2" * 22),
            PlaylistImportRow(4, TRACK_A, "1" * 22),
        )
        parse.return_value = rows
        prepared = prepare.return_value
        create.return_value = PlaylistImportResult("output-id", "Exact Copy", 3)
        source = self.temporary_path()

        result, stdout, stderr = self.run_cli(
            ["import", str(source), "--name", " Exact Copy "]
        )

        self.assertEqual(result, 0)
        self.assertEqual(stderr, "")
        self.assertIn("Tracks written: 3/3", stdout)
        self.assertIn("Output playlist ID: output-id", stdout)
        prepare.assert_called_once_with(rows)
        create.assert_called_once_with(restore.return_value, prepared, "Exact Copy")

    @patch("cli.create_imported_playlist")
    @patch("cli.prepare_playlist_import")
    @patch("cli.parse_playlist_csv")
    @patch("cli.restore_spotify_client")
    def test_import_validation_failure_creates_no_output(
        self, _restore, parse, prepare, create
    ):
        parse.return_value = (PlaylistImportRow(2, TRACK_A, "1" * 22),)
        parse.side_effect = PlaylistImportValidationError([
            PlaylistImportValidationIssue(2, "malformed_uri", "Track URI was malformed.")
        ])
        result, stdout, stderr = self.run_cli(
            ["import", str(self.temporary_path()), "--name", "Copy"]
        )
        self.assertEqual(result, cli.EXIT_USAGE)
        self.assertEqual(stdout, "")
        self.assertIn("Row 2: Track URI was malformed.", stderr)
        create.assert_not_called()

    def test_import_partial_population_is_preserved_and_returns_8(self):
        error = PlaylistImportPartialWriteError(
            "partial-id", "Copy", 100, 150, requests.ConnectionError("offline")
        )
        with patch("cli.import_playlist", side_effect=error):
            result, stdout, stderr = self.run_cli(
                ["import", "source.csv", "--name", "Copy"]
            )
        self.assertEqual(result, cli.EXIT_PARTIAL)
        self.assertEqual(stdout, "")
        self.assertIn("Output playlist ID: partial-id", stderr)
        self.assertIn("Tracks written: 100/150", stderr)

    def test_import_interrupt_before_and_after_output(self):
        with patch("cli.import_playlist", side_effect=KeyboardInterrupt()):
            before = self.run_cli(["import", "source.csv", "--name", "Copy"])
        after_error = PlaylistImportPartialWriteError(
            "output-id", "Copy", 0, 5, KeyboardInterrupt()
        )
        with patch("cli.import_playlist", side_effect=after_error):
            after = self.run_cli(["import", "source.csv", "--name", "Copy"])
        self.assertEqual(before[0], 130)
        self.assertNotIn("Output playlist ID", before[2])
        self.assertEqual(after[0], 130)
        self.assertIn("Output playlist ID: output-id", after[2])
        self.assertIn("Tracks written: 0/5", after[2])

    def test_import_requires_name_and_rejects_blank_name(self):
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as caught:
            cli.main(["import", "source.csv"])
        self.assertEqual(caught.exception.code, cli.EXIT_USAGE)
        result, stdout, stderr = self.run_cli(
            ["import", "source.csv", "--name", "   "]
        )
        self.assertEqual(result, cli.EXIT_LOCAL_STATE)
        self.assertEqual(stdout, "")
        self.assertIn("cannot be empty", stderr)


class CsvExportContentTests(unittest.TestCase):
    def test_schema_utf8_order_duplicates_local_items_and_quoting(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        path = Path(folder.name) / "export.csv"
        items = [
            {
                "playlist_position": 1, "name": "Café, Song", "artists": "=Artist",
                "album": "Album", "duration_ms": 61000, "spotify_url": "https://one",
                "uri": TRACK_A, "date_added": "2026-01-01", "added_by": "user",
                "disc_number": 1, "track_number": 2, "explicit": True,
            },
            {
                "playlist_position": 2, "name": "Local", "artists": "Artist",
                "uri": "spotify:local:Artist:Album:Local:123", "is_local": True,
            },
            {
                "playlist_position": 3, "name": "Café, Song", "artists": "=Artist",
                "album": "Album", "duration_ms": 61000, "spotify_url": "https://one",
                "uri": TRACK_A, "date_added": "2026-01-01", "added_by": "user",
                "disc_number": 1, "track_number": 2, "explicit": True,
            },
        ]

        self.assertEqual(export_playlist_csv(path, items), 3)
        self.assertTrue(path.read_bytes().startswith(b"\xef\xbb\xbf"))
        with path.open(newline="", encoding="utf-8-sig") as source:
            reader = csv.DictReader(source)
            rows = list(reader)
        self.assertEqual(reader.fieldnames, COLUMNS)
        self.assertEqual([row["Spotify URI"] for row in rows], [TRACK_A, items[1]["uri"], TRACK_A])
        self.assertEqual(rows[0]["Track Name"], "Café, Song")
        self.assertEqual(rows[0]["Artists"], "'=Artist")


if __name__ == "__main__":
    unittest.main()
