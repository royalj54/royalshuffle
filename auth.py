import base64
import hashlib
import os
import secrets
import urllib.parse
import webbrowser
import requests
import subprocess
import threading
import json
from pathlib import Path
from http.server import BaseHTTPRequestHandler, HTTPServer

TOKEN_FILE = Path.home() / ".royalshuffle_token.json"
CLIENT_ID = os.environ["SPOTIFY_CLIENT_ID"]

REDIRECT_URI = "http://127.0.0.1:8888/callback"

SCOPES = " ".join([
    "playlist-read-private",
    "playlist-modify-private",
    "playlist-modify-public",
])

def save_token_data(token_data):
    TOKEN_FILE.write_text(
        json.dumps(token_data, indent=2)
    )

class SpotifyCallbackHandler(BaseHTTPRequestHandler):
    callback_url = None

    def do_GET(self):
        SpotifyCallbackHandler.callback_url = (
            f"http://{self.headers['Host']}{self.path}"
        )

        self.send_response(200)
        self.send_header("Content-Type", "text/html")
        self.end_headers()

        self.wfile.write(
            b"""
            <html>
                <body>
                    <h2>RoyalShuffle connected to Spotify.</h2>
                    <p>You can close this browser window.</p>
                </body>
            </html>
            """
        )

    def log_message(self, format, *args):
        return

def wait_for_spotify_callback():
    SpotifyCallbackHandler.callback_url = None

    server = HTTPServer(
        ("127.0.0.1", 8888),
        SpotifyCallbackHandler,
    )

    server.handle_request()
    server.server_close()

    return SpotifyCallbackHandler.callback_url
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

    subprocess.run(
        [
            "/mnt/c/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
            auth_url
        ],
        check=False,
    )

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

    return token_data


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