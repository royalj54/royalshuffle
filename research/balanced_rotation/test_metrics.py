from __future__ import annotations

import unittest

from research.balanced_rotation.metrics import (
    coefficient_of_variation,
    distribution,
    effective_choice_ratio,
    first_threshold,
    gini,
    normalized_entropy,
    pearson_correlation,
)


class MetricsTests(unittest.TestCase):
    def test_equal_distribution_has_zero_inequality(self):
        self.assertEqual(gini([2, 2, 2]), 0.0)
        self.assertEqual(coefficient_of_variation([2, 2, 2]), 0.0)

    def test_equal_weights_have_full_entropy(self):
        self.assertAlmostEqual(normalized_entropy([1, 1, 1]), 1.0)
        self.assertAlmostEqual(effective_choice_ratio([1, 1, 1]), 1.0)

    def test_distribution_and_threshold(self):
        stats = distribution([0, 1, 2, 3])
        self.assertEqual(stats["mean"], 1.5)
        self.assertEqual(first_threshold([0.2, 0.5, 0.8], 0.5), 2)
        self.assertIsNone(first_threshold([0.2], 0.5))

    def test_correlation(self):
        self.assertAlmostEqual(pearson_correlation([1, 2, 3], [2, 4, 6]), 1.0)
        self.assertIsNone(pearson_correlation([1, 1], [2, 3]))


if __name__ == "__main__":
    unittest.main()
