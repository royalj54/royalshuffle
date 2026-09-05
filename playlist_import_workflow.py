from dataclasses import dataclass

from auth import log_debug


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


def prepare_playlist_import(rows):
    rows = tuple(rows)
    log_debug(f"CSV import rows parsed={len(rows)}")
    log_debug(f"CSV import valid track URIs={len(rows)}")
    log_debug(
        f"CSV import unique track IDs={len(_unique_track_ids(rows))}"
    )
    log_debug("CSV import local validation complete")
    return PreparedPlaylistImport(rows=rows)


def create_imported_playlist(
    spotify,
    prepared_import,
    playlist_name,
    progress_callback=None,
):
    rows = prepared_import.rows
    if progress_callback:
        progress_callback("Creating playlist...")
    log_debug("CSV import creating destination playlist")
    try:
        playlist = spotify.create_playlist(
            name=playlist_name,
            description="Ordered CSV import created by RoyalShuffle",
            public=False,
        )
    except (Exception, KeyboardInterrupt) as exc:
        log_debug(
            "CSV import failed; stage=playlist_creation; "
            f"exception_type={type(exc).__name__}"
        )
        raise
    playlist_id = playlist["id"]
    log_debug(
        f"CSV import destination playlist created; playlist_id={playlist_id}"
    )
    uris = [row.uri for row in rows]

    def report_batch(batch_number, total_batches, first_item, last_item, total_items):
        if progress_callback:
            progress_callback(
                f"Adding tracks {first_item}-{last_item} of {total_items}..."
            )

    try:
        spotify.add_playlist_items(
            playlist_id,
            uris,
            progress_callback=report_batch,
        )
    except (Exception, KeyboardInterrupt) as exc:
        items_written = getattr(exc, "items_written", 0)
        total_items = getattr(exc, "total_items", len(uris))
        log_debug(
            "CSV import failed; stage=playlist_population; "
            f"playlist_id={playlist_id}; items_written={items_written}; "
            f"total_items={total_items}; "
            f"exception_type={type(exc).__name__}"
        )
        raise PlaylistImportPartialWriteError(
            playlist_id=playlist_id,
            playlist_name=playlist_name,
            items_written=items_written,
            total_items=total_items,
            cause=exc,
        ) from exc

    log_debug(
        "CSV import completed; "
        f"playlist_id={playlist_id}; items_written={len(uris)}"
    )

    return PlaylistImportResult(
        playlist_id=playlist_id,
        name=playlist_name,
        item_count=len(uris),
    )
