"""Optional first-credited-artist sequencing, never membership selection."""

from collections import deque
import random


class ArtistSeparationError(ValueError):
    pass


def separate_artists(items, rng=None):
    """Return the same occurrence references in a separated linear order.

    Preserve the input order within each artist. Validate the entire supplied
    membership before sequencing; callers must pass the selected session only.
    """
    buckets = {}
    for item in items:
        artist = item.get("primary_artist_id")
        if not isinstance(artist, str) or not artist.strip():
            raise ArtistSeparationError(
                "Artist Separation cannot be applied: selected track metadata "
                "lacks a usable first-credited Spotify artist ID. Disable Artist "
                "Separation to use ordinary True Random."
            )
        buckets.setdefault(artist, deque()).append(item)
    if not buckets:
        return []
    if rng is None:
        rng = random.SystemRandom()
    order = _artist_order({artist: len(bucket) for artist, bucket in buckets.items()}, rng)
    return [buckets[artist].popleft() for artist in order]


def _artist_order(counts, rng):
    total = sum(counts.values())
    if not total:
        return []
    dominant = max(counts, key=counts.get)
    largest = counts[dominant]
    others = total - largest
    if largest > others + 1:
        # All O+1 gaps must contain the dominant artist to attain M-O-1
        # collisions. Balanced blocks attain the lower bound ceil(M/(O+1))
        # on the longest run. Recursion spaces the separator artists too.
        separators = _artist_order(
            {artist: count for artist, count in counts.items() if artist != dominant}, rng
        )
        size, extra = divmod(largest, others + 1)
        blocks = [size + 1] * extra + [size] * (others + 1 - extra)
        rng.shuffle(blocks)
        order = []
        for index, block in enumerate(blocks):
            order.extend([dominant] * block)
            if index < others:
                order.append(separators[index])
        return order

    original = counts.copy()
    last_position = {}
    previous = None
    order = []
    for position in range(total):
        remaining = total - position - 1
        # Top two counts let each candidate's suffix guard run in O(1).
        top_artist = max(counts, key=counts.get)
        top_count = counts[top_artist]
        second = max((count for artist, count in counts.items() if artist != top_artist), default=0)
        scores = {}
        for artist, count in counts.items():
            if artist == previous:
                continue
            other_max = second if artist == top_artist else top_count
            # A suffix following A needs count(A) <= floor(R/2); every
            # other artist needs count <= ceil(R/2). These are sufficient
            # as well as necessary, so no legal choice strands an artist.
            if count - 1 > remaining // 2 or other_max > (remaining + 1) // 2:
                continue
            # Gap relative to this artist's ideal average gap, capped at 1.
            scores[artist] = min(total, (position - last_position[artist]) * original[artist]) \
                if artist in last_position else total
        best = max(scores.values())
        # Randomize comparable gaps (within 1/3 of the normalized score),
        # allowing alternatives to rigid ABCABC cycles. This is a heuristic,
        # not a minimum-gap rule or a globally optimal spacing claim.
        choices = [artist for artist, score in scores.items() if 3 * (best - score) <= total]
        chosen = rng.choice(choices)
        order.append(chosen)
        last_position[chosen] = position
        counts[chosen] -= 1
        if not counts[chosen]:
            del counts[chosen]
        previous = chosen
    return order
