import csv
import tempfile
import unittest
from pathlib import Path

from playlist_export import COLUMNS
from playlist_import import (
    PlaylistImportValidationError,
    parse_playlist_csv,
)


TRACK_A = "spotify:track:1111111111111111111111"
TRACK_B = "spotify:track:2222222222222222222222"


class PlaylistImportParserTests(unittest.TestCase):
    def write_csv(self, text, encoding="utf-8"):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        path = Path(folder.name) / "playlist.csv"
        path.write_text(text, encoding=encoding)
        return path

    def issues_for(self, text):
        with self.assertRaises(PlaylistImportValidationError) as caught:
            parse_playlist_csv(self.write_csv(text))
        return caught.exception.issues

    def test_accepts_export_headers_utf8_bom_order_and_duplicates(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        path = Path(folder.name) / "export.csv"
        with path.open("w", newline="", encoding="utf-8-sig") as output:
            writer = csv.DictWriter(output, fieldnames=COLUMNS)
            writer.writeheader()
            writer.writerows([
                {"Playlist Position": "99", "Spotify URI": TRACK_A},
                {"Playlist Position": "1", "Spotify URI": TRACK_B},
                {"Playlist Position": "50", "Spotify URI": TRACK_A},
            ])

        rows = parse_playlist_csv(path)

        self.assertEqual([row.uri for row in rows], [TRACK_A, TRACK_B, TRACK_A])
        self.assertEqual([row.line_number for row in rows], [2, 3, 4])

    def test_allows_extra_columns_and_ignores_blank_rows(self):
        rows = parse_playlist_csv(self.write_csv(
            "Spotify URI,Extra\n"
            f"{TRACK_A},information\n"
            ",\n"
            f"{TRACK_B},more\n"
        ))

        self.assertEqual([row.uri for row in rows], [TRACK_A, TRACK_B])
        self.assertEqual([row.line_number for row in rows], [2, 4])

    def test_accumulates_blank_malformed_local_and_non_track_rows(self):
        issues = self.issues_for(
            "Spotify URI,Extra\n"
            "not-a-uri,\n"
            "spotify:local:artist:album:title:1,\n"
            "spotify:episode:1111111111111111111111,\n"
            "spotify:album:1111111111111111111111,\n"
            "spotify:artist:1111111111111111111111,\n"
            "spotify:playlist:1111111111111111111111,\n"
            "spotify:show:1111111111111111111111,\n"
            ",information\n"
        )

        self.assertEqual(
            [issue.line_number for issue in issues],
            [2, 3, 4, 5, 6, 7, 8, 9],
        )
        self.assertEqual(
            [issue.code for issue in issues],
            [
                "malformed_uri",
                "local_uri",
                "non_track_uri",
                "non_track_uri",
                "non_track_uri",
                "non_track_uri",
                "non_track_uri",
                "blank_uri",
            ],
        )

    def test_missing_required_column_fails(self):
        issues = self.issues_for("Track Name,URI\nSong,value\n")
        self.assertEqual(issues[0].code, "missing_spotify_uri_column")
        self.assertEqual(issues[0].line_number, 1)

    def test_empty_and_header_only_files_fail(self):
        empty = self.issues_for("")
        header_only = self.issues_for("Spotify URI\n")

        self.assertEqual(empty[0].code, "missing_header")
        self.assertEqual(header_only[0].code, "no_tracks")

    def test_malformed_csv_fails(self):
        issues = self.issues_for('Spotify URI\n"unterminated\n')
        self.assertEqual(issues[0].code, "malformed_csv")


if __name__ == "__main__":
    unittest.main()
