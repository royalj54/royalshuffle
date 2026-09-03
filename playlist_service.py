from playlist_registry import load_managed_playlist_ids


class PlaylistSourceError(ValueError):
    pass


class PlaylistSourceNotFoundError(PlaylistSourceError):
    pass


class AmbiguousPlaylistSourceError(PlaylistSourceError):
    pass


class ManagedPlaylistSourceError(PlaylistSourceError):
    pass


def eligible_source_playlists(playlists, managed_playlist_ids=None):
    if managed_playlist_ids is None:
        managed_playlist_ids = load_managed_playlist_ids()

    return [
        playlist
        for playlist in playlists
        if playlist["id"] not in managed_playlist_ids
    ]


def _playlist_id_from_reference(reference):
    if reference.startswith("spotify:playlist:"):
        return reference.removeprefix("spotify:playlist:").split(":", 1)[0]

    for prefix in (
        "https://open.spotify.com/playlist/",
        "http://open.spotify.com/playlist/",
    ):
        if reference.startswith(prefix):
            return reference.removeprefix(prefix).split("?", 1)[0].split("/", 1)[0]

    return reference


def resolve_source_playlist(playlists, reference, managed_playlist_ids=None):
    playlists = list(playlists)
    if managed_playlist_ids is None:
        managed_playlist_ids = load_managed_playlist_ids()

    playlist_id = _playlist_id_from_reference(reference)
    id_match = next(
        (playlist for playlist in playlists if playlist.get("id") == playlist_id),
        None,
    )
    if id_match is not None:
        if id_match["id"] in managed_playlist_ids:
            raise ManagedPlaylistSourceError(
                "The requested playlist is a managed RoyalShuffle output."
            )
        return id_match

    name_matches = [
        playlist
        for playlist in eligible_source_playlists(playlists, managed_playlist_ids)
        if playlist.get("name") == reference
    ]
    if len(name_matches) > 1:
        raise AmbiguousPlaylistSourceError(
            "More than one eligible playlist has that exact name; use an ID, URI, or URL."
        )
    if name_matches:
        return name_matches[0]

    if any(
        playlist.get("name") == reference
        and playlist.get("id") in managed_playlist_ids
        for playlist in playlists
    ):
        raise ManagedPlaylistSourceError(
            "The requested playlist is a managed RoyalShuffle output."
        )

    raise PlaylistSourceNotFoundError("No eligible playlist matched that reference.")
