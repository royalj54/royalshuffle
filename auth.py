import base64
import hashlib
import os
import secrets
import urllib.parse
import webbrowser
import requests
import threading
import json
import tempfile
from datetime import datetime
from pathlib import Path
from http.server import BaseHTTPRequestHandler, HTTPServer

from app_paths import ensure_diagnostics_folder, token_file

TOKEN_FILE = token_file()
LEGACY_LOG_FILE = Path.home() / ".royalshuffle_debug.log"
_active_debug_log_file = None


def _is_windows():
    return os.name == "nt"


def _get_debug_log_file():
    global _active_debug_log_file

    if _active_debug_log_file is None:
        diagnostics_directory = ensure_diagnostics_folder()
        if diagnostics_directory is None:
            _active_debug_log_file = LEGACY_LOG_FILE
        else:
            _active_debug_log_file = (
                diagnostics_directory / "royalshuffle_debug.log"
            )

    return _active_debug_log_file

def log_debug(message):
    global _active_debug_log_file

    timestamp = datetime.now().astimezone().isoformat(timespec="seconds")
    log_line = f"{timestamp} {message}\n"

    try:
        with _get_debug_log_file().open("a", encoding="utf-8") as log:
            log.write(log_line)
    except OSError:
        _active_debug_log_file = LEGACY_LOG_FILE
        try:
            with LEGACY_LOG_FILE.open("a", encoding="utf-8") as log:
                log.write(log_line)
        except OSError:
            return

CLIENT_ID = os.environ.get(
    "SPOTIFY_CLIENT_ID",
    "0c1a1fa51a574e98a0cf0e62c44d0717",
)

REDIRECT_URI = "http://127.0.0.1:8888/callback"

SCOPES = " ".join([
    "playlist-read-private",
    "playlist-modify-private",
    "playlist-modify-public",
])


class SpotifyAuthenticationError(RuntimeError):
    pass

def save_token_data(token_data):
    log_debug(f"Saving token data to {TOKEN_FILE}")
    TOKEN_FILE.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = None
    try:
        with tempfile.NamedTemporaryFile(
            "w",
            encoding="utf-8",
            dir=TOKEN_FILE.parent,
            prefix=f".{TOKEN_FILE.name}.",
            suffix=".tmp",
            delete=False,
        ) as temporary_file:
            temporary_path = Path(temporary_file.name)
            json.dump(token_data, temporary_file, indent=2)
            temporary_file.write("\n")
            temporary_file.flush()
            os.fsync(temporary_file.fileno())

        if not _is_windows():
            os.chmod(temporary_path, 0o600)

        os.replace(temporary_path, TOKEN_FILE)
    finally:
        if temporary_path is not None and temporary_path.exists():
            temporary_path.unlink()

    log_debug("Token data saved successfully")

class SpotifyCallbackHandler(BaseHTTPRequestHandler):
    callback_url = None

    def do_GET(self):
        SpotifyCallbackHandler.callback_url = (
            f"http://{self.headers['Host']}{self.path}"
        )

        parsed = urllib.parse.urlparse(self.path)
        query = urllib.parse.parse_qs(parsed.query)

        if "error" in query:
            error = query["error"][0]
            log_debug(f"Spotify callback returned OAuth error: {error}")
            heading = "Spotify authorization was not completed."
            detail = f"Spotify returned: {error}"
        else:
            log_debug("Spotify callback received authorization response")
            heading = "Spotify authorization received."
            detail = "RoyalShuffle is completing the connection."

        html = f"""
            <html>
                <body>
                    <h2>{heading}</h2>
                    <p>{detail}</p>
                    <p>You can close this browser window.</p>
                </body>
            </html>
        """

        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        self.wfile.write(html.encode("utf-8"))

    def log_message(self, format, *args):
        return

def wait_for_spotify_callback():
    log_debug("Starting localhost Spotify callback listener on 127.0.0.1:8888")
    SpotifyCallbackHandler.callback_url = None

    server = HTTPServer(
        ("127.0.0.1", 8888),
        SpotifyCallbackHandler,
    )

    server.handle_request()
    server.server_close()

    log_debug("Localhost Spotify callback listener completed")
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


def create_authentication_session():
    log_debug("Starting Spotify PKCE authentication")
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

    return {
        "auth_url": auth_url,
        "code_verifier": code_verifier,
        "state": state,
    }


def open_authentication_browser(auth_url):
    browser_opened = webbrowser.open(auth_url)
    log_debug(f"Spotify authorization URL sent to browser; webbrowser result={browser_opened}")
    return browser_opened


def start_authentication():
    auth_session = create_authentication_session()
    open_authentication_browser(auth_session["auth_url"])

    return auth_session


def finish_authentication(callback_url, code_verifier, state):
    log_debug("Finishing Spotify authentication")
    parsed = urllib.parse.urlparse(callback_url)
    query = urllib.parse.parse_qs(parsed.query)

    if "error" in query:
        error = query["error"][0]
        log_debug(f"Spotify authorization failed before token exchange: {error}")
        raise SpotifyAuthenticationError(error)

    returned_state = query.get("state", [None])[0]

    if returned_state != state:
        log_debug("Spotify callback state mismatch")
        raise SpotifyAuthenticationError("State mismatch. Aborting.")

    log_debug("Spotify callback state validated")

    code = query.get("code", [None])[0]
    if not code:
        raise SpotifyAuthenticationError(
            "Spotify callback did not contain an authorization code."
        )

    log_debug("Requesting Spotify access token")

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

    log_debug(f"Spotify token endpoint returned HTTP {token_response.status_code}")
    token_response.raise_for_status()

    token_data = token_response.json()
    log_debug("Spotify token exchange succeeded")

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

    token_data = finish_authentication(
        callback_url,
        auth_session["code_verifier"],
        auth_session["state"],
    )

    return token_data["access_token"]

def load_token_data():
    if not TOKEN_FILE.exists():
        log_debug("No saved Spotify token file found")
        return None

    log_debug(f"Loading saved Spotify token data from {TOKEN_FILE}")
    try:
        return json.loads(
            TOKEN_FILE.read_text(encoding="utf-8")
        )
    except (OSError, json.JSONDecodeError):
        log_debug("Saved Spotify token data could not be read")
        return None


def saved_token_file_exists():
    return TOKEN_FILE.is_file()


def refresh_access_token(refresh_token):
    log_debug("Refreshing Spotify access token")
    token_response = requests.post(
        "https://accounts.spotify.com/api/token",
        data={
            "grant_type": "refresh_token",
            "refresh_token": refresh_token,
            "client_id": CLIENT_ID,
        },
    )

    log_debug(f"Spotify refresh endpoint returned HTTP {token_response.status_code}")
    token_response.raise_for_status()

    token_data = token_response.json()
    log_debug("Spotify access token refresh succeeded")

    return token_data

def clear_token_data():
    if TOKEN_FILE.exists():
        log_debug(f"Removing saved Spotify token data from {TOKEN_FILE}")
        TOKEN_FILE.unlink()
