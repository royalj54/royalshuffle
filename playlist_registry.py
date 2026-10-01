import json
from functools import wraps
from writer_lock import writer_lock

from app_paths import legacy_recovery_file, managed_playlists_file


REGISTRY_FILE = managed_playlists_file()
LEGACY_RECOVERY_FILE = legacy_recovery_file()


def registry_writer(function):
    @wraps(function)
    def coordinated(*args, **kwargs):
        with writer_lock(REGISTRY_FILE.parent):
            return function(*args, **kwargs)
    return coordinated


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
            if (not isinstance(session, str) or not session.isascii()
                    or not session.isdecimal() or session.startswith("0")
                    or not isinstance(output_id, str) or not output_id
                    or output_id == source_id or output_id not in playlist_ids
                    or output_id in used_outputs):
                raise ValueError("Invalid or conflicting RoyalShuffle output bindings.")
            used_outputs.add(output_id)
    full_outputs = data.get("full_outputs", {})
    if not isinstance(full_outputs, dict):
        raise ValueError("Invalid RoyalShuffle Full Playlist bindings.")
    for source_id, output_id in full_outputs.items():
        if (not isinstance(source_id, str) or not source_id
                or not isinstance(output_id, str) or not output_id
                or output_id == source_id or output_id not in playlist_ids
                or output_id in used_outputs):
            raise ValueError("Invalid or conflicting RoyalShuffle Full Playlist bindings.")
        used_outputs.add(output_id)
    # Preserve legacy Opportunity data without treating it as an output binding.
    return data


def load_managed_playlist_ids():
    data = _load_registry()
    ids = set(data.get("playlist_ids", []))
    legacy = data.get("opportunity_outputs", {})
    if isinstance(legacy, dict):
        ids.difference_update(value for value in legacy.values() if isinstance(value, str))
    # Ordinary bindings remain managed even if legacy data mentions the same ID.
    ids.update(data.get("full_outputs", {}).values())
    for sessions in data.get("source_outputs", {}).values():
        ids.update(sessions.values())
    return ids


def load_legacy_opportunity_playlist_ids():
    legacy = _load_registry().get("opportunity_outputs", {})
    return {value for value in legacy.values() if isinstance(value, str)} if isinstance(legacy, dict) else set()


def timed_output_id(source_id, minutes):
    if type(minutes) is not int or minutes <= 0:
        raise ValueError("Choose a positive whole number of minutes.")
    return _load_registry().get("source_outputs", {}).get(source_id, {}).get(str(minutes))


def _save_registry(data):
    REGISTRY_FILE.parent.mkdir(parents=True, exist_ok=True)
    temporary_file = REGISTRY_FILE.with_suffix(".tmp")
    temporary_file.write_text(json.dumps(data, indent=2), encoding="utf-8")
    temporary_file.replace(REGISTRY_FILE)


@registry_writer
def register_timed_output(source_id, minutes, output_id):
    data = _load_registry()
    if (not isinstance(source_id, str) or not source_id
            or type(minutes) is not int or minutes <= 0
            or not isinstance(output_id, str) or not output_id or output_id == source_id):
        raise ValueError("Invalid timed output identity.")
    if output_id in data.get("full_outputs", {}).values():
        raise ValueError("Output already bound as a Full Playlist output.")
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


def full_output_id(source_id):
    return _load_registry().get("full_outputs", {}).get(source_id)


@registry_writer
def register_full_output(source_id, output_id):
    data = _load_registry()
    if (not isinstance(source_id, str) or not source_id
            or not isinstance(output_id, str) or not output_id or output_id == source_id):
        raise ValueError("Invalid Full Playlist output identity.")
    bindings = data.setdefault("full_outputs", {})
    if any(bound_id == output_id and bound_source != source_id
           for bound_source, bound_id in bindings.items()):
        raise ValueError("Output already bound to another Full Playlist source.")
    if any(output_id in sessions.values() for sessions in data.get("source_outputs", {}).values()):
        raise ValueError("Output already bound to a timed session.")
    if source_id in bindings and bindings[source_id] != output_id:
        raise ValueError("Source already has a different Full Playlist output.")
    bindings[source_id] = output_id
    data["playlist_ids"] = sorted(set(data.get("playlist_ids", [])) | {output_id})
    _save_registry(data)


@registry_writer
def remove_full_output(source_id, expected_output_id):
    """Remove only the Full Playlist association that was checked."""
    data = _load_registry()
    bindings = data.get("full_outputs", {})
    if bindings.get(source_id) != expected_output_id or source_id not in bindings:
        raise ValueError("Managed Full Playlist binding changed; recovery stopped.")
    del bindings[source_id]
    if (expected_output_id not in bindings.values()
            and not any(expected_output_id in sessions.values()
                        for sessions in data.get("source_outputs", {}).values())):
        data["playlist_ids"] = [playlist_id for playlist_id in data.get("playlist_ids", [])
                                if playlist_id != expected_output_id]
    _save_registry(data)


@registry_writer
def add_managed_playlist_id(playlist_id):
    data = _load_registry()
    playlist_ids = set(data.get("playlist_ids", []))

    if playlist_id in playlist_ids:
        return

    playlist_ids.add(playlist_id)
    data["playlist_ids"] = sorted(playlist_ids)
    _save_registry(data)


@registry_writer
def remove_timed_output(source_id, minutes, expected_output_id):
    """Remove only the association that was checked, preserving other state."""
    if type(minutes) is not int or minutes <= 0:
        raise ValueError("Choose a positive whole number of minutes.")
    data = _load_registry()
    bindings = data.get("source_outputs", {})
    sessions = bindings.get(source_id, {})
    if sessions.get(str(minutes)) != expected_output_id:
        raise ValueError("Managed output binding changed; recovery stopped.")
    del sessions[str(minutes)]
    if not sessions:
        del bindings[source_id]
    if (not any(expected_output_id in other.values() for other in bindings.values())
            and expected_output_id not in data.get("full_outputs", {}).values()):
        data["playlist_ids"] = [playlist_id for playlist_id in data.get("playlist_ids", [])
                                if playlist_id != expected_output_id]
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
