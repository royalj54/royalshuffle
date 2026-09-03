import unittest
from unittest.mock import Mock, patch

import session_service


class SessionServiceTests(unittest.TestCase):
    @patch("session_service.save_token_data")
    @patch(
        "session_service.refresh_access_token",
        return_value={"access_token": "new-access"},
    )
    def test_refresh_merges_and_saves_token_data(self, refresh, save):
        saved = {
            "access_token": "old-access",
            "refresh_token": "refresh-token",
        }

        result = session_service.refresh_saved_token_data(saved)

        self.assertEqual(result["access_token"], "new-access")
        self.assertEqual(result["refresh_token"], "refresh-token")
        refresh.assert_called_once_with("refresh-token")
        save.assert_called_once_with(saved)

    @patch("session_service.saved_token_file_exists", return_value=False)
    @patch("session_service.load_token_data", return_value=None)
    def test_missing_saved_session_is_distinct(self, _load, _exists):
        with self.assertRaises(session_service.NoSavedSessionError):
            session_service.restore_saved_access_token()

    @patch("session_service.saved_token_file_exists", return_value=True)
    @patch("session_service.load_token_data", return_value=None)
    def test_unreadable_saved_session_is_invalid_state(self, _load, _exists):
        with self.assertRaises(session_service.InvalidSavedSessionError):
            session_service.restore_saved_access_token()

    @patch("session_service.save_token_data")
    @patch("session_service.refresh_access_token", return_value={})
    def test_refresh_without_access_token_is_invalid(self, _refresh, save):
        with self.assertRaises(session_service.InvalidSavedSessionError):
            session_service.refresh_saved_token_data({
                "refresh_token": "refresh-token"
            })

        save.assert_not_called()

    @patch("session_service.SpotifyClient")
    @patch(
        "session_service.restore_saved_access_token",
        return_value="access-token",
    )
    def test_restored_client_uses_refreshed_access_token(self, _restore, client):
        expected = Mock()
        client.return_value = expected

        self.assertIs(session_service.restore_spotify_client(), expected)
        client.assert_called_once_with("access-token")


if __name__ == "__main__":
    unittest.main()
