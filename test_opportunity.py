"""Phase 1 rotation semantics and atomic shrinking-state persistence."""
from copy import deepcopy
from dataclasses import replace
import json
from pathlib import Path
import random
import tempfile
import unittest
from unittest.mock import patch

import app_paths
from artist_separation import ArtistSeparationError
from opportunity import OpportunityError, TARGET_MS, construct_pool, prepare_deal
from opportunity_state import OpportunityStateError, OpportunityStore, validate_state, candidate_rotation
from shuffle_engine import shuffle_items


def track(index, minutes=20, artist="artist"):
    return {"uri": f"spotify:track:{index}", "duration_ms": minutes * 60000,
            "primary_artist_id": artist, "name": f"Track {index}"}


class OpportunityTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "state.json"
        self.store = OpportunityStore(self.path)

    def begin(self, items=None, source="source"):
        return self.store.begin_rotation(source, items if items is not None
                                         else [track(i) for i in range(8)])

    def prepare(self, separate=False):
        return self.store.prepare_next_deal("source", artist_separation=separate,
                                            rng=random.Random(17))

    def test_unique_pool_first_metadata_and_distinct_uris(self):
        first = track("A", 10, "first")
        duplicate = track("A", 30, "second")
        pool = construct_pool([first, track("B"), duplicate, track("C")])
        self.assertEqual(list(pool), ["spotify:track:A", "spotify:track:B", "spotify:track:C"])
        self.assertEqual(pool[first["uri"]], {k: first[k] for k in
                                           ("duration_ms", "primary_artist_id", "name")})
        self.assertEqual(first, track("A", 10, "first"))

    def test_true_random_still_preserves_duplicate_occurrences(self):
        items = [track("A"), track("A"), track("B")]
        result = shuffle_items(items)
        self.assertEqual(sorted(x["uri"] for x in result), sorted(x["uri"] for x in items))
        self.assertEqual(len(result), 3)

    def test_crossing_track_and_normal_overshoot(self):
        self.begin([track(i, 23) for i in range(5)])
        deal = self.prepare()
        self.assertEqual(len(deal.tracks), 3)
        self.assertEqual(deal.duration_ms, 69 * 60000)
        self.assertGreaterEqual(deal.duration_ms, TARGET_MS)
        self.assertLess(deal.duration_ms - deal.tracks[-1].duration_ms, TARGET_MS)

    def test_short_final_and_singleton_and_extremely_long(self):
        for minutes in ([35, 12], [12], [300]):
            with self.subTest(minutes=minutes):
                store = OpportunityStore(Path(self.temp.name) / f"{len(minutes)}-{minutes[0]}.json")
                store.begin_rotation("source", [track(i, m) for i, m in enumerate(minutes)])
                deal = store.prepare_next_deal("source", rng=random.Random(1))
                self.assertEqual(len(deal.tracks), len(minutes))
                self.assertEqual(deal.duration_ms, sum(minutes) * 60000)
                self.assertTrue(store.commit_deal(deal)["complete"])

    def test_prepare_is_read_only_commit_shrinks_and_reload(self):
        self.begin()
        before = self.path.read_bytes()
        deal = self.prepare()
        self.assertEqual(self.path.read_bytes(), before)
        progress = self.store.commit_deal(deal)
        self.assertEqual(progress["completed_sessions"], 1)
        self.assertEqual(progress["remaining_unique_count"], 5)
        remaining = OpportunityStore(self.path).load()["sources"]["source"]["active"]["undealt"]
        self.assertEqual(set(remaining), {track(i)["uri"] for i in range(8)}
                         - {t.uri for t in deal.tracks})
        raw = json.loads(self.path.read_text())
        self.assertEqual(set(raw["sources"]["source"]), {"active", "pending"})
        self.assertEqual(set(raw["sources"]["source"]["active"]),
                         {"rotation_id", "started_at", "original_unique_count",
                          "completed_sessions", "undealt"})
        for selected in deal.tracks:
            self.assertNotIn(selected.uri, self.path.read_text())

    def test_whole_rotation_exactly_once_final_short_no_borrow(self):
        self.begin()
        committed = []
        sizes = []
        while not self.store.inspect_progress("source")["complete"]:
            deal = self.prepare()
            sizes.append(len(deal.tracks))
            self.assertFalse(set(committed) & {t.uri for t in deal.tracks})
            committed.extend(t.uri for t in deal.tracks)
            self.store.commit_deal(deal)
        self.assertEqual(sizes, [3, 3, 2])
        self.assertEqual(len(committed), len(set(committed)))
        self.assertEqual(set(committed), {track(i)["uri"] for i in range(8)})
        with self.assertRaises(OpportunityError):
            self.prepare()

    def test_duplicate_only_single_opportunity(self):
        self.begin([track("A"), track("A")])
        self.assertEqual(self.store.inspect_progress("source")["original_unique_count"], 1)
        self.assertEqual(len(self.prepare().tracks), 1)

    def test_source_snapshot_and_new_rotation_after_exhaustion(self):
        original = [track("A"), track("B")]
        self.begin(original)
        original[:] = [track("NEW")]
        deal = self.prepare()
        self.assertEqual({t.uri for t in deal.tracks}, {"spotify:track:A", "spotify:track:B"})
        old_id = deal.rotation_id
        self.store.commit_deal(deal)
        new = self.begin(original)
        self.assertNotEqual(new["rotation_id"], old_id)
        self.assertEqual(set(new["undealt"]), {"spotify:track:NEW"})

    def test_source_name_is_not_stored_or_needed_and_ids_are_isolated(self):
        self.begin([track("A")], "source")
        self.begin([track("B")], "other")
        self.assertEqual(self.prepare().tracks[0].uri, "spotify:track:A")
        self.assertEqual(self.store.prepare_next_deal("other").tracks[0].uri, "spotify:track:B")

    def test_cannot_replace_unexhausted_rotation(self):
        self.begin()
        before = self.path.read_bytes()
        with self.assertRaises(OpportunityError):
            self.begin([track("NEW")])
        self.assertEqual(self.path.read_bytes(), before)

    def test_stale_second_preparation_and_double_commit_are_rejected(self):
        self.begin()
        first, second = self.prepare(), self.prepare()
        self.store.commit_deal(first)
        before = self.path.read_bytes()
        for deal in (first, second):
            with self.assertRaises(OpportunityStateError):
                self.store.commit_deal(deal)
            self.assertEqual(self.path.read_bytes(), before)

    def test_altered_duplicate_or_foreign_membership_rejected(self):
        self.begin()
        deal = self.prepare()
        for tracks in ((), (deal.tracks[0],), (deal.tracks[0], deal.tracks[0]),
                       (replace(deal.tracks[0], uri="spotify:track:foreign"),),
                       (replace(deal.tracks[0], duration_ms=1),)):
            with self.subTest(tracks=tracks), self.assertRaises(OpportunityError):
                self.store.commit_deal(replace(deal, tracks=tracks))
        self.assertEqual(self.store.inspect_progress("source")["completed_sessions"], 0)

    def test_artist_separation_preserves_membership_duration_and_depletion(self):
        self.begin([track(i, 15, "A" if i % 2 else "B") for i in range(8)])
        ordinary = self.prepare()
        separated = self.prepare(True)
        self.assertEqual({t.uri for t in ordinary.tracks}, {t.uri for t in separated.tracks})
        self.assertEqual(ordinary.duration_ms, separated.duration_ms)
        self.store.commit_deal(separated)
        remaining = self.store.load()["sources"]["source"]["active"]["undealt"]
        self.assertFalse(set(remaining) & {t.uri for t in separated.tracks})

    def test_artist_separation_singleton_and_missing_artist(self):
        self.begin([track("A", 12)])
        self.assertEqual(len(self.prepare(True).tracks), 1)
        other = OpportunityStore(Path(self.temp.name) / "missing.json")
        other.begin_rotation("source", [track("A", artist=None)])
        before = other.path.read_bytes()
        with self.assertRaises(ArtistSeparationError):
            other.prepare_next_deal("source", artist_separation=True)
        self.assertEqual(other.path.read_bytes(), before)

    def test_invalid_or_empty_input_does_not_create_state(self):
        invalid = [[], [None], [{}], [track("A") | {"uri": None}], [track("A") | {"uri": []}],
                   [track("A") | {"uri": "spotify:local:A"}],
                   [track("A") | {"uri": "spotify:episode:A"}],
                   [track("A") | {"uri": ""}]]
        invalid += [[track("A") | {"duration_ms": d}] for d in (None, 0, -1, True, 1.5)]
        for items in invalid:
            with self.subTest(items=items), self.assertRaises(OpportunityError):
                self.begin(items)
            self.assertFalse(self.path.exists())

    def test_malformed_schema_and_duplicate_json_keys_fail_without_overwrite(self):
        for contents in ('not json', '[]', '{"schema_version":999,"sources":{}}',
                         '{"schema_version":true,"sources":{}}',
                         '{"schema_version":1,"sources":{},"sources":{}}'):
            self.path.write_text(contents)
            with self.assertRaises(OpportunityStateError):
                self.store.load()
            with self.assertRaises(OpportunityStateError):
                self.begin()
            self.assertEqual(self.path.read_text(), contents)

    def test_invalid_state_structures_and_progress(self):
        self.begin()
        valid = self.store.load()
        mutations = [
            lambda s: s["sources"].update({"bad id": s["sources"]["source"]}),
            lambda s: s["sources"]["source"].update(pending={}),
            lambda s: s["sources"]["source"].update(active=None),
        ]
        fields = {"rotation_id": "bad", "started_at": "yesterday",
                  "original_unique_count": 0, "completed_sessions": -1,
                  "undealt": [], "extra": True}
        for field, value in fields.items():
            mutations.append(lambda s, f=field, v=value:
                             s["sources"]["source"]["active"].update({f: v}))
        for mutate in mutations:
            state = deepcopy(valid)
            mutate(state)
            with self.subTest(state=state), self.assertRaises(OpportunityStateError):
                validate_state(state)
        for original, sessions, remaining in ((7, 0, 8), (8, 1, 8),
                                               (8, 0, 7), (8, 3, 6), (8, 0, 0)):
            state = deepcopy(valid)
            active = state["sources"]["source"]["active"]
            active.update(original_unique_count=original, completed_sessions=sessions)
            active["undealt"] = dict(list(active["undealt"].items())[:remaining])
            with self.subTest(progress=(original, sessions, remaining)):
                with self.assertRaises(OpportunityStateError):
                    validate_state(state)

    def test_invalid_saved_track_records(self):
        self.begin()
        for change in ({"duration_ms": True}, {"duration_ms": 0},
                       {"primary_artist_id": ""}, {"name": 5}, {"extra": 1}):
            state = self.store.load()
            state["sources"]["source"]["active"]["undealt"]["spotify:track:0"].update(change)
            with self.subTest(change=change), self.assertRaises(OpportunityStateError):
                validate_state(state)

    def test_atomic_replace_failure_preserves_disk_and_cleans_temp(self):
        self.begin()
        before = self.path.read_bytes()
        deal = self.prepare()
        with patch("opportunity_state.os.replace", side_effect=OSError("blocked")):
            with self.assertRaises(OpportunityStateError):
                self.store.commit_deal(deal)
        self.assertEqual(self.path.read_bytes(), before)
        self.assertEqual(list(self.path.parent.glob("*.tmp")), [])
        self.assertEqual(self.store.inspect_progress("source")["completed_sessions"], 0)

    def test_flush_fsync_and_unique_same_directory_temporary_file(self):
        self.begin()
        import os
        with patch("opportunity_state.os.fsync", wraps=os.fsync) as fsync, patch(
                "opportunity_state.os.replace", wraps=os.replace) as replace_file:
            self.store.commit_deal(self.prepare())
        fsync.assert_called_once()
        temporary, destination = replace_file.call_args.args
        self.assertEqual(temporary.parent, self.path.parent)
        self.assertNotEqual(temporary, self.path)
        self.assertEqual(destination, self.path)

    def test_fsync_failure_preserves_disk(self):
        self.begin()
        before = self.path.read_bytes()
        with patch("opportunity_state.os.fsync", side_effect=OSError("failed")):
            with self.assertRaises(OpportunityStateError):
                self.store.commit_deal(self.prepare())
        self.assertEqual(self.path.read_bytes(), before)
        self.assertEqual(list(self.path.parent.glob("*.tmp")), [])

    def test_pending_replacement_requires_exact_authorization_and_candidate(self):
        self.begin()
        active = self.store.load()["sources"]["source"]["active"]
        pending = self.store.stage_pending(self.prepare(), creation_name="Deal 1")
        before = self.path.read_bytes()
        candidate = candidate_rotation([track(99)])
        deal = prepare_deal("source", candidate)
        for token, selected_candidate in ((None, candidate), ("wrong", candidate),
                                          (pending["operation_id"], None)):
            with self.assertRaises(OpportunityStateError):
                self.store.stage_pending(deal, candidate=selected_candidate, creation_name="New Deal 1",
                                         replace_pending_operation_id=token)
            self.assertEqual(self.path.read_bytes(), before)
        replacement = self.store.stage_pending(deal, candidate=candidate, creation_name="New Deal 1",
                                              replace_pending_operation_id=pending["operation_id"])
        source = self.store.load()["sources"]["source"]
        self.assertEqual(source["active"], active)
        self.assertNotEqual(replacement["operation_id"], pending["operation_id"])
        self.assertEqual(replacement["uris"], ["spotify:track:99"])
        self.assertEqual(source["pending"], replacement)

    def test_path_helper_windows_and_xdg(self):
        with patch("app_paths._is_windows", return_value=True), patch(
                "app_paths.Path.home", return_value=Path("home")):
            self.assertEqual(app_paths.opportunity_state_file(),
                             Path("home") / ".royalshuffle_opportunity_state.json")
        with patch("app_paths._is_windows", return_value=False), patch(
                "app_paths.state_folder", return_value=Path("state")):
            self.assertEqual(app_paths.opportunity_state_file(),
                             Path("state") / "opportunity_state.json")


if __name__ == "__main__":
    unittest.main()
