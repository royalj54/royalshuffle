from auth import (
    load_token_data,
    refresh_access_token,
    save_token_data,
    saved_token_file_exists,
)
from spotify_client import SpotifyClient


class SavedSessionError(Exception):
    pass


class NoSavedSessionError(SavedSessionError):
    pass


class InvalidSavedSessionError(SavedSessionError):
    pass


def refresh_saved_token_data(saved_token_data):
    if not isinstance(saved_token_data, dict):
        raise InvalidSavedSessionError("Saved Spotify session is invalid.")

    refresh_token = saved_token_data.get("refresh_token")
    if not isinstance(refresh_token, str) or not refresh_token:
        raise InvalidSavedSessionError(
            "Saved Spotify session has no refresh token. Run 'royalshuffle auth'."
        )

    refreshed_token_data = refresh_access_token(refresh_token)
    if not isinstance(refreshed_token_data, dict):
        raise InvalidSavedSessionError("Spotify returned invalid token data.")

    access_token = refreshed_token_data.get("access_token")
    if not isinstance(access_token, str) or not access_token:
        raise InvalidSavedSessionError("Spotify returned no access token.")

    saved_token_data.update(refreshed_token_data)
    save_token_data(saved_token_data)
    return saved_token_data


def restore_saved_access_token():
    saved_token_data = load_token_data()
    if saved_token_data is None:
        if saved_token_file_exists():
            raise InvalidSavedSessionError(
                "Saved Spotify session could not be read. Run 'royalshuffle auth'."
            )
        raise NoSavedSessionError(
            "No saved Spotify session. Run 'royalshuffle auth' first."
        )

    return refresh_saved_token_data(saved_token_data)["access_token"]


def restore_spotify_client():
    return SpotifyClient(restore_saved_access_token())
