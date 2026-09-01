from dataclasses import dataclass

from spotify_client import SpotifyTrackNotFoundError


@dataclass(frozen=True)
class CatalogValidationIssue:
    line_number: int
    track_id: str
    code: str
    message: str


class CatalogValidationError(ValueError):
    def __init__(self, issues):
        self.issues = tuple(issues)
        super().__init__("Spotify catalog validation failed")


@dataclass(frozen=True)
class PreparedPlaylistImport:
    rows: tuple


@dataclass(frozen=True)
class PlaylistImportResult:
    playlist_id: str
    name: str
    item_count: int


class PlaylistImportPartialWriteError(Exception):
    def __init__(
        self,
        playlist_id,
        playlist_name,
        items_written,
        total_items,
        cause,
    ):
        self.playlist_id = playlist_id
        self.playlist_name = playlist_name
        self.items_written = items_written
        self.total_items = total_items
        self.cause = cause
        super().__init__("Spotify playlist import was only partially written")


def _unique_track_ids(rows):
    return tuple(dict.fromkeys(row.track_id for row in rows))


def _catalog_problem(track):
    if track.get("is_playable") is False:
        return "unavailable_track", "Track is unavailable for this account."

    restrictions = track.get("restrictions")
    if isinstance(restrictions, dict) and restrictions:
        return "restricted_track", "Track is restricted for this account."

    if track.get("type", "track") != "track":
        return "non_track_response", "Spotify did not return a track."

    return None


def preflight_playlist_import(spotify, rows):
    rows = tuple(rows)
    problems = {}
    for track_id in _unique_track_ids(rows):
        try:
            track = spotify.get_track(track_id)
        except SpotifyTrackNotFoundError:
            problems[track_id] = (
                "removed_track",
                "Track is unknown or has been removed from Spotify.",
            )
            continue

        problem = _catalog_problem(track)
        if problem:
            problems[track_id] = problem

    issues = []
    for row in rows:
        problem = problems.get(row.track_id)
        if problem:
            code, message = problem
            issues.append(CatalogValidationIssue(
                line_number=row.line_number,
                track_id=row.track_id,
                code=code,
                message=message,
            ))

    if issues:
        raise CatalogValidationError(issues)

    return PreparedPlaylistImport(rows=rows)


def create_imported_playlist(spotify, prepared_import, playlist_name):
    rows = prepared_import.rows
    playlist = spotify.create_playlist(
        name=playlist_name,
        description="Ordered CSV import created by RoyalShuffle",
        public=False,
    )
    playlist_id = playlist["id"]
    uris = [row.uri for row in rows]

    try:
        spotify.add_playlist_items(playlist_id, uris)
    except Exception as exc:
        raise PlaylistImportPartialWriteError(
            playlist_id=playlist_id,
            playlist_name=playlist_name,
            items_written=getattr(exc, "items_written", 0),
            total_items=getattr(exc, "total_items", len(uris)),
            cause=exc,
        ) from exc

    return PlaylistImportResult(
        playlist_id=playlist_id,
        name=playlist_name,
        item_count=len(uris),
    )
