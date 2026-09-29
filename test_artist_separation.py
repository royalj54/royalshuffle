from collections import Counter
from copy import deepcopy
from itertools import groupby, permutations, product
import random
import unittest

from artist_separation import ArtistSeparationError, separate_artists


def occurrences(counts):
    return [{"primary_artist_id": artist, "uri": f"spotify:track:{artist}", "occurrence": index}
            for artist, count in counts.items() for index in range(count)]


def metrics(artists):
    runs = [len(list(group)) for _, group in groupby(artists)]
    return sum(size - 1 for size in runs), max(runs, default=0)


class ArtistSeparationTests(unittest.TestCase):
    def check_order(self, counts, seed=0):
        items = occurrences(counts)
        before = deepcopy(items)
        result = separate_artists(items, random.Random(seed))
        self.assertEqual(items, before)
        self.assertEqual(Counter(map(id, items)), Counter(map(id, result)))
        self.assertEqual(Counter(item["uri"] for item in result), Counter(item["uri"] for item in items))
        for artist in counts:
            self.assertEqual([id(item) for item in result if item["primary_artist_id"] == artist],
                             [id(item) for item in items if item["primary_artist_id"] == artist])
        n = sum(counts.values())
        m = max(counts.values(), default=0)
        adjacency, longest = metrics([item["primary_artist_id"] for item in result])
        self.assertEqual(adjacency, max(0, 2 * m - n - 1))
        self.assertEqual(longest, (m + n - m) // (n - m + 1) if n else 0)
        return result

    def test_representative_distributions_and_boundary(self):
        cases = [dict(zip("ABCDEF", [8, 3, 3, 2, 2, 2])), {"A": 12, "B": 2, "C": 2, "D": 1}]
        for tail in (19, 20, 21):
            cases.append({"A": 36, "B": 15, "C": 8, "D": 6, "E": tail - 14})
        for counts in cases:
            for seed in (0, 1, 17, 42):
                with self.subTest(counts=counts, seed=seed):
                    self.check_order(counts, seed)

    def test_empty_singleton_all_same_all_unique_equal_counts(self):
        for counts in ({}, {"A": 1}, {"A": 13}, dict.fromkeys("ABCDEFG", 1),
                       {"A": 7, "B": 7}, dict.fromkeys("ABCD", 5)):
            with self.subTest(counts=counts):
                self.check_order(counts)

    def test_small_exhaustive_arrangement_oracle(self):
        for counts_tuple in product(range(5), repeat=3):
            if not 1 <= sum(counts_tuple) <= 7:
                continue
            counts = {artist: count for artist, count in zip("ABC", counts_tuple) if count}
            labels = tuple(item["primary_artist_id"] for item in occurrences(counts))
            optimum = min(metrics(order) for order in set(permutations(labels)))
            for seed in (0, 5):
                with self.subTest(counts=counts, seed=seed):
                    result = self.check_order(counts, seed)
                    self.assertEqual(metrics([item["primary_artist_id"] for item in result]), optimum)

    def test_dominant_blocks_balanced_and_extras_randomized(self):
        outputs = []
        for seed in (0, 1):
            result = self.check_order({"A": 14, "B": 2, "C": 2}, seed)
            blocks = [len(list(group)) for artist, group in groupby(
                item["primary_artist_id"] for item in result) if artist == "A"]
            self.assertEqual(sorted(blocks), [2, 3, 3, 3, 3])
            outputs.append(blocks)
        self.assertNotEqual(*outputs)

    def test_ties_allow_multiple_orders_without_rigid_three_artist_cycle(self):
        orders = [tuple(item["primary_artist_id"] for item in self.check_order(dict.fromkeys("ABC", 4), seed))
                  for seed in (0, 1, 2)]
        self.assertGreater(len(set(orders)), 1)
        self.assertTrue(any(order != order[:3] * 4 for order in orders))

    def test_repeated_same_object_is_still_multiple_occurrences(self):
        a, b = occurrences({"A": 1, "B": 1})
        result = separate_artists([a, a, b], random.Random(0))
        self.assertEqual([id(item) for item in result], [id(a), id(b), id(a)])
        # Linear scope: the equal first/last artists are not adjacent.
        self.assertEqual(metrics([item["primary_artist_id"] for item in result]), (0, 1))

    def test_identity_is_id_not_display_name_or_group_relationship(self):
        items = occurrences({"BLACKPINK-id": 3, "LISA-id": 2, "other-id": 1})
        for item in items:
            item["primary_artist_name"] = "Same display name"
            item["artists"] = "BLACKPINK, LISA"
        result = separate_artists(items, random.Random(0))
        self.assertEqual(metrics([item["primary_artist_id"] for item in result])[0], 0)
        self.assertEqual(Counter(map(id, items)), Counter(map(id, result)))

    def test_missing_identity_rejected_without_mutating_input(self):
        for invalid in (None, "", " \t", 5, False, [], {}):
            with self.subTest(invalid=invalid):
                items = [{"primary_artist_id": "valid"}, {"primary_artist_id": invalid}]
                before = deepcopy(items)
                with self.assertRaisesRegex(ArtistSeparationError, "Disable Artist Separation"):
                    separate_artists(items, random.Random(0))
                self.assertEqual(items, before)
