import base64
import hashlib
import os
import secrets
import urllib.parse
import webbrowser

import requests


CLIENT_ID = os.environ["SPOTIFY_CLIENT_ID"]

REDIRECT_URI = "http://127.0.0.1:8888/callback"

SCOPES = " ".join([
    "playlist-read-private",
    "playlist-modify-private",
    "playlist-modify-public",
])


def create_code_verifier():
    return secrets.token_urlsafe(64)


def create_code_challenge(verifier):
    digest = hashlib.sha256(verifier.encode()).digest()

    return (
        base64.urlsafe_b64encode(digest)
        .decode()
        .rstrip("=")
    )


def start_authentication():
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

    webbrowser.open(auth_url)

    return {
        "auth_url": auth_url,
        "code_verifier": code_verifier,
        "state": state,
    }


def finish_authentication(callback_url, code_verifier, state):
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

    return token_data["access_token"]


def authenticate():
    auth_session = start_authentication()

    print()
    print("Open this URL in your browser:")
    print()
    print(auth_session["auth_url"])
    print()

    callback_url = input(
        "After Spotify redirects you, paste the FULL callback URL here:\n> "
    )

    return finish_authentication(
        callback_url,
        auth_session["code_verifier"],
        auth_session["state"],
    )