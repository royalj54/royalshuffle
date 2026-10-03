"""Standard experiment matrices and focused lifecycle scenarios."""

from __future__ import annotations

from dataclasses import dataclass
from itertools import product

from .model import ConsumptionModel


SMOKE_PLAYLIST_SIZES = (100, 250, 500, 1000, 2000, 4000)
SMOKE_CONSUMPTIONS = ("10", "30", "50", "100", "25%", "50%")
SMOKE_ALGORITHMS = ("true_random", "candidate_a", "candidate_b", "candidate_c")
SMOKE_SEEDS = (2026090501, 2026090502)
MAIN_SEEDS = tuple(2026090501 + index for index in range(20))


@dataclass(frozen=True)
class Scenario:
    playlist_size: int
    consumption: ConsumptionModel
    algorithm: str
    rotations: int
    seed: int


def smoke_matrix() -> list[Scenario]:
    return [
        Scenario(size, ConsumptionModel.parse(consumption), algorithm, 10, seed)
        for size, consumption, algorithm, seed in product(
            SMOKE_PLAYLIST_SIZES,
            SMOKE_CONSUMPTIONS,
            SMOKE_ALGORITHMS,
            SMOKE_SEEDS,
        )
    ]


def main_matrix() -> list[Scenario]:
    return [
        Scenario(size, ConsumptionModel.parse(consumption), algorithm, 100, seed)
        for size, consumption, algorithm, seed in product(
            SMOKE_PLAYLIST_SIZES,
            SMOKE_CONSUMPTIONS,
            SMOKE_ALGORITHMS,
            MAIN_SEEDS,
        )
    ]
