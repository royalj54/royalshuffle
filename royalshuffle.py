from dataclasses import dataclass

from auth import authenticate, log_debug
from app_metadata import MANAGED_PLAYLIST_DESCRIPTION
from playlist_selector import select_playlist
from playlist_service import eligible_source_playlists
from playlist_registry import (
    add_managed_playlist_id,
    load_managed_playlist_ids,
)
from shuffle_engine import shuffle_items
from spotify_client import SpotifyClient


@dataclass(frozen=True)
class RoyalShuffleResult:
    source_name: str
    source_id: str
    output_name: str
    output_id: str
    total_items: int
    items_written: int
    skipped_item_count: int
    action: str


class RoyalShufflePartialWriteError(Exception):
    def __init__(self, result, cause):
        self.result = result
        self.cause = cause
        super().__init__("Royal Shuffle output was only partially written")

def royal_shuffle(
    spotify, 
    source_playlist,
    status_callback=None,
    output_playlist_name=None,
):
    def report_status(message):
        if status_callback:
            status_callback(message)

    source_playlist_id = source_playlist["id"]
    if output_playlist_name is None:
        output_playlist_name = f'{source_playlist["name"]} - RANDOM'

    report_status("Reading source playlist...")

    items = spotify.get_playlist_items(source_playlist_id)
    log_debug(
        f"RoyalShuffle source item count={len(items)}"
    )

    copyable_items = [
        item
        for item in items
        if not item.get("is_local", False)
        and not item["uri"].startswith("spotify:local:")
    ]
    skipped_item_count = len(items) - len(copyable_items)
    log_debug(
        "RoyalShuffle local item filtering complete; "
        f"copyable_items={len(copyable_items)}; "
        f"skipped_local_items={skipped_item_count}"
    )

    if skipped_item_count:
        report_status(
            f"Skipping {skipped_item_count} local Spotify "
            "items that cannot be copied..."
        )

    items = copyable_items
    
    report_status(
        f'Shuffling {len(items)} items...'
    )

    items = shuffle_items(items)

    report_status(
        f'Preparing {output_playlist_name}...'
    )
    
    matching_playlists = spotify.find_playlists_by_name(
        output_playlist_name
    )
    managed_playlist_ids = load_managed_playlist_ids()
    managed_matches = [
        playlist
        for playlist in matching_playlists
        if playlist["id"] in managed_playlist_ids
    ]

    if len(managed_matches) > 1:
        raise ValueError(
            "More than one managed playlist has the requested name."
        )

    playlist = managed_matches[0] if managed_matches else None

    playlist_action = "updated" if playlist else "created"

    if playlist:
        output_playlist_id = playlist["id"]
    else:
        playlist = spotify.create_playlist(
            name=output_playlist_name,
            description=(
                MANAGED_PLAYLIST_DESCRIPTION
            ),
            public=False,
        )

        output_playlist_id = playlist["id"]
        try:
            add_managed_playlist_id(output_playlist_id)
        except Exception as exc:
            result = RoyalShuffleResult(
                source_name=source_playlist["name"],
                source_id=source_playlist_id,
                output_name=output_playlist_name,
                output_id=output_playlist_id,
                total_items=len(items),
                items_written=0,
                skipped_item_count=skipped_item_count,
                action=playlist_action,
            )
            raise RoyalShufflePartialWriteError(result, exc) from exc

    report_status(
        f'Updating {output_playlist_name}...'
    )

    if output_playlist_id == source_playlist_id:
        raise ValueError(
            "The output playlist cannot be the source playlist."
        )

    uris = [
        item["uri"]
        for item in items
    ]

    try:
        spotify.clear_playlist(output_playlist_id)
        items_written = spotify.add_playlist_items(
            output_playlist_id,
            uris,
        )
    except (Exception, KeyboardInterrupt) as exc:
        result = RoyalShuffleResult(
            source_name=source_playlist["name"],
            source_id=source_playlist_id,
            output_name=output_playlist_name,
            output_id=output_playlist_id,
            total_items=len(uris),
            items_written=getattr(exc, "items_written", 0),
            skipped_item_count=skipped_item_count,
            action=playlist_action,
        )
        raise RoyalShufflePartialWriteError(result, exc) from exc

    report_status(
        f'{playlist_action.title()} '
        f'{output_playlist_name} with {len(uris)} items'
    )

    return RoyalShuffleResult(
        source_name=source_playlist["name"],
        source_id=source_playlist_id,
        output_name=output_playlist_name,
        output_id=output_playlist_id,
        total_items=len(uris),
        items_written=items_written,
        skipped_item_count=skipped_item_count,
        action=playlist_action,
    )

def main():
    # Authenticate with Spotify
    access_token = authenticate()
    spotify = SpotifyClient(access_token)

# ---------------------------------------------------------
# Select source playlist
# ---------------------------------------------------------

    print()
    print("Loading your Spotify playlists...")
    print()

    managed_playlist_ids = load_managed_playlist_ids()
    playlists = eligible_source_playlists(
        spotify.get_playlists(),
        managed_playlist_ids,
    )

    if not playlists:
        print()
        print("No eligible source playlists found.")
        return
    
    source_playlist = select_playlist(playlists)

    SOURCE_PLAYLIST_ID = source_playlist["id"]
    OUTPUT_PLAYLIST_NAME = f'{source_playlist["name"]} - RANDOM'

    print()
    print(
        f'Selected "{source_playlist["name"]}"'
    )

    result = royal_shuffle(
        spotify,
        source_playlist,
    )

    print()
    print("SUCCESS.")
    print(
        f'"{result.output_name}" now contains '
        f'{result.items_written} items in true-random order.'
    )

    if result.skipped_item_count:
        print(
            f'{result.skipped_item_count} local Spotify items '
            "were skipped because they cannot be copied."
        )

    print()
    print("Leave Spotify Shuffle OFF when playing it.")

if __name__ == "__main__":
    main()
