"""Run staged large-collection topology experiments."""

from __future__ import annotations

import argparse
from pathlib import Path
import time

from .algorithms import ALGORITHMS
from .model import synthetic_playlist
from .topology import TopologyConfig, simulate_topology
from .topology_reporting import write_topology_csv, write_topology_json, write_topology_markdown
from .topology_scenarios import topology_main_matrix, topology_smoke_matrix


def run_scenarios(scenarios):
    results = []
    for scenario in scenarios:
        config = TopologyConfig(
            seed=scenario.seed, global_sessions=scenario.global_sessions,
            consumption=scenario.consumption, pool_count=scenario.pool_count, usage=scenario.usage,
        )
        started = time.perf_counter()
        result = simulate_topology(
            synthetic_playlist(scenario.corpus_size), ALGORITHMS[scenario.algorithm], config
        )
        result.metadata["runtime_seconds"] = time.perf_counter() - started
        results.append(result)
    return results


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--matrix", choices=("smoke", "main"), required=True)
    parser.add_argument("--output-mode", choices=("compact", "detailed"), default="compact")
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args(argv)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    scenarios = topology_smoke_matrix() if args.matrix == "smoke" else topology_main_matrix()
    started = time.perf_counter()
    results = run_scenarios(scenarios)
    runtime = time.perf_counter() - started
    stem = f"topology-{args.matrix}"
    write_topology_json(args.output_dir / f"{stem}-results.json", results, runtime, args.output_mode == "compact")
    write_topology_csv(args.output_dir / f"{stem}-results.csv", results)
    write_topology_markdown(args.output_dir / f"{stem}-summary.md", results, runtime, f"Balanced Rotation Topology {args.matrix.title()}")
    print(f"Completed {len(results)} trials in {runtime:.3f} seconds")
    print(args.output_dir / f"{stem}-summary.md")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
