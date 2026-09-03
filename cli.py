import argparse
import os
import platform
import sys
import threading

import requests

from app_metadata import APP_VERSION
from app_paths import (
    config_folder,
    data_folder,
    diagnostics_folder,
    state_folder,
    token_file,
)
from auth import (
    create_authentication_session,
    finish_authentication,
    SpotifyAuthenticationError,
    open_authentication_browser,
    save_token_data,
    wait_for_spotify_callback,
    log_debug,
)
from playlist_service import (
    AmbiguousPlaylistSourceError,
    ManagedPlaylistSourceError,
    PlaylistSourceNotFoundError,
    eligible_source_playlists,
    resolve_source_playlist,
)
from playlist_registry import load_managed_playlist_ids
from royalshuffle import RoyalShufflePartialWriteError, royal_shuffle
from session_service import (
    InvalidSavedSessionError,
    NoSavedSessionError,
    restore_spotify_client,
)
from spotify_client import (
    SpotifyQuotaExceededError,
    SpotifyRetryLaterError,
)


EXIT_SUCCESS = 0
EXIT_USAGE = 2
EXIT_AUTH = 3
EXIT_API = 4
EXIT_RETRY_LATER = 5
EXIT_LOCAL_STATE = 6
EXIT_NO_ELIGIBLE_PLAYLISTS = 7
EXIT_PARTIAL = 8


