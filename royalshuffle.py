from auth import authenticate
from playlist_selector import select_playlist
from shuffle_engine import shuffle_items
from spotify_client import SpotifyClient


# ---------------------------------------------------------
# Authenticate with Spotify
# ---------------------------------------------------------

access_token = authenticate()
spotify = SpotifyClient(access_token)

# ---------------------------------------------------------
# Select source playlist
# ---------------------------------------------------------

print()
print("Loading your Spotify playlists...")
print()

playlists = spotify.get_playlists()
source_playlist = select_playlist(playlists)

SOURCE_PLAYLIST_ID = source_playlist["id"]
OUTPUT_PLAYLIST_NAME = f'{source_playlist["name"]} - RANDOM'

print()
print(
    f'Selected "{source_playlist["name"]}"'
)

# ---------------------------------------------------------
# Fetch every item from the source playlist
# ---------------------------------------------------------

print()
print("Reading source playlist...")

items = spotify.get_playlist_items(SOURCE_PLAYLIST_ID)

print()
print(f"Fetched {len(items)} playlist items.")


# ---------------------------------------------------------
# True-random shuffle
# ---------------------------------------------------------

items = shuffle_items(items)

print()
print("First 20 tracks in the randomized order:")
print()

for number, item in enumerate(items[:20], start=1):
    print(
        f"{number:2}. "
        f"{item['artists']} - {item['name']}"
    )

# ---------------------------------------------------------
# Find or create output playlist
# ---------------------------------------------------------

print()
print(f'Looking for "{OUTPUT_PLAYLIST_NAME}"...')

playlist = spotify.find_playlist_by_name(OUTPUT_PLAYLIST_NAME)

if playlist:
    output_playlist_id = playlist["id"]

    print(
        f'Found existing "{OUTPUT_PLAYLIST_NAME}" '
        f'with ID {output_playlist_id}'
    )

else:
    print(f'Creating "{OUTPUT_PLAYLIST_NAME}"...')

    playlist = spotify.create_playlist(
        name=OUTPUT_PLAYLIST_NAME,
        description=(
            "True-randomized copy generated "
            "by dmiles-randomizer"
        ),
        public=False,
    )

    output_playlist_id = playlist["id"]

    print(
        f'Created "{OUTPUT_PLAYLIST_NAME}" '
        f'with ID {output_playlist_id}'
    )


# ---------------------------------------------------------
# Clear existing contents of output playlist
# ---------------------------------------------------------

print()
print("Clearing existing randomized playlist...")

spotify.clear_playlist(output_playlist_id)

# ---------------------------------------------------------
# Add randomized items
# ---------------------------------------------------------

uris = [
    item["uri"]
    for item in items
]

print()
print(f"Writing {len(uris)} randomized items...")

spotify.add_playlist_items(
    output_playlist_id,
    uris
)


# ---------------------------------------------------------
# Finished
# ---------------------------------------------------------

print()
print("SUCCESS.")
print(
    f'"{OUTPUT_PLAYLIST_NAME}" now contains '
    f'{len(uris)} items in true-random order.'
)
print()
print("Leave Spotify Shuffle OFF when playing it.")
