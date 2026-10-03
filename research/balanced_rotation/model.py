"""Simulation model, including duplicate-preserving occurrence identity."""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
import random
from typing import Sequence

from .algorithms import RotationAlgorithm, weighted_permutation
from .metrics import (
    coefficient_of_variation,
    distribution,
    effective_choice_ratio,
    first_threshold,
    gini,
    normalized_entropy,
    pearson_correlation,
)


@dataclass(frozen=True)
class Occurrence:
    occurrence_id: str
    track_id: str


@dataclass(frozen=True)
class ConsumptionModel:
    kind: str
    value: float

    @classmethod
    def parse(cls, value: str | int) -> ConsumptionModel:
        text = str(value).strip()
        if text.endswith("%"):
            percentage = float(text[:-1])
            if not 0 <= percentage <= 100:
                raise ValueError("percentage consumption must be between 0% and 100%")
            return cls("proportion", percentage / 100)
        count = int(text)
        if count < 0:
            raise ValueError("fixed consumption must be nonnegative")
        return cls("fixed", float(count))

    def depth(self, playlist_size: int) -> int:
        if playlist_size < 0:
            raise ValueError("playlist size cannot be negative")
        if self.kind == "fixed":
            requested = int(self.value)
        elif self.kind == "proportion":
            requested = int(playlist_size * self.value)
        else:
            raise ValueError(f"unknown consumption kind: {self.kind}")
        return min(playlist_size, max(0, requested))

    def label(self) -> str:
        return str(int(self.value)) if self.kind == "fixed" else f"{self.value:.0%}"


@dataclass(frozen=True)
class MutationEvent:
    operation: int
    add: tuple[Occurrence, ...] = ()
    remove_occurrence_ids: tuple[str, ...] = ()


@dataclass(frozen=True)
class SimulationConfig:
    seed: int
    rotations: int
    consumption: ConsumptionModel
    reset_after: int | None = None
    mutations: tuple[MutationEvent, ...] = ()


@dataclass
class SimulationResult:
    metadata: dict[str, object]
    metrics: dict[str, object]
    coverage_curve_occurrences: list[float]
    coverage_curve_tracks: list[float]
    actual_exposure_by_occurrence: dict[str, int]
    actual_exposure_by_track: dict[str, int]
    perceived_score_by_track: dict[str, float]
    warnings: list[str] = field(default_factory=list)

    def as_dict(self) -> dict[str, object]:
        return asdict(self)

    def as_compact_dict(self) -> dict[str, object]:
        """Return comparison/reproduction data without per-track or per-operation arrays."""
        return {
            "metadata": self.metadata,
            "metrics": self.metrics,
            "warnings": self.warnings,
        }


def synthetic_playlist(size: int) -> list[Occurrence]:
    if size < 0:
        raise ValueError("playlist size cannot be negative")
    return [Occurrence(f"occ-{index:05d}", f"track-{index:05d}") for index in range(size)]


