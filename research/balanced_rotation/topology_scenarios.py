"""Approved large-collection pool topology matrices."""

from __future__ import annotations

from dataclasses import dataclass

from .model import ConsumptionModel


TOPOLOGIES = {1000: (1, 2, 4), 2000: (1, 2, 4, 8), 4000: (1, 2, 4, 8, 16)}
DEPTHS = ("30", "50")
ALGORITHMS = ("true_random", "candidate_c")
USAGES = ("sequential", "round_robin", "random")
SEED_BASE = 2026090601


@dataclass(frozen=True)
class TopologyScenario:
    corpus_size: int
    pool_count: int
    consumption: ConsumptionModel
    algorithm: str
    usage: str
    global_sessions: int
    seed: int


def topology_matrix(global_sessions: int, seed_count: int) -> list[TopologyScenario]:
    scenarios = []
    for corpus_size, pool_counts in TOPOLOGIES.items():
        for pool_count in pool_counts:
            usages = ("single",) if pool_count == 1 else USAGES
            for usage in usages:
                for depth in DEPTHS:
                    for algorithm in ALGORITHMS:
                        for seed_offset in range(seed_count):
                            scenarios.append(TopologyScenario(
                                corpus_size, pool_count, ConsumptionModel.parse(depth),
                                algorithm, usage, global_sessions, SEED_BASE + seed_offset,
                            ))
    return scenarios


def topology_smoke_matrix() -> list[TopologyScenario]:
    return topology_matrix(global_sessions=10, seed_count=2)


def topology_main_matrix() -> list[TopologyScenario]:
    return topology_matrix(global_sessions=100, seed_count=20)