def build_parser():
    parser = argparse.ArgumentParser(
        prog="royalshuffle",
        description="Transparent, user-controlled Spotify shuffle",
    )
    parser.add_argument(
        "--version",
        action="version",
        version=f"RoyalShuffle {APP_VERSION}",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("diagnostics", help="Show redacted runtime diagnostics")
    subparsers.add_parser("auth", help="Authenticate with Spotify")
    subparsers.add_parser("playlists", help="List eligible source playlists")
    shuffle_parser = subparsers.add_parser(
        "shuffle",
        help="Create or update a true-random managed playlist",
    )
    shuffle_parser.add_argument(
        "playlist",
        help="Playlist ID, URI, URL, or unambiguous exact name",
    )
    return parser


def _is_interactive():
    return sys.stdin.isatty() and sys.stdout.isatty()


def show_diagnostics(output):
    values = [
        ("RoyalShuffle version", APP_VERSION),
        ("Python version", platform.python_version()),
        ("Platform", platform.platform()),
        ("Config directory", config_folder()),
        ("State directory", state_folder()),
        ("Data directory", data_folder()),
        ("Diagnostics directory", diagnostics_folder()),
        ("Token path", token_file()),
        ("Saved session exists", "yes" if token_file().is_file() else "no"),
        ("Interactive terminal", "yes" if _is_interactive() else "no"),
        (
            "Display available",
            "yes" if os.environ.get("DISPLAY") or os.environ.get("WAYLAND_DISPLAY") else "no",
        ),
    ]
    for label, value in values:
        print(f"{label}: {value}", file=output)


def authenticate_interactively(output, input_stream):
    if not _is_interactive():
        raise NoSavedSessionError(
            "Spotify authentication requires an interactive terminal."
        )

    auth_session = create_authentication_session()
    callback_state = {"url": None, "error": None}

    def listen_for_callback():
        try:
            callback_state["url"] = wait_for_spotify_callback()
        except Exception as exc:
            callback_state["error"] = exc

    listener = threading.Thread(target=listen_for_callback, daemon=True)
    listener.start()

    print("Open this Spotify authorization URL:", file=output)
    print(auth_session["auth_url"], file=output)
    browser_opened = False
    try:
        browser_opened = open_authentication_browser(auth_session["auth_url"])
    except Exception as exc:
        log_debug(
            "CLI browser launch failed; "
            f"exception_type={type(exc).__name__}"
        )
    if not browser_opened:
        print(
            "A browser could not be opened. Open the printed URL manually.",
            file=output,
        )
    print(
        "Paste the complete callback URL, or press Enter to wait for the localhost callback:",
        file=output,
    )
    callback_url = input_stream.readline().strip()

    if not callback_url:
        listener.join()
        if callback_state["error"] is not None:
            raise NoSavedSessionError(
                "The localhost callback listener failed. Run auth again and paste the callback URL."
            ) from callback_state["error"]
        callback_url = callback_state["url"]

    token_data = finish_authentication(
        callback_url,
        auth_session["code_verifier"],
        auth_session["state"],
    )
    save_token_data(token_data)
    print("Spotify authentication saved successfully.", file=output)


def list_playlists(output):
    spotify = restore_spotify_client()
    playlists = eligible_source_playlists(spotify.get_playlists())
    if not playlists:
        return EXIT_NO_ELIGIBLE_PLAYLISTS

    for playlist in playlists:
        print(f'{playlist["name"]}\t{playlist["id"]}', file=output)
    return EXIT_SUCCESS


def shuffle_playlist(reference, output):
    spotify = restore_spotify_client()
    playlists = spotify.get_playlists()
    managed_playlist_ids = load_managed_playlist_ids()
    source = resolve_source_playlist(
        playlists,
        reference,
        managed_playlist_ids,
    )
    result = royal_shuffle(spotify, source)
    print("Royal Shuffle complete.", file=output)
    print(f"Source: {result.source_name} ({result.source_id})", file=output)
    print(f"Output: {result.output_name}", file=output)
    print(
        f"Tracks written: {result.items_written}/{result.total_items}",
        file=output,
    )
    print(f"Output playlist ID: {result.output_id}", file=output)
    return EXIT_SUCCESS


def _report_partial_shuffle(exc):
    result = exc.result
    print("royalshuffle: Royal Shuffle did not complete.", file=sys.stderr)
    print(f"Output playlist ID: {result.output_id}", file=sys.stderr)
    print(
        f"Tracks written: {result.items_written}/{result.total_items}",
        file=sys.stderr,
    )
    cause = exc.cause
    if isinstance(cause, KeyboardInterrupt):
        print("Failure: interrupted", file=sys.stderr)
        return 130
    if isinstance(cause, SpotifyQuotaExceededError):
        print(f"Failure: developer quota exhausted: {cause}", file=sys.stderr)
        return EXIT_PARTIAL
    if isinstance(cause, SpotifyRetryLaterError):
        print(f"Failure: Spotify retry deferred: {cause}", file=sys.stderr)
        return EXIT_PARTIAL
    if isinstance(cause, (requests.ConnectionError, requests.Timeout)):
        print(f"Failure: network failure: {cause}", file=sys.stderr)
        return EXIT_PARTIAL
    if isinstance(cause, requests.HTTPError):
        print(f"Failure: Spotify API failure: {cause}", file=sys.stderr)
        return EXIT_PARTIAL
    if isinstance(cause, (OSError, ValueError)):
        print(f"Failure: local state failure: {cause}", file=sys.stderr)
        return EXIT_PARTIAL
    print(f"Failure: {cause}", file=sys.stderr)
    return EXIT_PARTIAL


def _report_error(message, error):
    print(f"royalshuffle: {message}: {error}", file=sys.stderr)


def main(argv=None):
    parser = build_parser()
    args = parser.parse_args(argv)

    try:
        if args.command == "diagnostics":
            show_diagnostics(sys.stdout)
            return EXIT_SUCCESS
        if args.command == "auth":
            authenticate_interactively(sys.stdout, sys.stdin)
            return EXIT_SUCCESS
        if args.command == "playlists":
            result = list_playlists(sys.stdout)
            if result == EXIT_NO_ELIGIBLE_PLAYLISTS:
                print("No eligible source playlists found.", file=sys.stderr)
            return result
        if args.command == "shuffle":
            return shuffle_playlist(args.playlist, sys.stdout)
    except RoyalShufflePartialWriteError as exc:
        return _report_partial_shuffle(exc)
    except (PlaylistSourceNotFoundError, AmbiguousPlaylistSourceError) as exc:
        _report_error("playlist selection failed", exc)
        return EXIT_USAGE
    except ManagedPlaylistSourceError as exc:
        _report_error("managed playlist cannot be a source", exc)
        return EXIT_USAGE
    except (SpotifyQuotaExceededError, SpotifyRetryLaterError) as exc:
        _report_error("Spotify request deferred", exc)
        return EXIT_RETRY_LATER
    except (NoSavedSessionError, SpotifyAuthenticationError) as exc:
        _report_error("authentication failed", exc)
        return EXIT_AUTH
    except InvalidSavedSessionError as exc:
        _report_error("invalid saved session", exc)
        return EXIT_LOCAL_STATE
    except (requests.ConnectionError, requests.Timeout) as exc:
        _report_error("network failure", exc)
        return EXIT_API
    except requests.HTTPError as exc:
        status_code = getattr(getattr(exc, "response", None), "status_code", None)
        if status_code in (400, 401, 403):
            _report_error("authentication failed", exc)
            return EXIT_AUTH
        _report_error("Spotify API failure", exc)
        return EXIT_API
    except (OSError, ValueError, KeyError, TypeError) as exc:
        _report_error("local state failure", exc)
        return EXIT_LOCAL_STATE
    except KeyboardInterrupt:
        print("royalshuffle: interrupted", file=sys.stderr)
        return 130
    except Exception as exc:
        log_debug(
            "CLI internal failure; "
            f"exception_type={type(exc).__name__}"
        )
        print("royalshuffle: internal failure", file=sys.stderr)
        return EXIT_LOCAL_STATE

    return EXIT_USAGE


if __name__ == "__main__":
    raise SystemExit(main())
