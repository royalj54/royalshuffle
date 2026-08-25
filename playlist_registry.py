import json
from pathlib import Path


REGISTRY_FILE = Path.home() / ".royalshuffle_managed_playlists.json"
LEGACY_RECOVERY_FILE = (
    Path.home() / ".royalshuffle_legacy_recovery.json"
)


def load_managed_playlist_ids():
    if not REGISTRY_FILE.exists():
        return set()

    data = json.loads(
        REGISTRY_FILE.read_text(encoding="utf-8")
    )

    if not isinstance(data, dict):
        raise ValueError("Invalid RoyalShuffle playlist registry.")

    playlist_ids = data.get("playlist_ids", [])

    if not isinstance(playlist_ids, list) or not all(
        isinstance(playlist_id, str)
        for playlist_id in playlist_ids
    ):
        raise ValueError("Invalid RoyalShuffle playlist registry.")

    return set(playlist_ids)


def add_managed_playlist_id(playlist_id):
    playlist_ids = load_managed_playlist_ids()

    if playlist_id in playlist_ids:
        return

    playlist_ids.add(playlist_id)
    temporary_file = REGISTRY_FILE.with_suffix(".tmp")
    temporary_file.write_text(
        json.dumps(
            {"playlist_ids": sorted(playlist_ids)},
            indent=2,
        ),
        encoding="utf-8",
    )
    temporary_file.replace(REGISTRY_FILE)


def load_reviewed_legacy_playlist_ids():
    if not LEGACY_RECOVERY_FILE.exists():
        return set()

    data = json.loads(
        LEGACY_RECOVERY_FILE.read_text(encoding="utf-8")
    )

    if not isinstance(data, dict):
        raise ValueError("Invalid RoyalShuffle recovery state.")

    playlist_ids = data.get("reviewed_playlist_ids", [])

    if not isinstance(playlist_ids, list) or not all(
        isinstance(playlist_id, str)
        for playlist_id in playlist_ids
    ):
        raise ValueError("Invalid RoyalShuffle recovery state.")

    return set(playlist_ids)


def add_reviewed_legacy_playlist_id(playlist_id):
    playlist_ids = load_reviewed_legacy_playlist_ids()

    if playlist_id in playlist_ids:
        return

    playlist_ids.add(playlist_id)
    temporary_file = LEGACY_RECOVERY_FILE.with_suffix(".tmp")
    temporary_file.write_text(
        json.dumps(
            {"reviewed_playlist_ids": sorted(playlist_ids)},
            indent=2,
        ),
        encoding="utf-8",
    )
    temporary_file.replace(LEGACY_RECOVERY_FILE)
