import base64
import hashlib
import os
import secrets
import urllib.parse
import webbrowser

import requests

from shuffle_engine import shuffle_items
from spotify_client import SpotifyClient

# ---------------------------------------------------------
# Configuration
# ---------------------------------------------------------

CLIENT_ID = os.environ["SPOTIFY_CLIENT_ID"]

REDIRECT_URI = "http://127.0.0.1:8888/callback"

SCOPES = " ".join([
    "playlist-read-private",
    "playlist-modify-private",
    "playlist-modify-public",
])

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

code_verifier = create_code_verifier()
code_challenge = create_code_challenge(code_verifier)
state = secrets.token_urlsafe(16)

params = {
    "client_id": CLIENT_ID,
    "response_type": "code",
    "redirect_uri": REDIRECT_URI,
    "scope": SCOPES,
    "code_challenge_method": "S256",
    "code_challenge": code_challenge,
    "state": state,
}

auth_url = (
    "https://accounts.spotify.com/authorize?"
    + urllib.parse.urlencode(params)
)

print()
print("Open this URL in your browser:")
print()
print(auth_url)
print()

webbrowser.open(auth_url)

callback_url = input(
    "After Spotify redirects you, paste the FULL callback URL here:\n> "
)

parsed = urllib.parse.urlparse(callback_url)
query = urllib.parse.parse_qs(parsed.query)

if "error" in query:
    raise RuntimeError(query["error"][0])

returned_state = query.get("state", [None])[0]

if returned_state != state:
    raise RuntimeError("State mismatch. Aborting.")

code = query["code"][0]

token_response = requests.post(
    "https://accounts.spotify.com/api/token",
    data={
        "grant_type": "authorization_code",
        "code": code,
        "redirect_uri": REDIRECT_URI,
        "client_id": CLIENT_ID,
        "code_verifier": code_verifier,
    },
)

token_response.raise_for_status()

token_data = token_response.json()
access_token = token_data["access_token"]
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
