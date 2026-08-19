from auth import authenticate
from playlist_selector import select_playlist
from shuffle_engine import shuffle_items
from spotify_client import SpotifyClient

def royal_shuffle(spotify, source_playlist):
    source_playlist_id = source_playlist["id"]
    output_playlist_name = f'{source_playlist["name"]} - RANDOM'

    items = spotify.get_playlist_items(source_playlist_id)

    items = shuffle_items(items)

    playlist = spotify.find_playlist_by_name(
        output_playlist_name
    )

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

    spotify.clear_playlist(output_playlist_id)

    uris = [
        item["uri"]
        for item in items
    ]

    spotify.add_playlist_items(
        output_playlist_id,
        uris,
    )

    return {
        "name": output_playlist_name,
        "id": output_playlist_id,
        "item_count": len(uris),
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

    playlists = [
        playlist
        for playlist in spotify.get_playlists()
        if not playlist["name"].endswith(" - RANDOM")
    ]

    if not playlists:
        print()
        print("No eligible source playlists found.")
        return
    
    source_playlist = select_playlist(playlists)

    result = royal_shuffle(
        spotify,
        source_playlist,
    )

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