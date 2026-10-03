"""Research-only simulation of independently balanced playlist pool topologies."""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
import random
import statistics
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
from .model import ConsumptionModel, Occurrence


def derived_seed(seed: int, stream: int) -> int:
    """Derive stable independent RNG streams without relying on salted hash()."""
    return (seed * 1_000_003 + stream * 97_409 + 0x9E3779B9) & ((1 << 63) - 1)


def partition_occurrences(
    occurrences: Sequence[Occurrence], pool_count: int, seed: int
) -> list[list[Occurrence]]:
    if pool_count <= 0:
        raise ValueError("pool_count must be positive")
    if pool_count > len(occurrences) and occurrences:
        raise ValueError("pool_count cannot exceed occurrence count")
    shuffled = list(occurrences)
    # Preserve exact existing-simulator semantics for the single-pool baseline.
    if pool_count > 1:
        random.Random(derived_seed(seed, 1)).shuffle(shuffled)
    pools = [[] for _ in range(pool_count)]
    for index, occurrence in enumerate(shuffled):
        pools[index % pool_count].append(occurrence)
    return pools


def session_schedule(
    pool_count: int, global_sessions: int, usage: str, seed: int
) -> list[int]:
    if pool_count <= 0 or global_sessions < 0:
        raise ValueError("invalid pool/session count")
    if pool_count == 1:
        if usage != "single":
            raise ValueError("one-pool topology must use 'single'")
        return [0] * global_sessions
    if usage == "single":
        raise ValueError("multi-pool topology cannot use 'single'")
    if usage == "round_robin":
        return [session % pool_count for session in range(global_sessions)]
    if usage == "sequential":
        base, remainder = divmod(global_sessions, pool_count)
        return [
            pool
            for pool in range(pool_count)
            for _ in range(base + (1 if pool < remainder else 0))
        ]
    if usage == "random":
        rng = random.Random(derived_seed(seed, 2))
        return [rng.randrange(pool_count) for _ in range(global_sessions)]
    raise ValueError(f"unknown pool usage: {usage}")


@dataclass(frozen=True)
class TopologyConfig:
    seed: int
    global_sessions: int
    consumption: ConsumptionModel
    pool_count: int
    usage: str


@dataclass
class TopologyResult:
    metadata: dict[str, object]
    metrics: dict[str, object]
    pool_session_counts: list[int]
    warnings: list[str] = field(default_factory=list)
    corpus_exposure_by_occurrence: dict[str, int] = field(default_factory=dict)
    corpus_coverage_curve: list[float] = field(default_factory=list)

    def as_compact_dict(self) -> dict[str, object]:
        return {
            "metadata": self.metadata,
            "metrics": self.metrics,
            "pool_session_counts": self.pool_session_counts,
            "warnings": self.warnings,
        }

    def as_detailed_dict(self) -> dict[str, object]:
        return asdict(self)


