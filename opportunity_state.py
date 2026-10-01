"""Versioned shrinking rotations and durable exact pending delivery plans."""
from copy import deepcopy
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import tempfile
import uuid

from writer_lock import state_writer
from app_paths import opportunity_state_file
from opportunity import (OpportunityError, PreparedDeal, Track, canonical_record,
                         construct_pool, fingerprint, membership_digest, prepare_deal, progress, valid_uri)

SCHEMA_VERSION = 2


class OpportunityStateError(OpportunityError):
    """State cannot safely be loaded, changed, or persisted."""


def _require(condition, message):
    if not condition:
        raise OpportunityStateError(message)


def _identifier(value):
    return isinstance(value, str) and bool(value) and value.isascii() and value.isalnum()


def _validate_active(active):
    fields = {"rotation_id", "started_at", "original_unique_count",
              "completed_sessions", "undealt"}
    _require(isinstance(active, dict) and set(active) == fields, "Invalid active rotation.")
    try:
        _require(str(uuid.UUID(active["rotation_id"])) == active["rotation_id"],
                 "Invalid rotation ID.")
        started = datetime.fromisoformat(active["started_at"])
        _require(started.tzinfo is not None, "Rotation timestamp requires timezone.")
    except (ValueError, TypeError, AttributeError) as exc:
        raise OpportunityStateError("Invalid rotation ID or timestamp.") from exc
    original, sessions = active["original_unique_count"], active["completed_sessions"]
    _require(type(original) is int and original > 0, "Invalid original count.")
    _require(type(sessions) is int and sessions >= 0, "Invalid completed session count.")
    pool = active["undealt"]
    _require(isinstance(pool, dict), "Invalid undealt mapping.")
    remaining = len(pool)
    depleted = original - remaining
    _require(0 <= remaining <= original and
             ((sessions == 0 and depleted == 0) or
              (0 < sessions <= depleted)), "Impossible rotation progress.")
    for uri, record in pool.items():
        _require(isinstance(record, dict) and
                 {"duration_ms", "primary_artist_id"} <= set(record) <=
                 {"duration_ms", "primary_artist_id", "name"}, "Invalid undealt record.")
        try:
            canonical_record({"uri": uri, **record})
        except OpportunityError as exc:
            raise OpportunityStateError(str(exc)) from exc


def _uuid(value):
    try:
        return isinstance(value, str) and str(uuid.UUID(value)) == value
    except (ValueError, TypeError, AttributeError):
        return False


def _validate_pending(source_id, active, pending):
    fields = {"operation_id", "action", "expected_rotation_id", "expected_sessions",
              "expected_snapshot", "uris", "output_id", "phase", "candidate",
              "creation_name", "stale_output_id", "delivery"}
    _require(isinstance(pending, dict) and set(pending) == fields, "Malformed pending operation.")
    _require(_uuid(pending["operation_id"]), "Invalid pending operation ID.")
    _require(pending["action"] in ("next_session", "new_rotation"), "Invalid pending action.")
    expected_id = active["rotation_id"] if active else None
    expected_sessions = active["completed_sessions"] if active else 0
    _require(pending["expected_rotation_id"] == expected_id and
             type(pending["expected_sessions"]) is int and
             pending["expected_sessions"] == expected_sessions and
             pending["expected_snapshot"] == (fingerprint(active) if active else None),
             "Pending operation does not match active rotation.")
    if pending["action"] == "new_rotation":
        candidate = pending["candidate"]
        _validate_active(candidate)
        _require(candidate["completed_sessions"] == 0 and
                 candidate["rotation_id"] != expected_id, "Invalid candidate rotation.")
        selected_from = candidate
    else:
        _require(active is not None and pending["candidate"] is None,
                 "Next session requires an active rotation.")
        selected_from = active
    uris = pending["uris"]
    _require(isinstance(uris, list) and bool(uris) and all(valid_uri(uri) for uri in uris)
             and len(set(uris)) == len(uris) and
             set(uris) <= set(selected_from["undealt"]), "Invalid pending membership.")
    for key in ("output_id", "stale_output_id"):
        output = pending[key]
        _require(output is None or (_identifier(output) and output != source_id),
                 "Invalid pending output identity.")
    if pending["delivery"] is not None:
        from delivery_receipts import validate_delivery
        try:
            validate_delivery(pending["delivery"], pending["operation_id"], pending["output_id"], uris)
        except ValueError as exc:
            raise OpportunityStateError(str(exc)) from exc
    name = pending["creation_name"]
    _require(name is None or (isinstance(name, str) and bool(name.strip()) and name == name.strip()),
             "Invalid pending creation name.")
    phase = pending["phase"]
    _require(phase in ("staged", "creating", "output_known", "populating", "delivered"),
             "Invalid pending phase.")
    if phase == "creating":
        _require(pending["output_id"] is None and name is not None, "Invalid creation phase.")
    if phase in ("output_known", "populating", "delivered"):
        _require(pending["output_id"] is not None, "Pending phase requires output ID.")
    if pending["output_id"] is None:
        _require(name is not None, "Output creation requires accepted naming.")


