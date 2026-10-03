from __future__ import annotations

import math
import random
import unittest

from research.balanced_rotation.algorithms import (
    DecayingExposureScore,
    OpportunityBalance,
    RecencyWeight,
    TrueRandom,
    weighted_permutation,
)
from research.balanced_rotation.model import Occurrence, synthetic_playlist


class AlgorithmTests(unittest.TestCase):
    def test_true_random_equal_treatment(self):
        playlist = synthetic_playlist(20)
        self.assertEqual(TrueRandom().weights(playlist, 0), [1.0] * 20)

    def test_every_algorithm_returns_valid_permutation(self):
        playlist = synthetic_playlist(50)
        for algorithm in (TrueRandom(), RecencyWeight(), DecayingExposureScore(), OpportunityBalance()):
            weights = algorithm.weights(playlist, 0)
            generated = weighted_permutation(playlist, weights, random.Random(123))
            self.assertCountEqual(generated, playlist)
            self.assertEqual(len(generated), len(set(generated)))

    def test_weights_are_positive_finite_and_bounded(self):
        playlist = synthetic_playlist(30)
        for algorithm in (RecencyWeight(), DecayingExposureScore(), OpportunityBalance()):
            for operation in range(50):
                weights = algorithm.weights(playlist, operation)
                self.assertTrue(all(math.isfinite(value) and value > 0 for value in weights))
                generated = weighted_permutation(playlist, weights, random.Random(operation))
                algorithm.observe(generated, generated[:10], operation)

    def test_recency_never_exposed_semantics_are_explicit(self):
        playlist = synthetic_playlist(2)
        algorithm = RecencyWeight()
        self.assertEqual(algorithm.weights(playlist, 0), [1.35, 1.35])
        algorithm.observe(playlist, playlist[:1], 0)
        weights = algorithm.weights(playlist, 1)
        self.assertLess(weights[0], weights[1])

    def test_opportunity_algorithm_does_not_observe_actual_listening(self):
        playlist = synthetic_playlist(10)
        left, right = OpportunityBalance(), OpportunityBalance()
        left.observe(playlist, playlist[:1], 0)
        right.observe(playlist, playlist[:9], 0)
        self.assertEqual(left.scores, right.scores)

    def test_reset_restores_fresh_state(self):
        playlist = synthetic_playlist(10)
        for algorithm in (RecencyWeight(), DecayingExposureScore(), OpportunityBalance()):
            algorithm.observe(playlist, playlist[:5], 0)
            algorithm.reset()
            fresh = type(algorithm)()
            self.assertEqual(algorithm.weights(playlist, 2), fresh.weights(playlist, 2))

    def test_invalid_weight_rejected(self):
        playlist = synthetic_playlist(1)
        for value in (0.0, -1.0, float("nan"), float("inf")):
            with self.assertRaises(ValueError):
                weighted_permutation(playlist, [value], random.Random(1))


if __name__ == "__main__":
    unittest.main()
