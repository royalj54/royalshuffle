"""Machine-readable and Markdown report generation."""

from __future__ import annotations

import csv
import json
from pathlib import Path
from typing import Iterable

from .model import SimulationResult


def write_json(
    path: Path,
    results: Iterable[SimulationResult],
    runtime_seconds: float,
    compact: bool = False,
) -> None:
    results = list(results)
    payload = {
        "schema_version": 1,
        "output_mode": "compact" if compact else "detailed",
        "runtime_seconds": runtime_seconds,
        "results": [
            result.as_compact_dict() if compact else result.as_dict()
            for result in results
        ],
    }
    path.write_text(json.dumps(payload, indent=2, sort_keys=True), encoding="utf-8")


def _rows(results: Iterable[SimulationResult]):
    for result in results:
        meta, metrics = result.metadata, result.metrics
        yield {
            "seed": meta["seed"],
            "playlist_size": meta["initial_playlist_occurrences"],
            "consumption": meta["consumption"],
            "rotations": meta["rotations"],
            "algorithm": meta["algorithm"],
            "oracle_benchmark": meta["oracle_benchmark"],
            "track_coverage": metrics["final_track_coverage"],
            "zero_exposure_tracks": metrics["zero_exposure_tracks"],
            "track_gini": metrics["track_gini"],
            "track_cv": metrics["track_coefficient_of_variation"],
            "adjacent_track_overlap": metrics["mean_adjacent_track_overlap"],
            "adjacent_overlap_vs_random": metrics["mean_adjacent_overlap_vs_random_expectation"],
            "recurrence_within_3": metrics["mean_track_recurrence_within_3_rotations"],
            "recurrence_within_5": metrics["mean_track_recurrence_within_5_rotations"],
            "weight_entropy": metrics["mean_normalized_weight_entropy"],
            "effective_choice_ratio": metrics["mean_effective_choice_ratio"],
            "opportunity_actual_correlation": metrics["perceived_actual_track_correlation"],
            "warnings": ";".join(result.warnings),
        }


def write_csv(path: Path, results: Iterable[SimulationResult]) -> None:
    rows = list(_rows(results))
    if not rows:
        return
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def aggregate(results: Iterable[SimulationResult]) -> list[dict[str, object]]:
    groups: dict[tuple[int, str, str], list[SimulationResult]] = {}
    for result in results:
        key = (
            int(result.metadata["initial_playlist_occurrences"]),
            str(result.metadata["consumption"]),
            str(result.metadata["algorithm"]),
        )
        groups.setdefault(key, []).append(result)
    summaries = []
    for (size, consumption, algorithm), members in sorted(groups.items()):
        def mean(name: str) -> float:
            return sum(float(item.metrics[name]) for item in members) / len(members)
        correlations = [
            float(item.metrics["perceived_actual_track_correlation"])
            for item in members
            if item.metrics["perceived_actual_track_correlation"] is not None
        ]
        summaries.append({
            "playlist_size": size,
            "consumption": consumption,
            "algorithm": algorithm,
            "seeds": len(members),
            "mean_coverage": mean("final_track_coverage"),
            "mean_zero_track_percent": mean("zero_exposure_track_percent"),
            "mean_track_gini": mean("track_gini"),
            "mean_track_cv": mean("track_coefficient_of_variation"),
            "mean_adjacent_overlap": mean("mean_adjacent_track_overlap"),
            "mean_adjacent_overlap_vs_random": mean("mean_adjacent_overlap_vs_random_expectation"),
            "mean_recurrence_within_3": mean("mean_track_recurrence_within_3_rotations"),
            "mean_recurrence_within_5": mean("mean_track_recurrence_within_5_rotations"),
            "mean_entropy": mean("mean_normalized_weight_entropy"),
            "mean_effective_choice_ratio": mean("mean_effective_choice_ratio"),
            "mean_opportunity_actual_correlation": (
                sum(correlations) / len(correlations) if correlations else None
            ),
            "warnings": sorted({warning for item in members for warning in item.warnings}),
        })
    return summaries


def write_markdown(
    path: Path, results: Iterable[SimulationResult], runtime_seconds: float, title: str
) -> None:
    summaries = aggregate(results)
    lines = [
        f"# {title}", "", f"Runtime: {runtime_seconds:.3f} seconds", "",
        "Candidates A and B are oracle benchmarks updated from exact synthetic listening. "
        "Candidate C uses generated position only.", "",
        "| Size | Listen | Algorithm | Coverage | Zero | Gini | Adjacent overlap | Entropy | Effective choices | C correlation | Warnings |",
        "|---:|:---:|:---|---:|---:|---:|---:|---:|---:|---:|:---|",
    ]
    for row in summaries:
        correlation = row["mean_opportunity_actual_correlation"]
        correlation_text = "n/a" if correlation is None else f"{correlation:.3f}"
        lines.append(
            f"| {row['playlist_size']} | {row['consumption']} | {row['algorithm']} | "
            f"{row['mean_coverage']:.1%} | {row['mean_zero_track_percent']:.1%} | "
            f"{row['mean_track_gini']:.3f} | {row['mean_adjacent_overlap']:.3f} | "
            f"{row['mean_entropy']:.3f} | {row['mean_effective_choice_ratio']:.3f} | "
            f"{correlation_text} | {', '.join(row['warnings']) or 'none'} |"
        )
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