def validate_state(state):
    _require(isinstance(state, dict) and set(state) == {"schema_version", "sources"},
             "Invalid Opportunity top-level structure.")
    _require(type(state["schema_version"]) is int and state["schema_version"] == SCHEMA_VERSION,
             "Unsupported Opportunity schema version.")
    _require(isinstance(state["sources"], dict), "Invalid Opportunity sources.")
    for source_id, source in state["sources"].items():
        _require(_identifier(source_id), "Invalid source playlist ID.")
        _require(isinstance(source, dict) and set(source) == {"active", "pending"},
                 "Invalid source state.")
        active, pending = source["active"], source["pending"]
        if active is not None:
            _validate_active(active)
        if pending is not None:
            _validate_pending(source_id, active, pending)
        else:
            _require(active is not None, "Source has neither active nor pending rotation.")
    return state


def candidate_rotation(eligible_items):
    pool = construct_pool(eligible_items)
    return {"rotation_id": str(uuid.uuid4()),
            "started_at": datetime.now(timezone.utc).isoformat(),
            "original_unique_count": len(pool), "completed_sessions": 0, "undealt": pool}


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        _require(key not in result, "Duplicate JSON key in Opportunity state.")
        result[key] = value
    return result


class OpportunityStore:
    """Atomic rotation store coordinated with registry and delivery writers."""

    def __init__(self, path=None):
        self.path = Path(path) if path is not None else opportunity_state_file()

    def load(self):
        try:
            with self.path.open(encoding="utf-8") as stream:
                state = json.load(stream, object_pairs_hook=_unique_object)
        except FileNotFoundError:
            return {"schema_version": SCHEMA_VERSION, "sources": {}}
        except (OSError, ValueError) as exc:
            raise OpportunityStateError("Cannot load Opportunity state.") from exc
        if isinstance(state, dict) and type(state.get("schema_version")) is int and state["schema_version"] == 1:
            # In-memory migration only; no historical acknowledgments are invented.
            _require(isinstance(state.get("sources"), dict), "Invalid legacy Opportunity sources.")
            for source in state["sources"].values():
                if isinstance(source, dict) and isinstance(source.get("pending"), dict):
                    _require("delivery" not in source["pending"], "Legacy state cannot contain delivery receipts.")
                    source["pending"]["delivery"] = None
            state["schema_version"] = SCHEMA_VERSION
        return validate_state(state)

    def _save(self, state):
        validate_state(state)
        temporary = None
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=self.path.parent,
                                            prefix=f".{self.path.name}.", suffix=".tmp",
                                            delete=False) as stream:
                temporary = Path(stream.name)
                json.dump(state, stream, indent=2)
                stream.write("\n")
                stream.flush()
                os.fsync(stream.fileno())
            if os.name != "nt":
                os.chmod(temporary, 0o600)
            os.replace(temporary, self.path)
        except OSError as exc:
            raise OpportunityStateError("Cannot persist Opportunity state.") from exc
        finally:
            if temporary is not None:
                try:
                    temporary.unlink(missing_ok=True)
                except OSError:
                    pass

    @state_writer
    def begin_rotation(self, source_id, eligible_items):
        _require(_identifier(source_id), "Invalid source playlist ID.")
        state = self.load()
        previous = state["sources"].get(source_id)
        _require(previous is None or previous["pending"] is None, "Resume the pending session first.")
        _require(previous is None or not previous["active"]["undealt"],
                 "An unexhausted rotation already exists.")
        active = candidate_rotation(eligible_items)
        state["sources"][source_id] = {"active": active, "pending": None}
        self._save(state)
        return deepcopy(active)

    def _active(self, state, source_id):
        _require(_identifier(source_id) and source_id in state["sources"], "No saved rotation for source.")
        _require(state["sources"][source_id]["active"] is not None, "No active rotation yet.")
        return state["sources"][source_id]["active"]

    def prepare_next_deal(self, source_id, *, artist_separation=False, rng=None):
        state = self.load()
        _require(state["sources"].get(source_id, {}).get("pending") is None, "Resume the pending session first.")
        active = self._active(state, source_id)
        return prepare_deal(source_id, active, artist_separation=artist_separation, rng=rng)

    @state_writer
    def commit_deal(self, deal):
        _require(isinstance(deal, PreparedDeal), "Expected an exact prepared deal.")
        state = self.load()
        _require(state["sources"].get(deal.source_id, {}).get("pending") is None, "Resume the pending session first.")
        active = self._active(state, deal.source_id)
        _require(deal.rotation_id == active["rotation_id"] and
                 deal.snapshot == fingerprint(active), "Prepared deal is stale.")
        _require(isinstance(deal.tracks, tuple) and bool(deal.tracks) and
                 all(isinstance(track, Track) for track in deal.tracks),
                 "Expected nonempty prepared track records.")
        _require(deal.membership_digest == membership_digest(deal.tracks),
                 "Prepared deal was altered.")
        uris = [track.uri for track in deal.tracks]
        _require(len(set(uris)) == len(uris), "Duplicate prepared URI.")
        for track in deal.tracks:
            uri, record = canonical_record(track.item())
            _require(active["undealt"].get(uri) == record, "Prepared membership changed.")
        # Artist Separation may reorder the selected prefix, so membership, not
        # final order, is the commit identity.
        for uri in uris:
            del active["undealt"][uri]
        active["completed_sessions"] += 1
        self._save(state)
        return progress(active)

    def inspect_progress(self, source_id):
        return progress(self._active(self.load(), source_id))

    @state_writer
    def stage_pending(self, deal, *, candidate=None, output_id=None,
                      creation_name=None, stale_output_id=None, replace_pending_operation_id=None):
        state = self.load()
        previous = state["sources"].get(deal.source_id)
        old_pending = previous["pending"] if previous else None
        if replace_pending_operation_id is not None:
            _require(candidate is not None and old_pending is not None
                     and old_pending["operation_id"] == replace_pending_operation_id,
                     "Pending operation changed; confirm abandonment again.")
        else:
            _require(old_pending is None, "Resume the pending session first.")
        active = previous["active"] if previous else None
        selected_from = candidate if candidate is not None else active
        _require(selected_from is not None and deal.rotation_id == selected_from["rotation_id"]
                 and deal.snapshot == fingerprint(selected_from)
                 and deal.membership_digest == membership_digest(deal.tracks),
                 "Prepared deal is stale or altered.")
        for track in deal.tracks:
            uri, record = canonical_record(track.item())
            _require(selected_from["undealt"].get(uri) == record, "Prepared membership changed.")
        pending = {"operation_id": str(uuid.uuid4()),
                   "action": "new_rotation" if candidate is not None else "next_session",
                   "expected_rotation_id": active["rotation_id"] if active else None,
                   "expected_sessions": active["completed_sessions"] if active else 0,
                   "expected_snapshot": fingerprint(active) if active else None,
                   "uris": [track.uri for track in deal.tracks],
                   "output_id": output_id, "phase": "staged", "candidate": deepcopy(candidate),
                   "creation_name": creation_name, "stale_output_id": stale_output_id, "delivery": None}
        state["sources"][deal.source_id] = {"active": active, "pending": pending}
        self._save(state)
        return deepcopy(pending)

    def pending(self, source_id):
        return deepcopy(self.load()["sources"].get(source_id, {}).get("pending"))

    @state_writer
    def update_pending(self, source_id, operation_id, **changes):
        _require(set(changes) <= {"output_id", "creation_name", "stale_output_id", "phase", "delivery"},
                 "Cannot change pending membership.")
        state = self.load()
        pending = state["sources"][source_id]["pending"]
        _require(pending is not None and pending["operation_id"] == operation_id,
                 "Pending operation changed.")
        pending.update(changes)
        self._save(state)
        return deepcopy(pending)

    @state_writer
    def finalize_pending(self, source_id, operation_id):
        state = self.load()
        source = state["sources"][source_id]
        pending = source["pending"]
        _require(pending is not None and pending["operation_id"] == operation_id
                 and pending["phase"] == "delivered", "Pending delivery is not confirmed.")
        if pending["delivery"] is not None:
            from delivery_receipts import validate_delivery
            _require(validate_delivery(pending["delivery"], operation_id, pending["output_id"], pending["uris"]),
                     "Delivery acknowledgment coverage is incomplete.")
        active = deepcopy(pending["candidate"] if pending["action"] == "new_rotation"
                          else source["active"])
        for uri in pending["uris"]:
            del active["undealt"][uri]
        active["completed_sessions"] += 1
        source.update(active=active, pending=None)
        self._save(state)
        return progress(active)
