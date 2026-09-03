from playlist_registry import load_managed_playlist_ids


def eligible_source_playlists(playlists, managed_playlist_ids=None):
    if managed_playlist_ids is None:
        managed_playlist_ids = load_managed_playlist_ids()

    return [
        playlist
        for playlist in playlists
        if playlist["id"] not in managed_playlist_ids
    ]
