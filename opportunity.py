"""Balanced Opportunity: unique-track, fixed-60-minute deals without replacement."""
from copy import deepcopy
from dataclasses import dataclass
import hashlib
import json
import random

from artist_separation import separate_artists
from royalshuffle import apply_session_length, _validate_artist_separation

TARGET_MS = 60 * 60 * 1000


class OpportunityError(ValueError):
    """Invalid pool, rotation, or prepared deal."""


def valid_uri(uri):
    return (isinstance(uri, str) and uri.startswith("spotify:track:")
            and bool(uri.removeprefix("spotify:track:"))
            and uri.removeprefix("spotify:track:").isascii()
            and uri.removeprefix("spotify:track:").isalnum())


def canonical_record(item):
    uri = item.get("uri")
    duration = item.get("duration_ms")
    artist = item.get("primary_artist_id")
    if not valid_uri(uri):
        raise OpportunityError("Opportunity requires a Spotify track URI.")
    if type(duration) is not int or duration <= 0:
        raise OpportunityError("Opportunity requires positive integer durations.")
    if artist is not None and (not isinstance(artist, str) or not artist.strip()):
        raise OpportunityError("Invalid primary artist ID.")
    record = {"duration_ms": duration, "primary_artist_id": artist}
    if "name" in item:
        if not isinstance(item["name"], str):
            raise OpportunityError("Invalid display name.")
        record["name"] = item["name"]
    return uri, record


def construct_pool(eligible_items):
    """Accept already filtered source items; retain the first exact URI record."""
    pool = {}
    for item in eligible_items:
        if not isinstance(item, dict):
            raise OpportunityError("Invalid eligible track record.")
        uri = item.get("uri")
        if isinstance(uri, str) and uri in pool:
            continue
        uri, record = canonical_record(item)
        pool[uri] = record
    if not pool:
        raise OpportunityError("No eligible Opportunity tracks.")
    return pool


def fingerprint(active):
    return hashlib.sha256(json.dumps(active, sort_keys=True,
                                     separators=(",", ":")).encode()).hexdigest()


@dataclass(frozen=True)
class Track:
    uri: str
    duration_ms: int
    primary_artist_id: str | None
    name: str | None = None

    def item(self):
        result = {"uri": self.uri, "duration_ms": self.duration_ms,
                  "primary_artist_id": self.primary_artist_id}
        if self.name is not None:
            result["name"] = self.name
        return result


@dataclass(frozen=True)
class PreparedDeal:
    source_id: str
    rotation_id: str
    snapshot: str
    tracks: tuple[Track, ...]
    membership_digest: str

    @property
    def duration_ms(self):
        return sum(track.duration_ms for track in self.tracks)


def membership_digest(tracks):
    return hashlib.sha256(json.dumps([track.item() for track in tracks],
                                     sort_keys=True).encode()).hexdigest()


def prepare_deal(source_id, active, *, artist_separation=False, rng=None):
    """Plan only. No pool depletion, persistence, or Spotify operations."""
    if not active["undealt"]:
        raise OpportunityError("Rotation is exhausted; begin a fresh rotation.")
    rng = rng if rng is not None else random.SystemRandom()
    items = [{"uri": uri, **record} for uri, record in active["undealt"].items()]
    rng.shuffle(items)
    selected, _ = apply_session_length(items, TARGET_MS)
    if artist_separation:
        originals = [(item, deepcopy(item)) for item in selected]
        selected = separate_artists(selected, rng=rng)
        _validate_artist_separation(originals, selected)
    tracks = tuple(Track(item["uri"], item["duration_ms"],
                         item["primary_artist_id"], item.get("name"))
                   for item in selected)
    return PreparedDeal(source_id, active["rotation_id"], fingerprint(active), tracks,
                        membership_digest(tracks))


def progress(active):
    return {"completed_sessions": active["completed_sessions"],
            "original_unique_count": active["original_unique_count"],
            "remaining_unique_count": len(active["undealt"]),
            "complete": not active["undealt"],
            "remaining_duration_ms": sum(r["duration_ms"]
                                         for r in active["undealt"].values())}
