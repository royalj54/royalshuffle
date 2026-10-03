"""Compact JSON, CSV, and Markdown output for topology simulations."""

from __future__ import annotations

import csv
import json
from pathlib import Path
import statistics


def write_topology_json(path: Path, results, runtime: float, compact: bool = True):
    payload = {
        "schema_version": 1,
        "output_mode": "compact" if compact else "detailed",
        "runtime_seconds": runtime,
        "results": [
            result.as_compact_dict() if compact else result.as_detailed_dict()
            for result in results
        ],
    }
    path.write_text(json.dumps(payload, indent=2, sort_keys=True), encoding="utf-8")


def flat_rows(results):
    for result in results:
        meta, metrics = result.metadata, result.metrics
        checkpoints = metrics["global_session_checkpoints"]
        row = {
            "seed": meta["seed"], "corpus_size": meta["source_corpus_size"],
            "pool_count": meta["pool_count"], "pool_sizes": ";".join(map(str, meta["pool_sizes"])),
            "usage": meta["usage"], "depth": meta["consumption"],
            "algorithm": meta["algorithm"], "global_sessions": meta["global_sessions"],
            "runtime_seconds": meta.get("runtime_seconds"),
            "coverage": metrics["final_corpus_coverage"],
            "zero_count": metrics["zero_exposure_count"], "zero_percent": metrics["zero_exposure_percent"],
            "gini": metrics["corpus_gini"], "cv": metrics["corpus_coefficient_of_variation"],
            "global_adjacent": metrics["mean_global_adjacent_overlap"],
            "same_pool_adjacent": metrics["mean_same_pool_adjacent_overlap"],
            "recurrence_3": metrics["mean_recurrence_within_3_sessions"],
            "recurrence_5": metrics["mean_recurrence_within_5_sessions"],
            "minimum_pool_coverage": metrics["minimum_pool_coverage"],
            "maximum_pool_coverage": metrics["maximum_pool_coverage"],
            "pool_coverage_variance": metrics["pool_coverage_variance"],
            "entropy": metrics["mean_normalized_weight_entropy"],
            "effective_choice_ratio": metrics["mean_effective_choice_ratio"],
            "opportunity_actual_correlation": metrics["opportunity_actual_correlation"],
            "pool_session_counts": ";".join(map(str, result.pool_session_counts)),
            "management_burden": meta["management_burden"],
            "warnings": ";".join(result.warnings),
        }
        for threshold, operation in metrics["coverage_threshold_operations"].items():
            row[f"sessions_to_{threshold}"] = operation
        for checkpoint in ("10", "25", "50", "75", "100"):
            values = checkpoints.get(checkpoint, {})
            row[f"coverage_at_{checkpoint}"] = values.get("corpus_coverage")
            row[f"zero_at_{checkpoint}"] = values.get("zero_exposure_count")
        yield row


def write_topology_csv(path: Path, results):
    rows = list(flat_rows(results))
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def aggregate(results):
    groups = {}
    for result in results:
        m = result.metadata
        key = (m["source_corpus_size"], m["pool_count"], m["usage"], m["consumption"], m["algorithm"])
        groups.setdefault(key, []).append(result)
    rows = []
    for key, members in sorted(groups.items()):
        def avg(metric):
            values = [item.metrics[metric] for item in members if item.metrics[metric] is not None]
            return statistics.fmean(values) if values else None
        rows.append({
            "corpus": key[0], "pools": key[1], "usage": key[2], "depth": key[3], "algorithm": key[4],
            "coverage": avg("final_corpus_coverage"), "zero": avg("zero_exposure_percent"),
            "gini": avg("corpus_gini"), "cv": avg("corpus_coefficient_of_variation"),
            "global_adjacent": avg("mean_global_adjacent_overlap"),
            "same_pool_adjacent": avg("mean_same_pool_adjacent_overlap"),
            "min_pool": avg("minimum_pool_coverage"), "max_pool": avg("maximum_pool_coverage"),
            "pool_variance": avg("pool_coverage_variance"), "entropy": avg("mean_normalized_weight_entropy"),
            "effective": avg("mean_effective_choice_ratio"),
            "warnings": sorted({warning for item in members for warning in item.warnings}),
        })
    return rows


def write_topology_markdown(path: Path, results, runtime: float, title: str):
    rows = aggregate(results)
    lines = [f"# {title}", "", f"Runtime: {runtime:.3f} seconds", "",
        "| Corpus | Pools | Usage | Depth | Algorithm | Coverage | Zero | Gini | Global repeat | Same-pool repeat | Min pool | Pool variance | Entropy | Warnings |",
        "|---:|---:|:---|---:|:---|---:|---:|---:|---:|---:|---:|---:|---:|:---|"]
    for row in rows:
        same = "n/a" if row["same_pool_adjacent"] is None else f"{row['same_pool_adjacent']:.3f}"
        lines.append(
            f"| {row['corpus']} | {row['pools']} | {row['usage']} | {row['depth']} | {row['algorithm']} | "
            f"{row['coverage']:.1%} | {row['zero']:.1%} | {row['gini']:.3f} | "
            f"{row['global_adjacent']:.3f} | {same} | {row['min_pool']:.1%} | "
            f"{row['pool_variance']:.4f} | {row['entropy']:.3f} | {', '.join(row['warnings']) or 'none'} |"
        )
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
