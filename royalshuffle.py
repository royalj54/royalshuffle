from collections import Counter
from copy import deepcopy
from dataclasses import dataclass
from requests import HTTPError

from artist_separation import ArtistSeparationError, separate_artists
from auth import authenticate, log_debug
from app_metadata import MANAGED_PLAYLIST_DESCRIPTION
from playlist_selector import select_playlist
from playlist_service import eligible_source_playlists
from playlist_registry import (
    add_managed_playlist_id,
    load_managed_playlist_ids,
    timed_output_id,
    register_timed_output,
    remove_timed_output,
)
from shuffle_engine import shuffle_items
from spotify_client import SpotifyClient, get_unsupported_item_count


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
    requested_duration_ms: int | None = None
    duration_ms: int | None = None
    source_shorter_than_target: bool = False
    unsupported_item_count: int = 0


class SessionLengthError(ValueError):
    pass


def _validate_artist_separation(selected_occurrences, separated):
    """Check the sequencer independently against references and pre-call metadata."""
    selected_count = len(selected_occurrences)
    artist_counts = Counter(
        metadata.get("primary_artist_id") for _, metadata in selected_occurrences
    )
    expected = max(0, 2 * max(artist_counts.values(), default=0) - selected_count - 1)
    actual = sum(
        left.get("primary_artist_id") == right.get("primary_artist_id")
        for left, right in zip(separated, separated[1:])
    )
    originals = {id(item): metadata for item, metadata in selected_occurrences}
    preserved = (
        Counter(id(item) for item, _ in selected_occurrences)
        == Counter(id(item) for item in separated)
        and all(item == originals[id(item)] for item in separated)
    )
    failures = []
    if not preserved:
        failures.append("exact occurrence preservation")
    if actual != expected:
        failures.append("minimum same-primary-artist adjacency")
    if failures:
        message = (
            f"Artist Separation invariant failed: {', '.join(failures)}; "
            f"selected_count={selected_count}; separated_count={len(separated)}; "
            f"expected_minimum_adjacency={expected}; actual_adjacency={actual}. "
            "No output playlist or registry changes were made."
        )
        log_debug(message)
        raise ArtistSeparationError(message)


