"""Post-process compact simulator JSON into reproducible comparison statistics."""

from __future__ import annotations

import argparse
from collections import defaultdict
import json
from pathlib import Path
import statistics


def mean(values):
    values = list(values)
    return statistics.fmean(values) if values else None


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args(argv)
    payload = json.loads(args.input.read_text(encoding="utf-8"))
    groups = defaultdict(list)
    for result in payload["results"]:
        meta = result["metadata"]
        groups[(meta["initial_playlist_occurrences"], meta["consumption"], meta["algorithm"])].append(result)

    metric_names = (
        "final_track_coverage", "zero_exposure_track_percent", "track_gini",
        "track_coefficient_of_variation", "mean_adjacent_track_overlap",
        "mean_track_recurrence_within_3_rotations", "mean_track_recurrence_within_5_rotations",
        "mean_normalized_weight_entropy", "mean_effective_choice_ratio",
        "perceived_actual_track_correlation",
    )
    scenario_rows = []
    for (size, consumption, algorithm), members in sorted(groups.items()):
        row = {"playlist_size": size, "consumption": consumption, "algorithm": algorithm, "seeds": len(members)}
        for metric in metric_names:
            row[metric] = mean(
                member["metrics"][metric] for member in members
                if member["metrics"][metric] is not None
            )
        row["coverage_checkpoints"] = {
            checkpoint: mean(member["metrics"]["track_coverage_checkpoints"].get(checkpoint) for member in members)
            for checkpoint in ("10", "25", "50", "100")
        }
        row["coverage_thresholds"] = {}
        for threshold in ("50", "75", "90", "95"):
            values = [member["metrics"]["coverage_threshold_operations"][threshold] for member in members]
            attained = [value for value in values if value is not None]
            row["coverage_thresholds"][threshold] = {
                "attainment_rate": len(attained) / len(values),
                "mean_operation_when_attained": mean(attained),
            }
        row["warning_count"] = sum(bool(member["warnings"]) for member in members)
        scenario_rows.append(row)

    indexed = {(row["playlist_size"], row["consumption"], row["algorithm"]): row for row in scenario_rows}
    comparisons = []
    algorithms = ("candidate_a_recency_oracle", "candidate_b_exposure_oracle", "candidate_c_opportunity")
    for size, consumption in sorted({(key[0], key[1]) for key in indexed}):
        baseline = indexed[(size, consumption, "true_random")]
        for algorithm in algorithms:
            candidate = indexed[(size, consumption, algorithm)]
            changes = {}
            for metric in metric_names[:-1]:
                base, value = baseline[metric], candidate[metric]
                if base == 0:
                    changes[metric] = None
                elif metric == "final_track_coverage":
                    changes[metric] = (value - base) / base
                else:
                    changes[metric] = (base - value) / base
            comparisons.append({
                "playlist_size": size, "consumption": consumption,
                "algorithm": algorithm, "relative_improvement_vs_true_random": changes,
            })

    output = {
        "source_runtime_seconds": payload["runtime_seconds"],
        "scenario_aggregates": scenario_rows,
        "comparisons": comparisons,
        "warning_trials": [
            {"metadata": result["metadata"], "warnings": result["warnings"]}
            for result in payload["results"] if result["warnings"]
        ],
    }
    args.output.write_text(json.dumps(output, indent=2, sort_keys=True), encoding="utf-8")
    print(f"Wrote {len(scenario_rows)} scenario aggregates and {len(comparisons)} comparisons")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
