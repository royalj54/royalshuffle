"""Dependency-free metrics for Balanced Rotation experiments."""

from __future__ import annotations

import math
import statistics
from typing import Iterable, Sequence


def distribution(values: Iterable[int | float]) -> dict[str, float]:
    data = list(values)
    if not data:
        return {key: 0.0 for key in ("minimum", "maximum", "mean", "median", "stddev", "variance")}
    return {
        "minimum": float(min(data)),
        "maximum": float(max(data)),
        "mean": float(statistics.fmean(data)),
        "median": float(statistics.median(data)),
        "stddev": float(statistics.pstdev(data)),
        "variance": float(statistics.pvariance(data)),
    }


def coefficient_of_variation(values: Iterable[int | float]) -> float:
    data = list(values)
    mean = statistics.fmean(data) if data else 0.0
    return statistics.pstdev(data) / mean if mean else 0.0


def gini(values: Iterable[int | float]) -> float:
    data = sorted(float(value) for value in values)
    if not data or sum(data) == 0:
        return 0.0
    n = len(data)
    weighted = sum((index + 1) * value for index, value in enumerate(data))
    return (2 * weighted) / (n * sum(data)) - (n + 1) / n


def normalized_entropy(weights: Sequence[float]) -> float:
    if len(weights) <= 1:
        return 1.0
    total = sum(weights)
    probabilities = [weight / total for weight in weights]
    entropy = -sum(p * math.log(p) for p in probabilities if p > 0)
    return entropy / math.log(len(weights))


def effective_choice_ratio(weights: Sequence[float]) -> float:
    if len(weights) <= 1:
        return 1.0
    total = sum(weights)
    entropy = -sum((w / total) * math.log(w / total) for w in weights if w > 0)
    return math.exp(entropy) / len(weights)


def pearson_correlation(left: Sequence[float], right: Sequence[float]) -> float | None:
    if len(left) != len(right) or len(left) < 2:
        return None
    left_mean, right_mean = statistics.fmean(left), statistics.fmean(right)
    numerator = sum((x - left_mean) * (y - right_mean) for x, y in zip(left, right))
    left_ss = sum((x - left_mean) ** 2 for x in left)
    right_ss = sum((y - right_mean) ** 2 for y in right)
    denominator = math.sqrt(left_ss * right_ss)
    return numerator / denominator if denominator else None


def first_threshold(coverage: Sequence[float], threshold: float) -> int | None:
    return next((index + 1 for index, value in enumerate(coverage) if value >= threshold), None)
