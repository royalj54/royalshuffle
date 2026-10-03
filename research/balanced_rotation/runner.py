"""Command-line entry point for reproducible Balanced Rotation experiments."""

from __future__ import annotations

import argparse
from pathlib import Path
import time

from .algorithms import ALGORITHMS
from .model import SimulationConfig, simulate, synthetic_playlist
from .reporting import write_csv, write_json, write_markdown
from .scenarios import Scenario, main_matrix, smoke_matrix


def run_scenarios(scenarios: list[Scenario]):
    results = []
    for scenario in scenarios:
        algorithm = ALGORITHMS[scenario.algorithm]()
        config = SimulationConfig(
            seed=scenario.seed,
            rotations=scenario.rotations,
            consumption=scenario.consumption,
        )
        started = time.perf_counter()
        result = simulate(synthetic_playlist(scenario.playlist_size), algorithm, config)
        result.metadata["runtime_seconds"] = time.perf_counter() - started
        results.append(result)
    return results


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--matrix", choices=("smoke", "main"), default="smoke")
    parser.add_argument(
        "--output-mode", choices=("compact", "detailed"), default="compact"
    )
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args(argv)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    scenarios = smoke_matrix() if args.matrix == "smoke" else main_matrix()
    started = time.perf_counter()
    results = run_scenarios(scenarios)
    runtime = time.perf_counter() - started
    stem = args.matrix
    write_json(
        args.output_dir / f"{stem}-results.json",
        results,
        runtime,
        compact=args.output_mode == "compact",
    )
    write_csv(args.output_dir / f"{stem}-results.csv", results)
    write_markdown(
        args.output_dir / f"{stem}-summary.md",
        results,
        runtime,
        f"Balanced Rotation {stem.title()} Run",
    )
    print(f"Completed {len(results)} trials in {runtime:.3f} seconds")
    print(args.output_dir / f"{stem}-summary.md")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
