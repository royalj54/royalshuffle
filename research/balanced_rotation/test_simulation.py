from __future__ import annotations

import unittest

from research.balanced_rotation.algorithms import OpportunityBalance, RecencyWeight, TrueRandom
from research.balanced_rotation.model import (
    ConsumptionModel,
    MutationEvent,
    Occurrence,
    SimulationConfig,
    simulate,
    synthetic_playlist,
)
from research.balanced_rotation.scenarios import main_matrix, smoke_matrix


class SimulationTests(unittest.TestCase):
    def test_same_seed_is_deterministic(self):
        config = SimulationConfig(1234, 10, ConsumptionModel.parse("25%"))
        left = simulate(synthetic_playlist(100), RecencyWeight(), config)
        right = simulate(synthetic_playlist(100), RecencyWeight(), config)
        self.assertEqual(left.as_dict(), right.as_dict())

    def test_duplicate_occurrences_survive_and_share_history(self):
        playlist = [Occurrence("occ-a", "track-x"), Occurrence("occ-b", "track-x")]
        config = SimulationConfig(7, 1, ConsumptionModel.parse(2))
        result = simulate(playlist, RecencyWeight(), config)
        self.assertEqual(set(result.actual_exposure_by_occurrence), {"occ-a", "occ-b"})
        self.assertEqual(result.actual_exposure_by_track["track-x"], 2)
        self.assertEqual(result.metrics["active_occurrence_count"], 2)
        self.assertEqual(result.metrics["active_unique_track_count"], 1)

    def test_mutation_adds_and_removes_without_crashing(self):
        playlist = synthetic_playlist(10)
        mutation = MutationEvent(
            operation=2,
            add=(Occurrence("new-occ", "new-track"),),
            remove_occurrence_ids=("occ-00000", "occ-00001"),
        )
        config = SimulationConfig(9, 5, ConsumptionModel.parse(3), mutations=(mutation,))
        result = simulate(playlist, OpportunityBalance(), config)
        self.assertEqual(result.metrics["active_occurrence_count"], 9)
        self.assertIn("new-occ", result.actual_exposure_by_occurrence)

    def test_history_reset_scenario_remains_valid(self):
        config = SimulationConfig(11, 10, ConsumptionModel.parse(5), reset_after=5)
        result = simulate(synthetic_playlist(20), RecencyWeight(), config)
        self.assertEqual(result.metrics["history_reset_count"], 1)
        self.assertEqual(sum(result.actual_exposure_by_occurrence.values()), 50)

    def test_extreme_and_clamped_consumption(self):
        for size in (0, 1, 5):
            config = SimulationConfig(1, 3, ConsumptionModel.parse(100))
            result = simulate(synthetic_playlist(size), TrueRandom(), config)
            self.assertEqual(sum(result.actual_exposure_by_occurrence.values()), size * 3)
        self.assertEqual(ConsumptionModel.parse("25%").depth(3), 0)
        with self.assertRaises(ValueError):
            ConsumptionModel.parse("-1")

    def test_duplicate_occurrence_ids_rejected(self):
        playlist = [Occurrence("same", "a"), Occurrence("same", "b")]
        with self.assertRaises(ValueError):
            simulate(playlist, TrueRandom(), SimulationConfig(1, 1, ConsumptionModel.parse(1)))

    def test_smoke_matrix_shape_and_required_values(self):
        scenarios = smoke_matrix()
        self.assertEqual(len(scenarios), 288)
        self.assertEqual({item.playlist_size for item in scenarios}, {100, 250, 500, 1000, 2000, 4000})
        self.assertEqual({item.rotations for item in scenarios}, {10})

    def test_main_matrix_shape_and_required_values(self):
        scenarios = main_matrix()
        self.assertEqual(len(scenarios), 2880)
        self.assertEqual({item.rotations for item in scenarios}, {100})
        self.assertEqual(len({item.seed for item in scenarios}), 20)

    def test_compact_result_omits_large_structures(self):
        config = SimulationConfig(3, 10, ConsumptionModel.parse(10))
        result = simulate(synthetic_playlist(100), TrueRandom(), config)
        compact = result.as_compact_dict()
        self.assertEqual(set(compact), {"metadata", "metrics", "warnings"})
        self.assertNotIn("actual_exposure_by_track", compact)
        self.assertEqual(
            compact["metrics"]["track_coverage_checkpoints"]["10"],
            result.metrics["final_track_coverage"],
        )

    def test_full_consumption_is_not_mislabeled_scheduler_like(self):
        config = SimulationConfig(3, 10, ConsumptionModel.parse(100))
        result = simulate(synthetic_playlist(100), TrueRandom(), config)
        self.assertNotIn("scheduler_like_exposure_range", result.warnings)


if __name__ == "__main__":
    unittest.main()
