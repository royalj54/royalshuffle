import requests

from auth import authenticate
from shuffle_engine import shuffle_items
from spotify_client import SpotifyClient

# ---------------------------------------------------------
# Configuration
# ---------------------------------------------------------

SOURCE_PLAYLIST_ID = "5kIThQnpUJva1XTUvujOfs"

OUTPUT_PLAYLIST_NAME = "Kpop - RANDOM"


# ---------------------------------------------------------
# PKCE helpers
# ---------------------------------------------------------

def create_code_verifier():
    return secrets.token_urlsafe(64)


def create_code_challenge(verifier):
    digest = hashlib.sha256(verifier.encode()).digest()

    return (
        base64.urlsafe_b64encode(digest)
        .decode()
        .rstrip("=")
    )


# ---------------------------------------------------------
# Authenticate with Spotify
# ---------------------------------------------------------

access_token = authenticate()
spotify = SpotifyClient(access_token)

headers = {
    "Authorization": f"Bearer {access_token}"
}


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
# Create output playlist if it does not exist
# ---------------------------------------------------------

if not output_playlist_id:
    print(f'Creating "{OUTPUT_PLAYLIST_NAME}"...')

    response = requests.post(
        "https://api.spotify.com/v1/me/playlists",
        headers={
            **headers,
            "Content-Type": "application/json"
        },
        json={
            "name": OUTPUT_PLAYLIST_NAME,
            "public": False,
            "description": (
                "True-randomized copy generated "
                "by dmiles-randomizer"
            ),
        },
    )

    response.raise_for_status()

    playlist = response.json()

    output_playlist_id = playlist["id"]

    print(
        f'Created "{OUTPUT_PLAYLIST_NAME}" '
        f'with ID {output_playlist_id}'
    )

else:
    print(
        f'Found existing "{OUTPUT_PLAYLIST_NAME}" '
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
