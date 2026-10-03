"""Candidate algorithms for the research simulator.

All history is keyed by track_id.  A playlist may contain several occurrence_id
values for one track; those entries remain separate permutation members while
sharing history and therefore receiving the same weight at a given draw.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
import math
import random
from typing import Iterable, Protocol, Sequence, TYPE_CHECKING

if TYPE_CHECKING:
    from .model import Occurrence


def _bounded(value: float, lower: float, upper: float) -> float:
    if not math.isfinite(value) or lower <= 0 or upper < lower:
        raise ValueError("weights and bounds must be finite, positive, and ordered")
    return min(upper, max(lower, value))


def weighted_permutation(
    occurrences: Sequence[Occurrence], weights: Sequence[float], rng: random.Random
) -> list[Occurrence]:
    """Return a Plackett-Luce weighted permutation using exponential keys."""
    if len(occurrences) != len(weights):
        raise ValueError("occurrences and weights must have equal lengths")
    keyed = []
    for occurrence, weight in zip(occurrences, weights):
        if not math.isfinite(weight) or weight <= 0:
            raise ValueError("every weight must be finite and greater than zero")
        # random() may return zero; 1-random() is in (0, 1].
        key = -math.log1p(-rng.random()) / weight
        keyed.append((key, occurrence.occurrence_id, occurrence))
    keyed.sort(key=lambda item: (item[0], item[1]))
    return [item[2] for item in keyed]


class RotationAlgorithm(Protocol):
    name: str
    oracle: bool

    def weights(self, occurrences: Sequence[Occurrence], operation: int) -> list[float]: ...
    def observe(
        self,
        generated: Sequence[Occurrence],
        actually_heard: Sequence[Occurrence],
        operation: int,
    ) -> None: ...
    def reset(self) -> None: ...
    def parameters(self) -> dict[str, object]: ...
    def perceived_scores(self, track_ids: Iterable[str]) -> dict[str, float]: ...


class TrueRandom:
    name = "true_random"
    oracle = False

    def weights(self, occurrences: Sequence[Occurrence], operation: int) -> list[float]:
        return [1.0] * len(occurrences)

    def observe(self, generated, actually_heard, operation: int) -> None:
        return None

    def reset(self) -> None:
        return None

    def parameters(self) -> dict[str, object]:
        return {}

    def perceived_scores(self, track_ids: Iterable[str]) -> dict[str, float]:
        return {track_id: 0.0 for track_id in track_ids}


@dataclass(frozen=True)
class RecencyParameters:
    tau: float = 3.0
    recent_penalty: float = 0.55
    overdue_boost: float = 0.35
    never_exposed_weight: float = 1.35
    w_min: float = 0.35
    w_max: float = 1.50


class RecencyWeight:
    """Oracle benchmark using the last operation in which a track was heard."""

    name = "candidate_a_recency_oracle"
    oracle = True

    def __init__(self, params: RecencyParameters | None = None):
        self.params = params or RecencyParameters()
        self.last_exposure: dict[str, int] = {}
        self._validate()

    def _validate(self) -> None:
        p = self.params
        if p.tau <= 0 or p.recent_penalty < 0 or p.overdue_boost < 0:
            raise ValueError("recency parameters must be nonnegative and tau positive")
        _bounded(p.never_exposed_weight, p.w_min, p.w_max)

    def weights(self, occurrences: Sequence[Occurrence], operation: int) -> list[float]:
        p = self.params
        result = []
        for occurrence in occurrences:
            last = self.last_exposure.get(occurrence.track_id)
            if last is None:
                value = p.never_exposed_weight
            else:
                age = max(0, operation - last)
                recent = math.exp(-age / p.tau)
                value = 1.0 - p.recent_penalty * recent + p.overdue_boost * (1.0 - recent)
            result.append(_bounded(value, p.w_min, p.w_max))
        return result

    def observe(self, generated, actually_heard, operation: int) -> None:
        for occurrence in actually_heard:
            self.last_exposure[occurrence.track_id] = operation

    def reset(self) -> None:
        self.last_exposure.clear()

    def parameters(self) -> dict[str, object]:
        return asdict(self.params)

    def perceived_scores(self, track_ids: Iterable[str]) -> dict[str, float]:
        return {track_id: float(self.last_exposure.get(track_id, -1)) for track_id in track_ids}


@dataclass(frozen=True)
class ExposureScoreParameters:
    decay: float = 0.85
    exposure_increment: float = 1.0
    penalty: float = 0.45
    w_min: float = 0.35
    w_max: float = 2.00


class DecayingExposureScore:
    """Oracle benchmark whose score is updated from actual synthetic listening."""

    name = "candidate_b_exposure_oracle"
    oracle = True

    def __init__(self, params: ExposureScoreParameters | None = None):
        self.params = params or ExposureScoreParameters()
        self.scores: dict[str, float] = {}
        self._validate()

    def _validate(self) -> None:
        p = self.params
        if not 0 <= p.decay <= 1 or p.exposure_increment < 0 or p.penalty < 0:
            raise ValueError("invalid exposure-score parameters")
        _bounded(1.0, p.w_min, p.w_max)

    def weights(self, occurrences: Sequence[Occurrence], operation: int) -> list[float]:
        p = self.params
        values = [self.scores.get(item.track_id, 0.0) for item in occurrences]
        mean = sum(values) / len(values) if values else 0.0
        return [_bounded(math.exp(-p.penalty * (value - mean)), p.w_min, p.w_max) for value in values]

    def observe(self, generated, actually_heard, operation: int) -> None:
        p = self.params
        self.scores = {key: value * p.decay for key, value in self.scores.items()}
        for occurrence in actually_heard:
            self.scores[occurrence.track_id] = (
                self.scores.get(occurrence.track_id, 0.0) + p.exposure_increment
            )

    def reset(self) -> None:
        self.scores.clear()

    def parameters(self) -> dict[str, object]:
        return asdict(self.params)

    def perceived_scores(self, track_ids: Iterable[str]) -> dict[str, float]:
        return {track_id: self.scores.get(track_id, 0.0) for track_id in track_ids}


@dataclass(frozen=True)
class OpportunityParameters:
    decay: float = 0.85
    position_decay: float = 3.0
    penalty: float = 0.45
    w_min: float = 0.35
    w_max: float = 2.00


class OpportunityBalance:
    """Local-information candidate updated only from generated positions."""

    name = "candidate_c_opportunity"
    oracle = False

    def __init__(self, params: OpportunityParameters | None = None):
        self.params = params or OpportunityParameters()
        self.scores: dict[str, float] = {}
        self._validate()

    def _validate(self) -> None:
        p = self.params
        if not 0 <= p.decay <= 1 or p.position_decay < 0 or p.penalty < 0:
            raise ValueError("invalid opportunity parameters")
        _bounded(1.0, p.w_min, p.w_max)

    def weights(self, occurrences: Sequence[Occurrence], operation: int) -> list[float]:
        p = self.params
        values = [self.scores.get(item.track_id, 0.0) for item in occurrences]
        mean = sum(values) / len(values) if values else 0.0
        return [_bounded(math.exp(-p.penalty * (value - mean)), p.w_min, p.w_max) for value in values]

    def observe(self, generated, actually_heard, operation: int) -> None:
        p = self.params
        self.scores = {key: value * p.decay for key, value in self.scores.items()}
        denominator = max(1, len(generated) - 1)
        for position, occurrence in enumerate(generated):
            opportunity = math.exp(-p.position_decay * position / denominator)
            self.scores[occurrence.track_id] = self.scores.get(occurrence.track_id, 0.0) + opportunity

    def reset(self) -> None:
        self.scores.clear()

    def parameters(self) -> dict[str, object]:
        return asdict(self.params)

    def perceived_scores(self, track_ids: Iterable[str]) -> dict[str, float]:
        return {track_id: self.scores.get(track_id, 0.0) for track_id in track_ids}


ALGORITHMS = {
    "true_random": TrueRandom,
    "candidate_a": RecencyWeight,
    "candidate_b": DecayingExposureScore,
    "candidate_c": OpportunityBalance,
}