def apply_session_length(items, target_ms):
    """Consume a prefix of an already randomized sequence; never choose by duration."""
    if not items:
        raise SessionLengthError("No eligible tracks. Full Playlist remains available.")
    if any(type(item.get("duration_ms")) is not int or item["duration_ms"] <= 0
           for item in items):
        raise SessionLengthError(
            "Timed sessions require a valid positive duration for every eligible track. "
            "Full Playlist remains available."
        )
    total = 0
    for index, item in enumerate(items):
        total += item["duration_ms"]
        if total >= target_ms:
            return items[:index + 1], total
    return items[:], total


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
    session_minutes=None,
    artist_separation=False,
):
    def report_status(message):
        if status_callback:
            status_callback(message)

    source_playlist_id = source_playlist["id"]
    if session_minutes is not None and (
        type(session_minutes) is not int or session_minutes not in (30, 60, 90)
    ):
        raise SessionLengthError("Choose Full Playlist, 30M, 60M, or 90M.")
    target_ms = session_minutes * 60_000 if session_minutes is not None else None
    duration_ms = None
    if target_ms is not None:
        output_playlist_name = f'{source_playlist["name"]} - RANDOM {session_minutes}M'
    if output_playlist_name is None:
        output_playlist_name = f'{source_playlist["name"]} - RANDOM'

    report_status("Reading source playlist...")

    items = spotify.get_playlist_items(source_playlist_id)
    unsupported_item_count = get_unsupported_item_count(items)
    if unsupported_item_count:
        report_status(f"Skipped {unsupported_item_count} unsupported playlist item(s).")
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
    if not items:
        message = (
            "No eligible tracks remain. Add supported, non-local music tracks "
            "or choose another source playlist. "
            f"Skipped {unsupported_item_count} unsupported and {skipped_item_count} local item(s). "
            "Output playlists and registry were not changed."
        )
        report_status(message)
        raise SessionLengthError(message)
    
    report_status(
        f'Shuffling {len(items)} items...'
    )

    items = shuffle_items(items)
    if target_ms is not None:
        items, duration_ms = apply_session_length(items, target_ms)
    if artist_separation:
        # Retain references and metadata before calling the sequencer, including
        # if a faulty implementation mutates its input list or occurrence data.
        selected_occurrences = [(item, deepcopy(item)) for item in items]
        items = separate_artists(items)
        _validate_artist_separation(selected_occurrences, items)
    duration_result = dict(
        unsupported_item_count=unsupported_item_count,
        requested_duration_ms=target_ms,
        duration_ms=duration_ms,
        source_shorter_than_target=target_ms is not None and duration_ms < target_ms,
    )

    report_status(
        f'Preparing {output_playlist_name}...'
    )
    
    if target_ms is not None:
        bound_id = timed_output_id(source_playlist_id, session_minutes)
        playlist = None
        if bound_id is not None:
            if bound_id == source_playlist_id:
                log_debug("Managed output equals source playlist; recovery aborted; registry unchanged")
                raise SessionLengthError(
                    "The registered output is the source playlist. Recovery stopped; "
                    "the source and registry were not changed."
                )
            log_debug(f"Resolving timed managed output; playlist_id={bound_id}; session_minutes={session_minutes}")
            try:
                playlist = next((candidate for candidate in spotify.get_playlists()
                                 if candidate["id"] == bound_id), None)
            except Exception as exc:
                log_debug(
                    f"Managed output resolution failed; playlist_id={bound_id}; "
                    f"exception_type={type(exc).__name__}; registry unchanged"
                )
                raise
            if playlist is None:
                log_debug(
                    f"Managed output absent from playlist listing; playlist_id={bound_id}; "
                    "checking registered ID directly; registry unchanged"
                )
                try:
                    playlist = spotify.get_playlist(bound_id)
                except Exception as exc:
                    status = getattr(getattr(exc, "response", None), "status_code", None)
                    if not isinstance(exc, HTTPError) or status != 404:
                        log_debug(
                            f"Managed output direct lookup failed; playlist_id={bound_id}; "
                            f"http_status={status}; exception_type={type(exc).__name__}; registry unchanged"
                        )
                        raise
                    log_debug(
                        f"Managed output binding unusable; playlist_id={bound_id}; direct_lookup_http_status=404"
                    )
                    try:
                        remove_timed_output(source_playlist_id, session_minutes, bound_id)
                    except Exception as removal_error:
                        log_debug(
                            f"Stale binding removal failed; playlist_id={bound_id}; "
                            f"exception_type={type(removal_error).__name__}; replacement not created"
                        )
                        raise
                    log_debug(f"Stale binding removed; playlist_id={bound_id}; preparing replacement")
                else:
                    if (not isinstance(playlist, dict) or playlist.get("id") != bound_id
                            or not isinstance(playlist.get("name"), str)):
                        raise SessionLengthError(
                            "Managed output lookup returned an invalid identity or name; "
                            "recovery stopped and registry unchanged."
                        )
            if playlist is not None:
                log_debug(f"Reusing timed managed output; playlist_id={bound_id}")
                output_playlist_name = playlist["name"]
    else:
        matching_playlists = spotify.find_playlists_by_name(output_playlist_name)
        managed_playlist_ids = load_managed_playlist_ids()
        managed_matches = [playlist for playlist in matching_playlists
                           if playlist["id"] in managed_playlist_ids]
        if len(managed_matches) > 1:
            raise ValueError("More than one managed playlist has the requested name.")
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
            if target_ms is None:
                add_managed_playlist_id(output_playlist_id)
            else:
                register_timed_output(source_playlist_id, session_minutes, output_playlist_id)
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
                **duration_result,
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
            **duration_result,
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
        **duration_result,
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
    if result.unsupported_item_count:
        print(f"Skipped {result.unsupported_item_count} unsupported playlist item(s).")

    print()
    print("Leave Spotify Shuffle OFF when playing it.")

if __name__ == "__main__":
    main()
