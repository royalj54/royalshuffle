import unittest
from unittest.mock import patch

from playlist_service import (
    AmbiguousPlaylistSourceError,
    ManagedPlaylistSourceError,
    PlaylistSourceNotFoundError,
    eligible_source_playlists,
    resolve_source_playlist,
)


class PlaylistServiceTests(unittest.TestCase):
    def setUp(self):
        self.playlists = [
            {"id": "first-id", "name": "First"},
            {"id": "second-id", "name": "Same"},
            {"id": "third-id", "name": "Same"},
            {"id": "managed-id", "name": "Managed"},
        ]

    def test_resolves_by_id_uri_and_url(self):
        references = (
            "first-id",
            "spotify:playlist:first-id",
            "https://open.spotify.com/playlist/first-id?si=value",
        )
        for reference in references:
            with self.subTest(reference=reference):
                result = resolve_source_playlist(
                    self.playlists,
                    reference,
                    {"managed-id"},
                )
                self.assertEqual(result["id"], "first-id")

    def test_resolves_unique_exact_name(self):
        result = resolve_source_playlist(self.playlists, "First", {"managed-id"})
        self.assertEqual(result["id"], "first-id")

    def test_rejects_duplicate_exact_name(self):
        with self.assertRaises(AmbiguousPlaylistSourceError):
            resolve_source_playlist(self.playlists, "Same", {"managed-id"})

    def test_rejects_managed_id_but_not_random_suffix(self):
        with self.assertRaises(ManagedPlaylistSourceError):
            resolve_source_playlist(self.playlists, "managed-id", {"managed-id"})
        with self.assertRaises(ManagedPlaylistSourceError):
            resolve_source_playlist(self.playlists, "Managed", {"managed-id"})

        result = resolve_source_playlist(
            [{"id": "source-id", "name": "Name - RANDOM"}],
            "Name - RANDOM",
            set(),
        )
        self.assertEqual(result["id"], "source-id")

    def test_missing_reference_is_clear(self):
        with self.assertRaises(PlaylistSourceNotFoundError):
            resolve_source_playlist(self.playlists, "Missing", {"managed-id"})

    def test_excludes_only_explicitly_managed_playlist_ids(self):
        playlists = [
            {"id": "source-id", "name": "Source"},
            {"id": "managed-id", "name": "Anything"},
            {"id": "name-only", "name": "Source - RANDOM"},
        ]

        result = eligible_source_playlists(playlists, {"managed-id"})

        self.assertEqual(
            [playlist["id"] for playlist in result],
            ["source-id", "name-only"],
        )

    @patch(
        "playlist_service.load_managed_playlist_ids",
        return_value={"managed-id"},
    )
    def test_loads_shared_registry_when_ids_are_not_supplied(self, load_ids):
        result = eligible_source_playlists([
            {"id": "managed-id", "name": "Managed"},
            {"id": "source-id", "name": "Source"},
        ])

        self.assertEqual(result, [{"id": "source-id", "name": "Source"}])
        load_ids.assert_called_once_with()


if __name__ == "__main__":
    unittest.main()