def simulate(
    occurrences: Sequence[Occurrence],
    algorithm: RotationAlgorithm,
    config: SimulationConfig,
) -> SimulationResult:
    if config.rotations < 0:
        raise ValueError("rotations cannot be negative")
    active = list(occurrences)
    if len({item.occurrence_id for item in active}) != len(active):
        raise ValueError("occurrence_id values must be unique")
    rng = random.Random(config.seed)
    exposure_occurrence = {item.occurrence_id: 0 for item in active}
    exposure_track = {item.track_id: 0 for item in active}
    ever_occurrence: set[str] = set()
    ever_track: set[str] = set()
    coverage_occurrence: list[float] = []
    coverage_track: list[float] = []
    adjacent_track_overlaps: list[float] = []
    adjacent_occurrence_overlaps: list[float] = []
    adjacent_overlap_ratios: list[float] = []
    short_window_3: list[float] = []
    short_window_5: list[float] = []
    recent_track_sets: list[set[str]] = []
    previous_tracks: set[str] = set()
    previous_occurrences: set[str] = set()
    entropies: list[float] = []
    effective_ratios: list[float] = []
    immediate_repeat_events = 0
    consumed_events = 0
    reset_count = 0
    mutations_by_operation = {event.operation: event for event in config.mutations}

    for operation in range(config.rotations):
        if config.reset_after is not None and operation == config.reset_after:
            algorithm.reset()
            reset_count += 1
        event = mutations_by_operation.get(operation)
        if event:
            removals = set(event.remove_occurrence_ids)
            active = [item for item in active if item.occurrence_id not in removals]
            known_ids = {item.occurrence_id for item in active}
            for item in event.add:
                if item.occurrence_id in known_ids:
                    raise ValueError("mutation introduced a duplicate occurrence_id")
                active.append(item)
                known_ids.add(item.occurrence_id)
                exposure_occurrence[item.occurrence_id] = 0
                exposure_track.setdefault(item.track_id, 0)

        weights = algorithm.weights(active, operation)
        entropies.append(normalized_entropy(weights))
        effective_ratios.append(effective_choice_ratio(weights))
        generated = weighted_permutation(active, weights, rng)
        heard = generated[: config.consumption.depth(len(generated))]
        heard_occurrences = {item.occurrence_id for item in heard}
        heard_tracks = {item.track_id for item in heard}
        if operation > 0:
            adjacent_track_overlaps.append(
                len(heard_tracks & previous_tracks) / max(1, len(heard_tracks))
            )
            adjacent_occurrence_overlaps.append(
                len(heard_occurrences & previous_occurrences) / max(1, len(heard_occurrences))
            )
            immediate_repeat_events += len(heard_tracks & previous_tracks)
            consumed_events += len(heard_tracks)
            expected_overlap_fraction = len(previous_tracks) / max(
                1, len({item.track_id for item in active})
            )
            if expected_overlap_fraction:
                adjacent_overlap_ratios.append(
                    (len(heard_tracks & previous_tracks) / max(1, len(heard_tracks)))
                    / expected_overlap_fraction
                )
        for window, target in ((3, short_window_3), (5, short_window_5)):
            recent_union = set().union(*recent_track_sets[-window:]) if recent_track_sets else set()
            if recent_union:
                target.append(len(heard_tracks & recent_union) / max(1, len(heard_tracks)))
        recent_track_sets.append(heard_tracks)
        previous_tracks, previous_occurrences = heard_tracks, heard_occurrences
        for item in heard:
            exposure_occurrence[item.occurrence_id] += 1
            exposure_track[item.track_id] = exposure_track.get(item.track_id, 0) + 1
            ever_occurrence.add(item.occurrence_id)
            ever_track.add(item.track_id)
        algorithm.observe(generated, heard, operation)

        active_occurrence_ids = {item.occurrence_id for item in active}
        active_track_ids = {item.track_id for item in active}
        coverage_occurrence.append(len(ever_occurrence & active_occurrence_ids) / max(1, len(active_occurrence_ids)))
        coverage_track.append(len(ever_track & active_track_ids) / max(1, len(active_track_ids)))

    active_occurrence_ids = [item.occurrence_id for item in active]
    active_track_ids = sorted({item.track_id for item in active})
    occurrence_values = [exposure_occurrence[item] for item in active_occurrence_ids]
    track_values = [exposure_track[item] for item in active_track_ids]
    perceived = algorithm.perceived_scores(active_track_ids)
    correlation = pearson_correlation(
        [perceived[item] for item in active_track_ids],
        [float(exposure_track[item]) for item in active_track_ids],
    )
    zero_occurrence = sum(value == 0 for value in occurrence_values)
    zero_track = sum(value == 0 for value in track_values)
    mean_adjacent_track = sum(adjacent_track_overlaps) / len(adjacent_track_overlaps) if adjacent_track_overlaps else 0.0
    mean_adjacent_occurrence = sum(adjacent_occurrence_overlaps) / len(adjacent_occurrence_overlaps) if adjacent_occurrence_overlaps else 0.0
    mean_adjacent_ratio = sum(adjacent_overlap_ratios) / len(adjacent_overlap_ratios) if adjacent_overlap_ratios else 0.0
    mean_window_3 = sum(short_window_3) / len(short_window_3) if short_window_3 else 0.0
    mean_window_5 = sum(short_window_5) / len(short_window_5) if short_window_5 else 0.0
    mean_entropy = sum(entropies) / len(entropies) if entropies else 1.0
    mean_effective = sum(effective_ratios) / len(effective_ratios) if effective_ratios else 1.0
    warnings = []
    final_depth = config.consumption.depth(len(active))
    expected_repeat_events = max(0, config.rotations - 1) * final_depth * final_depth / max(1, len(active))
    if consumed_events and immediate_repeat_events == 0 and expected_repeat_events >= 3:
        warnings.append("no_immediate_track_repeats")
    if mean_entropy < 0.95 or mean_effective < 0.80:
        warnings.append("concentrated_selection_weights")
    if (
        config.rotations >= 10
        and 0 < final_depth < len(active)
        and expected_repeat_events >= 3
        and occurrence_values
        and max(occurrence_values) - min(occurrence_values) <= 1
    ):
        warnings.append("scheduler_like_exposure_range")

    metrics: dict[str, object] = {
        "active_occurrence_count": len(active_occurrence_ids),
        "active_unique_track_count": len(active_track_ids),
        "occurrence_exposure": distribution(occurrence_values),
        "track_exposure": distribution(track_values),
        "zero_exposure_occurrences": zero_occurrence,
        "zero_exposure_occurrence_percent": zero_occurrence / max(1, len(occurrence_values)),
        "zero_exposure_tracks": zero_track,
        "zero_exposure_track_percent": zero_track / max(1, len(track_values)),
        "occurrence_gini": gini(occurrence_values),
        "track_gini": gini(track_values),
        "occurrence_coefficient_of_variation": coefficient_of_variation(occurrence_values),
        "track_coefficient_of_variation": coefficient_of_variation(track_values),
        "final_occurrence_coverage": coverage_occurrence[-1] if coverage_occurrence else 0.0,
        "final_track_coverage": coverage_track[-1] if coverage_track else 0.0,
        "coverage_threshold_operations": {
            str(int(threshold * 100)): first_threshold(coverage_track, threshold)
            for threshold in (0.50, 0.75, 0.90, 0.95)
        },
        "track_coverage_checkpoints": {
            str(checkpoint): coverage_track[checkpoint - 1]
            for checkpoint in (10, 25, 50, 100)
            if checkpoint <= len(coverage_track)
        },
        "mean_adjacent_track_overlap": mean_adjacent_track,
        "mean_adjacent_occurrence_overlap": mean_adjacent_occurrence,
        "mean_adjacent_overlap_vs_random_expectation": mean_adjacent_ratio,
        "mean_track_recurrence_within_3_rotations": mean_window_3,
        "mean_track_recurrence_within_5_rotations": mean_window_5,
        "immediate_track_repeat_rate": immediate_repeat_events / max(1, consumed_events),
        "mean_normalized_weight_entropy": mean_entropy,
        "mean_effective_choice_ratio": mean_effective,
        "perceived_actual_track_correlation": correlation,
        "history_reset_count": reset_count,
    }
    metadata = {
        "seed": config.seed,
        "initial_playlist_occurrences": len(occurrences),
        "initial_unique_tracks": len({item.track_id for item in occurrences}),
        "consumption": config.consumption.label(),
        "rotations": config.rotations,
        "algorithm": algorithm.name,
        "oracle_benchmark": algorithm.oracle,
        "algorithm_parameters": algorithm.parameters(),
        "reset_after": config.reset_after,
        "mutation_count": len(config.mutations),
    }
    return SimulationResult(
        metadata,
        metrics,
        coverage_occurrence,
        coverage_track,
        exposure_occurrence,
        exposure_track,
        perceived,
        warnings,
    )