def simulate_topology(
    occurrences: Sequence[Occurrence],
    algorithm_factory,
    config: TopologyConfig,
) -> TopologyResult:
    if config.global_sessions < 0:
        raise ValueError("global_sessions cannot be negative")
    occurrence_ids = [item.occurrence_id for item in occurrences]
    if len(set(occurrence_ids)) != len(occurrence_ids):
        raise ValueError("occurrence_id values must be unique")
    pools = partition_occurrences(occurrences, config.pool_count, config.seed)
    flattened = [item.occurrence_id for pool in pools for item in pool]
    if len(flattened) != len(occurrence_ids) or set(flattened) != set(occurrence_ids):
        raise AssertionError("partition must preserve every occurrence exactly once")
    sizes = [len(pool) for pool in pools]
    if sizes and max(sizes) - min(sizes) > 1:
        raise AssertionError("neutral partition sizes differ by more than one")

    schedule = session_schedule(
        config.pool_count, config.global_sessions, config.usage, config.seed
    )
    pool_session_counts = [schedule.count(index) for index in range(config.pool_count)]
    algorithms: list[RotationAlgorithm] = [algorithm_factory() for _ in pools]
    rngs = [
        random.Random(config.seed if config.pool_count == 1 else derived_seed(config.seed, 100 + index))
        for index in range(config.pool_count)
    ]
    pool_operations = [0] * config.pool_count
    exposure_occurrence = {item.occurrence_id: 0 for item in occurrences}
    exposure_track = {item.track_id: 0 for item in occurrences}
    ever_occurrence: set[str] = set()
    coverage_curve: list[float] = []
    zero_curve: list[int] = []
    global_adjacent: list[float] = []
    same_pool_adjacent: list[float] = []
    short_window_3: list[float] = []
    short_window_5: list[float] = []
    recent_heard: list[set[str]] = []
    previous_heard: set[str] = set()
    previous_pool: int | None = None
    entropies: list[float] = []
    effective_choices: list[float] = []

    for global_session, pool_index in enumerate(schedule):
        pool = pools[pool_index]
        algorithm = algorithms[pool_index]
        operation = pool_operations[pool_index]
        weights = algorithm.weights(pool, operation)
        entropies.append(normalized_entropy(weights))
        effective_choices.append(effective_choice_ratio(weights))
        generated = weighted_permutation(pool, weights, rngs[pool_index])
        heard = generated[: config.consumption.depth(len(pool))]
        heard_tracks = {item.track_id for item in heard}
        if global_session:
            overlap = len(heard_tracks & previous_heard) / max(1, len(heard_tracks))
            global_adjacent.append(overlap)
            if pool_index == previous_pool:
                same_pool_adjacent.append(overlap)
        for window, target in ((3, short_window_3), (5, short_window_5)):
            recent_union = set().union(*recent_heard[-window:]) if recent_heard else set()
            if recent_union:
                target.append(len(heard_tracks & recent_union) / max(1, len(heard_tracks)))
        recent_heard.append(heard_tracks)
        previous_heard, previous_pool = heard_tracks, pool_index
        for item in heard:
            exposure_occurrence[item.occurrence_id] += 1
            exposure_track[item.track_id] += 1
            ever_occurrence.add(item.occurrence_id)
        algorithm.observe(generated, heard, operation)
        pool_operations[pool_index] += 1
        coverage_curve.append(len(ever_occurrence) / max(1, len(occurrences)))
        zero_curve.append(len(occurrences) - len(ever_occurrence))

    exposure_values = list(exposure_occurrence.values())
    track_values = list(exposure_track.values())
    pool_coverages = []
    for pool in pools:
        heard_count = sum(exposure_occurrence[item.occurrence_id] > 0 for item in pool)
        pool_coverages.append(heard_count / max(1, len(pool)))
    correlations = []
    for pool, algorithm in zip(pools, algorithms):
        track_ids = [item.track_id for item in pool]
        scores = algorithm.perceived_scores(track_ids)
        correlation = pearson_correlation(
            [scores[item] for item in track_ids],
            [float(exposure_track[item]) for item in track_ids],
        )
        if correlation is not None:
            correlations.append(correlation)
    warnings = []
    mean_entropy = statistics.fmean(entropies) if entropies else 1.0
    mean_effective = statistics.fmean(effective_choices) if effective_choices else 1.0
    if mean_entropy < 0.95 or mean_effective < 0.80:
        warnings.append("concentrated_selection_weights")
    expected_exposure = sum(
        config.consumption.depth(len(pools[index])) for index in schedule
    )
    if sum(exposure_values) != expected_exposure:
        warnings.append("fair_budget_accounting_failure")
    if sum(pool_session_counts) != config.global_sessions:
        warnings.append("pool_session_accounting_failure")

    checkpoints = {}
    for checkpoint in (10, 25, 50, 75, 100):
        if checkpoint <= len(coverage_curve):
            checkpoints[str(checkpoint)] = {
                "corpus_coverage": coverage_curve[checkpoint - 1],
                "zero_exposure_count": zero_curve[checkpoint - 1],
                "zero_exposure_percent": zero_curve[checkpoint - 1] / max(1, len(occurrences)),
            }
    metrics = {
        "expected_consumed_exposure": expected_exposure,
        "actual_consumed_exposure": sum(exposure_values),
        "corpus_occurrence_exposure": distribution(exposure_values),
        "corpus_track_exposure": distribution(track_values),
        "final_corpus_coverage": coverage_curve[-1] if coverage_curve else 0.0,
        "zero_exposure_count": zero_curve[-1] if zero_curve else len(occurrences),
        "zero_exposure_percent": (zero_curve[-1] / max(1, len(occurrences))) if zero_curve else 1.0,
        "corpus_gini": gini(track_values),
        "corpus_coefficient_of_variation": coefficient_of_variation(track_values),
        "coverage_threshold_operations": {
            str(int(threshold * 100)): first_threshold(coverage_curve, threshold)
            for threshold in (0.50, 0.75, 0.90, 0.95)
        },
        "global_session_checkpoints": checkpoints,
        "mean_global_adjacent_overlap": statistics.fmean(global_adjacent) if global_adjacent else None,
        "mean_same_pool_adjacent_overlap": statistics.fmean(same_pool_adjacent) if same_pool_adjacent else None,
        "same_pool_adjacent_pair_count": len(same_pool_adjacent),
        "mean_recurrence_within_3_sessions": statistics.fmean(short_window_3) if short_window_3 else None,
        "mean_recurrence_within_5_sessions": statistics.fmean(short_window_5) if short_window_5 else None,
        "pool_coverages": pool_coverages,
        "minimum_pool_coverage": min(pool_coverages) if pool_coverages else 0.0,
        "maximum_pool_coverage": max(pool_coverages) if pool_coverages else 0.0,
        "pool_coverage_variance": statistics.pvariance(pool_coverages) if pool_coverages else 0.0,
        "mean_normalized_weight_entropy": mean_entropy,
        "mean_effective_choice_ratio": mean_effective,
        "opportunity_actual_correlation": statistics.fmean(correlations) if correlations else None,
    }
    friction = "minimal" if config.pool_count == 1 else "moderate" if config.pool_count <= 4 else "high"
    metadata = {
        "seed": config.seed,
        "source_corpus_size": len(occurrences),
        "pool_count": config.pool_count,
        "pool_sizes": sizes,
        "usage": config.usage,
        "global_sessions": config.global_sessions,
        "consumption": config.consumption.label(),
        "algorithm": algorithms[0].name if algorithms else algorithm_factory().name,
        "algorithm_parameters": algorithms[0].parameters() if algorithms else algorithm_factory().parameters(),
        "partition_method": "seeded_shuffle_round_robin",
        "rng_streams": "independent_assignment_selection_and_per_pool",
        "output_playlists": config.pool_count,
        "management_burden": friction,
    }
    return TopologyResult(
        metadata=metadata,
        metrics=metrics,
        pool_session_counts=pool_session_counts,
        warnings=warnings,
        corpus_exposure_by_occurrence=exposure_occurrence,
        corpus_coverage_curve=coverage_curve,
    )
