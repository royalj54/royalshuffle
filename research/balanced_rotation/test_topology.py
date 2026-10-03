from __future__ import annotations

import unittest

from research.balanced_rotation.algorithms import OpportunityBalance, TrueRandom
from research.balanced_rotation.model import ConsumptionModel, SimulationConfig, simulate, synthetic_playlist
from research.balanced_rotation.topology import (
    TopologyConfig,
    partition_occurrences,
    session_schedule,
    simulate_topology,
)
from research.balanced_rotation.topology_scenarios import topology_main_matrix, topology_smoke_matrix


class TopologyTests(unittest.TestCase):
    def test_partition_is_complete_unique_and_balanced(self):
        source = synthetic_playlist(1001)
        pools = partition_occurrences(source, 4, 123)
        flattened = [item for pool in pools for item in pool]
        self.assertCountEqual(flattened, source)
        self.assertEqual(len({item.occurrence_id for item in flattened}), len(source))
        self.assertLessEqual(max(map(len, pools)) - min(map(len, pools)), 1)

    def test_schedule_semantics_and_budget(self):
        sequential = session_schedule(4, 10, "sequential", 1)
        self.assertEqual(sequential, [0, 0, 0, 1, 1, 1, 2, 2, 3, 3])
        self.assertEqual(session_schedule(4, 6, "round_robin", 1), [0, 1, 2, 3, 0, 1])
        random_schedule = session_schedule(4, 100, "random", 1)
        self.assertEqual(len(random_schedule), 100)
        self.assertEqual(random_schedule, session_schedule(4, 100, "random", 1))

    def test_fair_budget_and_pool_corpus_accounting(self):
        config = TopologyConfig(5, 10, ConsumptionModel.parse(30), 4, "round_robin")
        result = simulate_topology(synthetic_playlist(1000), OpportunityBalance, config)
        self.assertEqual(result.metrics["expected_consumed_exposure"], 300)
        self.assertEqual(result.metrics["actual_consumed_exposure"], 300)
        self.assertEqual(sum(result.corpus_exposure_by_occurrence.values()), 300)
        self.assertEqual(sum(result.pool_session_counts), 10)
        self.assertEqual(result.pool_session_counts, [3, 3, 2, 2])

    def test_single_pool_matches_existing_simulator(self):
        source = synthetic_playlist(250)
        consumption = ConsumptionModel.parse(30)
        existing = simulate(source, TrueRandom(), SimulationConfig(77, 10, consumption))
        topology = simulate_topology(
            source, TrueRandom, TopologyConfig(77, 10, consumption, 1, "single")
        )
        self.assertEqual(
            topology.corpus_exposure_by_occurrence,
            existing.actual_exposure_by_occurrence,
        )
        self.assertEqual(topology.corpus_coverage_curve, existing.coverage_curve_occurrences)

    def test_seeded_topology_is_deterministic(self):
        config = TopologyConfig(9, 10, ConsumptionModel.parse(50), 4, "random")
        left = simulate_topology(synthetic_playlist(1000), OpportunityBalance, config)
        right = simulate_topology(synthetic_playlist(1000), OpportunityBalance, config)
        self.assertEqual(left.as_detailed_dict(), right.as_detailed_dict())

    def test_compact_contains_checkpoints_and_session_counts(self):
        result = simulate_topology(
            synthetic_playlist(1000), OpportunityBalance,
            TopologyConfig(3, 10, ConsumptionModel.parse(30), 2, "sequential"),
        )
        compact = result.as_compact_dict()
        self.assertIn("pool_session_counts", compact)
        self.assertEqual(sum(compact["pool_session_counts"]), 10)
        checkpoint = compact["metrics"]["global_session_checkpoints"]["10"]
        self.assertIn("corpus_coverage", checkpoint)
        self.assertIn("zero_exposure_count", checkpoint)
        self.assertNotIn("corpus_exposure_by_occurrence", compact)

    def test_matrix_sizes(self):
        self.assertEqual(len(topology_smoke_matrix()), 240)
        self.assertEqual(len(topology_main_matrix()), 2400)


if __name__ == "__main__":
    unittest.main()
