from auth import authenticate, log_debug
from playlist_selector import select_playlist
from playlist_registry import (
    add_managed_playlist_id,
    load_managed_playlist_ids,
)
from shuffle_engine import shuffle_items
from spotify_client import SpotifyClient

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
                "True-randomized copy generated "
                "by RoyalShuffle"
            ),
            public=False,
        )

        output_playlist_id = playlist["id"]
        add_managed_playlist_id(output_playlist_id)

    report_status(
        f'Updating {output_playlist_name}...'
    )

    if output_playlist_id == source_playlist_id:
        raise ValueError(
            "The output playlist cannot be the source playlist."
        )

    spotify.clear_playlist(output_playlist_id)
    
    uris = [
        item["uri"]
        for item in items
    ]

    spotify.add_playlist_items(
        output_playlist_id,
        uris,
    )

    report_status(
        f'{playlist_action.title()} '
        f'{output_playlist_name} with {len(uris)} items'
    )

    return {
        "name": output_playlist_name,
        "id": output_playlist_id,
        "item_count": len(uris),
        "action": playlist_action,
    }

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
    playlists = [
        playlist
        for playlist in spotify.get_playlists()
        if playlist["id"] not in managed_playlist_ids
    ]

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
        f'"{result["name"]}" now contains '
        f'{result["item_count"]} items in true-random order.'
    )

    print()
    print("Leave Spotify Shuffle OFF when playing it.")

if __name__ == "__main__":
    main()
