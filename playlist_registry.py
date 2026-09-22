import json

from app_paths import legacy_recovery_file, managed_playlists_file


REGISTRY_FILE = managed_playlists_file()
LEGACY_RECOVERY_FILE = legacy_recovery_file()


def _load_registry():
    if not REGISTRY_FILE.exists():
        return {"playlist_ids": []}

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

    bindings = data.get("source_outputs", {})
    if not isinstance(bindings, dict):
        raise ValueError("Invalid RoyalShuffle output bindings.")
    used_outputs = set()
    for source_id, sessions in bindings.items():
        if not isinstance(source_id, str) or not source_id or not isinstance(sessions, dict):
            raise ValueError("Invalid RoyalShuffle output bindings.")
        for session, output_id in sessions.items():
            if (session not in {"30", "60", "90"}
                    or not isinstance(output_id, str) or not output_id
                    or output_id == source_id or output_id not in playlist_ids
                    or output_id in used_outputs):
                raise ValueError("Invalid or conflicting RoyalShuffle output bindings.")
            used_outputs.add(output_id)
    return data


def load_managed_playlist_ids():
    return set(_load_registry().get("playlist_ids", []))


def timed_output_id(source_id, minutes):
    return _load_registry().get("source_outputs", {}).get(source_id, {}).get(str(minutes))


def _save_registry(data):
    REGISTRY_FILE.parent.mkdir(parents=True, exist_ok=True)
    temporary_file = REGISTRY_FILE.with_suffix(".tmp")
    temporary_file.write_text(json.dumps(data, indent=2), encoding="utf-8")
    temporary_file.replace(REGISTRY_FILE)


def register_timed_output(source_id, minutes, output_id):
    data = _load_registry()
    if (not isinstance(source_id, str) or not source_id
            or type(minutes) is not int or minutes not in (30, 60, 90)
            or not isinstance(output_id, str) or not output_id or output_id == source_id):
        raise ValueError("Invalid timed output identity.")
    bindings = data.setdefault("source_outputs", {})
    for bound_source, sessions in bindings.items():
        for session, bound_id in sessions.items():
            if bound_id == output_id and (bound_source, session) != (source_id, str(minutes)):
                raise ValueError("Output already bound to another source/session.")
    sessions = bindings.setdefault(source_id, {})
    if str(minutes) in sessions and sessions[str(minutes)] != output_id:
        raise ValueError("Source/session already has a different output.")
    sessions[str(minutes)] = output_id
    data["playlist_ids"] = sorted(set(data.get("playlist_ids", [])) | {output_id})
    _save_registry(data)


def add_managed_playlist_id(playlist_id):
    data = _load_registry()
    playlist_ids = set(data.get("playlist_ids", []))

    if playlist_id in playlist_ids:
        return

    playlist_ids.add(playlist_id)
    data["playlist_ids"] = sorted(playlist_ids)
    _save_registry(data)


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
    LEGACY_RECOVERY_FILE.parent.mkdir(parents=True, exist_ok=True)
    temporary_file = LEGACY_RECOVERY_FILE.with_suffix(".tmp")
    temporary_file.write_text(
        json.dumps(
            {"reviewed_playlist_ids": sorted(playlist_ids)},
            indent=2,
        ),
        encoding="utf-8",
    )
    temporary_file.replace(LEGACY_RECOVERY_FILE)
