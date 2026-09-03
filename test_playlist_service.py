import unittest
from unittest.mock import patch

from playlist_service import eligible_source_playlists


class PlaylistServiceTests(unittest.TestCase):
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
