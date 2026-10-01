"""Phase 2 transaction boundaries, raw verification, and shared writer safety."""
from copy import deepcopy
import json
import os
from pathlib import Path
import random
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch

from requests import HTTPError
from opportunity_state import OpportunityStore, OpportunityStateError, validate_state
from opportunity_workflow import (OpportunityWorkflow, PendingSessionError, UnknownCreationError,
                                  OPPORTUNITY_PLAYLIST_DESCRIPTION)
import playlist_registry as registry
from spotify_client import SpotifyClient, SpotifyRetryLaterError
from writer_lock import writer_lock, WriterBusyError


def item(i, minutes=20):
    return {"uri": f"spotify:track:{i}", "duration_ms": minutes * 60000,
            "primary_artist_id": "A" if i % 2 else "B"}


def http_error(status):
    error = HTTPError(f"HTTP {status}")
    error.response = Mock(status_code=status)
    return error


class FakeSpotify:
    def __init__(self, store):
        self.store = store
        self.source = [item(i) for i in range(8)]
        self.playlists = {}
        self.contents = {}
        self.events = []
        self.fail = {}
        self.omit_listing = False
        self.raw_override = None
        self.counter = 0

    def _event(self, action, value):
        self.events.append((action, value))
        if action in ("create", "clear", "add"):
            pending = self.store.pending("source")
            if pending is None:
                raise AssertionError("Spotify mutation before durable pending")
            selected_from = pending["candidate"] or self.store.load()["sources"]["source"]["active"]
            if not set(pending["uris"]) <= set(selected_from["undealt"]):
                raise AssertionError("Premature depletion")
        failure = self.fail.get(action)
        if failure:
            raise failure

    def get_playlist_items(self, source):
        self._event("source", source)
        return deepcopy(self.source)

    def get_playlists(self):
        self._event("listing", None)
        return [] if self.omit_listing else list(self.playlists.values())

    def get_playlist(self, output):
        self._event("lookup", output)
        if output == "source":
            return {"id": "source", "name": "Source"}
        if output not in self.playlists:
            raise http_error(404)
        return self.playlists[output]

    def create_playlist(self, name, description="", public=False):
        self._event("create", (name, description, public))
        self.counter += 1
        output = f"out{self.counter}"
        playlist = {"id": output, "name": name}
        self.playlists[output] = playlist
        self.contents[output] = []
        if self.fail.get("lost_create"):
            raise self.fail["lost_create"]
        return playlist

    def clear_playlist(self, output):
        self._event("clear", output)
        self.contents[output] = []

    def add_playlist_items(self, output, uris):
        self._event("add", tuple(uris))
        if self.fail.get("partial"):
            self.contents[output] = list(uris[:1])
            raise self.fail["partial"]
        self.contents[output] = list(uris)
        if self.fail.get("lost_add"):
            raise self.fail["lost_add"]
        return len(uris)

    def submit_opportunity_deal(self, evidence, uris, persist_receipt):
        from delivery_receipts import payload_digest
        self.clear_playlist(evidence["output_id"])
        persist_receipt({"attempt_id": evidence["attempt_id"], "index": 0, "method": "PUT", "start": 0, "end": 0,
                        "payload_digest": payload_digest([]), "status": 200, "snapshot_id": "clear"})
        self.add_playlist_items(evidence["output_id"], uris)
        for index, start in enumerate(range(0, len(uris), 100), 1):
            end = min(start + 100, len(uris))
            persist_receipt({"attempt_id": evidence["attempt_id"], "index": index, "method": "POST", "start": start, "end": end,
                            "payload_digest": payload_digest(uris[start:end]), "status": 201, "snapshot_id": f"add{index}"})

    def get_playlist_items_raw(self, output):
        self._event("raw", output)
        return self.raw_override if self.raw_override is not None else list(self.contents[output])


class OpportunityWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        patcher = patch.object(registry, "REGISTRY_FILE", self.root / "registry.json")
        patcher.start()
        self.addCleanup(patcher.stop)
        self.store = OpportunityStore(self.root / "opportunity.json")
        self.spotify = FakeSpotify(self.store)
        self.workflow = OpportunityWorkflow(self.spotify, self.store)
        self.name = Mock(return_value="  Chosen  ")


    def generate(self, **kwargs):
        return self.workflow.generate_next_session(
            "source", output_name_callback=self.name, rng=random.Random(4), **kwargs)


    def assert_pending_blocked(self):
        with self.assertRaises(PendingSessionError):
            self.generate()
        with self.assertRaises(PendingSessionError):
            self.workflow.start_new_rotation("source", output_name_callback=self.name)
        with self.assertRaises(OpportunityStateError):
            self.store.prepare_next_deal("source")


    def test_cancel_and_blank_creation_have_no_state_registry_or_spotify_mutation(self):
        for name in (None, "", "  ", "\t\n"):
            self.name.return_value = name
            if name is None:
                self.assertIsNone(self.generate())
            else:
                with self.assertRaises(ValueError):
                    self.generate()
            self.assertFalse(self.store.path.exists())
            self.assertFalse(registry.REGISTRY_FILE.exists())
            self.assertFalse(any(action in ("create", "clear", "add")
                                 for action, _ in self.spotify.events))


    def test_pending_persistence_failure_prevents_all_mutation(self):
        with patch.object(self.store, "_save", side_effect=OpportunityStateError("disk")):
            with self.assertRaises(OpportunityStateError):
                self.generate()
        self.assertFalse(registry.REGISTRY_FILE.exists())
        self.assertFalse(any(action in ("create", "clear", "add")
                             for action, _ in self.spotify.events))


    def test_clean_creation_rejection_retries_exact_pending(self):
        self.spotify.fail["create"] = http_error(400)
        with self.assertRaises(HTTPError):
            self.generate()
        pending = self.store.pending("source")
        self.assertEqual(pending["phase"], "staged")
        self.assert_pending_blocked()
        self.spotify.fail.clear()
        result = self.workflow.resume_pending_session("source")
        self.assertEqual(result.uris, tuple(pending["uris"]))
        self.assertEqual(self.name.call_count, 1)


    def test_creation_rate_limit_rejections_resume_exact_pending(self):
        for reason in ("long_delay", "quota", "exhausted"):
            with self.subTest(reason=reason):
                response = Mock(status_code=429, headers={"Retry-After": "0"})
                response.json.return_value = {}
                if reason == "long_delay":
                    response.headers["Retry-After"] = "61"
                elif reason == "quota":
                    response.json.return_value = {"error": {"reason": "QUOTA_EXCEEDED"}}
                client = SpotifyClient("test-token")
                with patch.object(self.spotify, "create_playlist", side_effect=client.create_playlist), patch(
                        "spotify_client.requests.post", return_value=response) as post, patch(
                        "spotify_client.time.sleep"), patch("spotify_client.log_debug"):
                    with self.assertRaises(SpotifyRetryLaterError):
                        self.generate()
                self.assertEqual(post.call_count, 4 if reason == "exhausted" else 1)
                pending = self.store.pending("source")
                self.assertEqual(pending["phase"], "staged")
                self.assertIsNone(pending["output_id"])
                self.assert_pending_blocked()
                before = len(self.spotify.events)
                result = self.workflow.resume_pending_session("source")
                self.assertEqual(result.uris, tuple(pending["uris"]))
                self.assertIsNone(self.store.pending("source"))
                self.assertFalse(any(action == "source" for action, _ in self.spotify.events[before:]))
                self.assertEqual(self.name.call_count, 1)
                self.name.reset_mock()
                self.store.path.unlink()
                registry.REGISTRY_FILE.unlink(missing_ok=True)
                self.spotify.playlists.clear()
                self.spotify.contents.clear()
                self.spotify.events.clear()


    def test_lost_creation_response_unknown_identity_fails_closed(self):
        self.spotify.fail["lost_create"] = RuntimeError("lost response")
        with self.assertRaises(RuntimeError):
            self.generate()
        self.assertEqual(self.store.pending("source")["phase"], "creating")
        self.assert_pending_blocked()
        with self.assertRaises(UnknownCreationError):
            self.workflow.resume_pending_session("source")
        self.assertEqual(len(self.spotify.playlists), 1)
        self.assertFalse(any(action in ("clear", "add") for action, _ in self.spotify.events))


    def test_output_identity_persistence_failure_fails_closed(self):
        original = self.store._save
        def fail_identity(state):
            pending = state["sources"]["source"]["pending"]
            if pending and pending["phase"] == "output_known":
                raise OpportunityStateError("disk")
            return original(state)
        with patch.object(self.store, "_save", side_effect=fail_identity):
            with self.assertRaises(UnknownCreationError):
                self.generate()
        self.assertEqual(self.store.pending("source")["phase"], "creating")
        self.assertFalse(registry.REGISTRY_FILE.exists())
        with self.assertRaises(UnknownCreationError):
            self.workflow.resume_pending_session("source")
        self.assertFalse(any(action in ("clear", "add") for action, _ in self.spotify.events))


    def test_clear_failure_preserves_pool_then_resume_same_deal(self):
        self.seed()
        before = deepcopy(self.store.load()["sources"]["source"]["active"])
        self.spotify.fail["clear"] = RuntimeError("clear failed")
        with self.assertRaises(RuntimeError):
            self.generate()
        pending = self.store.pending("source")
        self.assertEqual(self.store.load()["sources"]["source"]["active"], before)
        self.assert_pending_blocked()
        self.spotify.fail.clear()
        result = self.workflow.resume_pending_session("source")
        self.assertEqual(result.uris, tuple(pending["uris"]))


    def test_partial_population_rebuilds_identical_order(self):
        self.seed()
        self.spotify.fail["partial"] = RuntimeError("partial")
        with self.assertRaises(RuntimeError):
            self.generate()
        pending = self.store.pending("source")
        self.assertEqual(self.store.inspect_progress("source")["remaining_unique_count"], 8)
        self.assert_pending_blocked()
        self.spotify.fail.clear()
        result = self.workflow.resume_pending_session("source")
        self.assertEqual(result.uris, tuple(pending["uris"]))
        adds = [value for action, value in self.spotify.events if action == "add"]
        self.assertEqual(adds, [tuple(pending["uris"])] * 2)


    def test_population_exception_without_effect_stays_pending(self):
        self.seed()
        self.spotify.fail["add"] = RuntimeError("population failure")
        with self.assertRaises(RuntimeError):
            self.generate()
        self.assertEqual(self.store.inspect_progress("source")["remaining_unique_count"], 8)
        self.assertEqual(self.store.pending("source")["phase"], "populating")
        self.assert_pending_blocked()


    def test_lost_population_response_exact_readback_finalizes_without_writes(self):
        self.seed()
        self.spotify.fail["lost_add"] = RuntimeError("lost response")
        with self.assertRaises(RuntimeError):
            self.generate()
        pending = self.store.pending("source")
        mutations_before = [event for event in self.spotify.events if event[0] in ("create", "clear", "add")]
        result = self.workflow.resume_pending_session("source")
        self.assertEqual(result.uris, tuple(pending["uris"]))
        self.assertEqual([event for event in self.spotify.events if event[0] in ("create", "clear", "add")],
                         mutations_before)


    def test_acknowledged_delivery_does_not_require_readback(self):
        self.seed()
        self.spotify.fail["raw"] = AssertionError("readback is not a commit gate")
        result = self.generate()
        self.assertEqual(result.progress["completed_sessions"], 1)
        self.assertIsNone(self.store.pending("source"))


    def test_recovery_readback_failure_fails_closed_without_writes(self):
        self.seed()
        self.spotify.fail["lost_add"] = RuntimeError("lost")
        with self.assertRaises(RuntimeError):
            self.generate()
        self.spotify.fail["raw"] = RuntimeError("read failed")
        before = self.store.path.read_bytes()
        events_before = len(self.spotify.events)
        with self.assertRaises(RuntimeError):
            self.workflow.resume_pending_session("source")
        self.assertEqual(self.store.path.read_bytes(), before)
        self.assertFalse(any(action in ("clear", "add") for action, _
                             in self.spotify.events[events_before:]))


    def test_final_local_commit_failure_resumes_without_rewrite(self):
        self.seed()
        with patch.object(self.store, "finalize_pending", side_effect=OpportunityStateError("disk")):
            with self.assertRaises(OpportunityStateError):
                self.generate()
        self.assertEqual(self.store.pending("source")["phase"], "delivered")
        self.assertEqual(self.store.inspect_progress("source")["remaining_unique_count"], 8)
        mutations = [event for event in self.spotify.events if event[0] in ("clear", "add")]
        self.workflow.resume_pending_session("source")
        self.assertEqual([event for event in self.spotify.events if event[0] in ("clear", "add")],
                         mutations)
        self.assertIsNone(self.store.pending("source"))


    def test_natural_rollover_success_uses_fresh_snapshot(self):
        self.seed()
        while not self.store.inspect_progress("source")["complete"]:
            self.generate()
        old = self.store.load()["sources"]["source"]["active"]
        self.spotify.source = [item(99)]
        result = self.generate()
        active = self.store.load()["sources"]["source"]["active"]
        self.assertNotEqual(active["rotation_id"], old["rotation_id"])
        self.assertEqual(result.uris, ("spotify:track:99",))
        self.assertTrue(result.progress["complete"])
        self.assertEqual(result.progress["completed_sessions"], 1)


    def test_failed_rollover_preserves_completed_active_and_candidate(self):
        self.seed()
        while not self.store.inspect_progress("source")["complete"]:
            self.generate()
        old = deepcopy(self.store.load()["sources"]["source"]["active"])
        self.spotify.source = [item(99)]
        self.spotify.fail["clear"] = RuntimeError("failure")
        with self.assertRaises(RuntimeError):
            self.generate()
        source = self.store.load()["sources"]["source"]
        self.assertEqual(source["active"], old)
        self.assertEqual(set(source["pending"]["candidate"]["undealt"]), {"spotify:track:99"})
        self.spotify.fail.clear()
        self.workflow.resume_pending_session("source")
        self.assertNotEqual(self.store.load()["sources"]["source"]["active"]["rotation_id"],
                            old["rotation_id"])


    def test_empty_rollover_preserves_old_state(self):
        self.seed()
        while not self.store.inspect_progress("source")["complete"]:
            self.generate()
        before = self.store.path.read_bytes()
        for items in ([], [item(99) | {"is_local": True}]):
            self.spotify.source = items
            with self.assertRaises(ValueError):
                self.generate()
            self.assertEqual(self.store.path.read_bytes(), before)


    def test_explicit_new_rotation_preserves_old_until_delivery_then_replaces(self):
        self.seed()
        old = deepcopy(self.store.load()["sources"]["source"]["active"])
        self.spotify.source = [item(99), item(100)]
        self.spotify.fail["lost_add"] = RuntimeError("lost")
        with self.assertRaises(RuntimeError):
            self.workflow.start_new_rotation("source", output_name_callback=self.name)
        source = self.store.load()["sources"]["source"]
        self.assertEqual(source["active"], old)
        self.assertEqual(source["pending"]["action"], "new_rotation")
        self.assert_pending_blocked()
        result = self.workflow.resume_pending_session("source")
        self.assertEqual(set(result.uris), {"spotify:track:99", "spotify:track:100"})
        self.assertNotEqual(self.store.load()["sources"]["source"]["active"]["rotation_id"],
                            old["rotation_id"])


    def test_new_rotation_failure_before_pending_preserves_active(self):
        self.seed()
        before = self.store.path.read_bytes()
        self.spotify.source = []
        with self.assertRaises(ValueError):
            self.workflow.start_new_rotation("source", output_name_callback=self.name)
        self.assertEqual(self.store.path.read_bytes(), before)


    def test_malformed_pending_fails_safely_and_never_overwrites(self):
        self.seed()
        self.spotify.fail["partial"] = RuntimeError("partial")
        with self.assertRaises(RuntimeError):
            self.generate()
        valid = json.loads(self.store.path.read_text())
        for field, value in (("uris", []), ("uris", ["spotify:track:foreign"]),
                             ("phase", "unknown"), ("expected_sessions", True),
                             ("output_id", "source"), ("operation_id", "bad"),
                             ("expected_snapshot", "wrong"), ("candidate", {}),
                             ("creation_name", " "), ("extra", True)):
            state = deepcopy(valid)
            state["sources"]["source"]["pending"][field] = value
            self.store.path.write_text(json.dumps(state))
            before = self.store.path.read_bytes()
            with self.subTest(field=field), self.assertRaises(OpportunityStateError):
                self.workflow.resume_pending_session("source")
            self.assertEqual(self.store.path.read_bytes(), before)


    def test_population_phase_persistence_failure_prevents_destructive_writes(self):
        self.seed()
        before = deepcopy(self.store.load()["sources"]["source"]["active"])
        original = self.store._save
        def fail_population(state):
            pending = state["sources"]["source"]["pending"]
            if pending and pending["phase"] == "populating":
                raise OpportunityStateError("disk")
            return original(state)
        with patch.object(self.store, "_save", side_effect=fail_population):
            with self.assertRaises(OpportunityStateError):
                self.generate()
        self.assertEqual(self.store.load()["sources"]["source"]["active"], before)
        self.assertEqual(self.store.pending("source")["phase"], "output_known")
        self.assertFalse(any(action in ("clear", "add") for action, _ in self.spotify.events))
        self.workflow.resume_pending_session("source")


    def test_legacy_same_name_managed_output_is_not_adopted(self):
        registry.add_managed_playlist_id("legacy")
        self.spotify.playlists["legacy"] = {"id": "legacy", "name": "Chosen"}
        self.spotify.contents["legacy"] = ["spotify:track:untouched"]
        result = self.generate()
        self.assertNotEqual(result.output_id, "legacy")
        self.assertEqual(self.spotify.contents["legacy"], ["spotify:track:untouched"])


    def seed(self):
        self.store.begin_rotation("source", self.spotify.source)

    def fail_pending(self, action="partial"):
        self.spotify.fail[action] = RuntimeError(action)
        with self.assertRaises(RuntimeError):
            self.generate()
        self.spotify.fail.clear()
        return self.store.pending("source")

    def test_three_deals_have_distinct_ids_preserve_contents_and_no_registry(self):
        originals = {}
        all_uris = set()
        for number, remaining in ((1, 5), (2, 2), (3, 0)):
            self.name.side_effect = lambda default: default
            result = self.generate(artist_separation=True)
            self.assertEqual(self.name.call_args.args, (f"Source - Deal {number}",))
            self.assertNotIn(result.output_id, originals)
            self.assertFalse(all_uris.intersection(result.uris))
            all_uris.update(result.uris)
            originals[result.output_id] = list(result.uris)
            self.assertEqual(self.spotify.contents, originals)
            self.assertEqual(result.progress["remaining_unique_count"], remaining)
            self.assertEqual(result.progress["completed_sessions"], number)
            self.assertIsNone(self.store.pending("source"))
            self.assertFalse(registry.REGISTRY_FILE.exists())
            self.assertNotIn(result.output_id, self.store.path.read_text())
        self.assertEqual(all_uris, {x["uri"] for x in self.spotify.source})
        self.assertFalse(any(action in ("clear", "add") and value == "source"
                             for action, value in self.spotify.events))
        self.assertEqual(self.name.call_count, 3)
        self.assertIn(("create", ("Source - Deal 1", OPPORTUNITY_PLAYLIST_DESCRIPTION, False)),
                      self.spotify.events)

    def test_default_user_name_trim_and_cancel_second_deal(self):
        first = self.generate()
        self.assertEqual(first.output_name, "Chosen")
        self.name.assert_called_once_with("Source - Deal 1")
        before = self.store.path.read_bytes()
        contents = deepcopy(self.spotify.contents)
        self.name.return_value = None
        self.assertIsNone(self.generate())
        self.name.assert_called_with("Source - Deal 2")
        self.assertEqual(before, self.store.path.read_bytes())
        self.assertEqual(contents, self.spotify.contents)
        self.name.return_value = "  Edited second  "
        second = self.generate()
        self.assertEqual(second.output_name, "Edited second")
        self.assertEqual(self.spotify.contents[first.output_id], list(first.uris))

    def test_second_deal_failure_and_recovery_preserve_first_and_exact_depletion(self):
        first = self.generate()
        active = deepcopy(self.store.load()["sources"]["source"]["active"])
        pending = self.fail_pending()
        second_id = pending["output_id"]
        self.assertNotEqual(first.output_id, second_id)
        self.assertEqual(self.store.load()["sources"]["source"]["active"], active)
        self.assertEqual(self.spotify.contents[first.output_id], list(first.uris))
        self.assert_pending_blocked()
        self.spotify.playlists[second_id]["name"] = "User renamed pending"
        result = self.workflow.resume_pending_session("source")
        self.assertEqual(result.output_id, second_id)
        self.assertEqual(result.output_name, "User renamed pending")
        self.assertEqual(result.uris, tuple(pending["uris"]))
        self.assertEqual(self.spotify.contents[first.output_id], list(first.uris))
        self.assertEqual(set(self.store.load()["sources"]["source"]["active"]["undealt"]),
                         set(active["undealt"]) - set(pending["uris"]))
        self.assertEqual(self.name.call_count, 2)
        self.assertFalse(registry.REGISTRY_FILE.exists())

    def test_known_id_saved_before_population_and_no_registry_writes(self):
        def fail_clear(output):
            pending = self.store.pending("source")
            self.assertEqual(output, pending["output_id"])
            self.assertEqual(pending["phase"], "populating")
            self.assertFalse(registry.REGISTRY_FILE.exists())
            raise RuntimeError("clear")
        with patch.object(self.spotify, "clear_playlist", side_effect=fail_clear):
            with self.assertRaises(RuntimeError):
                self.generate()
        pending = self.store.pending("source")
        result = self.workflow.resume_pending_session("source")
        self.assertEqual(result.output_id, pending["output_id"])
        self.assertEqual(result.uris, tuple(pending["uris"]))
        self.assertEqual(self.spotify.counter, 1)

    def test_pending_absent_listing_direct_lookup_preserves_id(self):
        pending = self.fail_pending()
        self.spotify.omit_listing = True
        result = self.workflow.resume_pending_session("source")
        self.assertEqual(result.output_id, pending["output_id"])
        self.assertIn(("lookup", pending["output_id"]), self.spotify.events)

    def test_pending_non404_lookup_preserves_state_and_stops_writes(self):
        pending = self.fail_pending()
        self.spotify.omit_listing = True
        self.spotify.fail["lookup"] = http_error(503)
        before = self.store.path.read_bytes()
        events = len(self.spotify.events)
        with self.assertRaises(HTTPError):
            self.workflow.resume_pending_session("source")
        self.assertEqual(before, self.store.path.read_bytes())
        self.assertFalse(any(action in ("create", "clear", "add")
                             for action, _ in self.spotify.events[events:]))
        self.assertEqual(self.store.pending("source"), pending)

    def test_pending_confirmed404_cancel_blank_then_replace_exact_deal(self):
        pending = self.fail_pending()
        self.spotify.playlists.clear()
        before = self.store.path.read_bytes()
        callback = Mock(return_value=None)
        self.assertIsNone(self.workflow.resume_pending_session("source", output_name_callback=callback))
        self.assertEqual(before, self.store.path.read_bytes())
        callback.return_value = " "
        with self.assertRaises(ValueError):
            self.workflow.resume_pending_session("source", output_name_callback=callback)
        self.assertEqual(before, self.store.path.read_bytes())
        callback.return_value = "Recovered"
        result = self.workflow.resume_pending_session("source", output_name_callback=callback)
        callback.assert_called_with("Chosen")
        self.assertNotEqual(result.output_id, pending["output_id"])
        self.assertEqual(result.uris, tuple(pending["uris"]))

    def test_raw404_requires_direct_id_confirmation(self):
        pending = self.fail_pending()
        self.spotify.fail["raw"] = http_error(404)
        callback = Mock(return_value="Replacement")
        with self.assertRaises(PendingSessionError):
            self.workflow.resume_pending_session("source", output_name_callback=callback)
        callback.assert_not_called()
        self.assertEqual(self.store.pending("source"), pending)
        self.spotify.fail.clear()
        with patch.object(self.spotify, "get_playlist_items_raw",
                          side_effect=[http_error(404), list(pending["uris"])]), patch.object(
                self.spotify, "get_playlist", side_effect=http_error(404)):
            result = self.workflow.resume_pending_session("source", output_name_callback=callback)
        self.assertNotEqual(result.output_id, pending["output_id"])
        self.assertEqual(result.uris, tuple(pending["uris"]))

    def test_legacy_completed_binding_is_ignored_preserved_and_not_managed(self):
        registry.REGISTRY_FILE.write_text(json.dumps({"playlist_ids": ["old"],
                                                     "opportunity_outputs": {"source": "old"}}))
        self.spotify.playlists["old"] = {"id": "old", "name": "Old deal"}
        self.spotify.contents["old"] = ["spotify:track:old"]
        before = registry.REGISTRY_FILE.read_bytes()
        first, second = self.generate(), self.generate()
        self.assertEqual(len({"old", first.output_id, second.output_id}), 3)
        self.assertEqual(self.spotify.contents["old"], ["spotify:track:old"])
        self.assertEqual(registry.REGISTRY_FILE.read_bytes(), before)
        self.assertNotIn("old", registry.load_managed_playlist_ids())
        self.assertFalse(any(action == "listing" or (action == "lookup" and value == "old")
                             for action, value in self.spotify.events))

    def test_legacy_reused_pending_output_moves_exact_deal_to_new_id(self):
        self.seed()
        self.spotify.playlists["old"] = {"id": "old", "name": "Completed Deal 1"}
        self.spotify.contents["old"] = ["spotify:track:old"]
        deal = self.store.prepare_next_deal("source", rng=random.Random(4))
        pending = self.store.stage_pending(deal, output_id="old")
        before = self.store.path.read_bytes()
        cancel = Mock(return_value=None)
        self.assertIsNone(self.workflow.resume_pending_session("source", output_name_callback=cancel))
        self.assertEqual(before, self.store.path.read_bytes())
        result = self.workflow.resume_pending_session("source", output_name_callback=self.name)
        self.assertNotEqual(result.output_id, "old")
        self.assertEqual(result.uris, tuple(pending["uris"]))
        self.assertEqual(self.spotify.contents["old"], ["spotify:track:old"])

    def test_ordinary_collision_blocks_population_and_recovery(self):
        registry.register_full_output("ordinary", "out1")
        self.spotify.contents["out1"] = ["spotify:track:ordinary"]
        with self.assertRaises(PendingSessionError):
            self.generate()
        pending = self.store.pending("source")
        self.assertEqual(pending["output_id"], "out1")
        self.assertFalse(any(action in ("clear", "add") for action, _ in self.spotify.events))
        with self.assertRaises(PendingSessionError):
            self.workflow.resume_pending_session("source")
        self.assertEqual(self.store.pending("source"), pending)

    def test_new_rotation_and_rollover_reset_names_preserve_old_playlists(self):
        self.name.side_effect = lambda default: default
        first = self.generate()
        contents = deepcopy(self.spotify.contents)
        explicit = self.workflow.start_new_rotation("source", output_name_callback=self.name)
        self.assertEqual(explicit.output_name, "Source - Deal 1")
        self.assertEqual(self.spotify.contents[first.output_id], contents[first.output_id])
        while not self.store.inspect_progress("source")["complete"]:
            self.generate()
        contents = deepcopy(self.spotify.contents)
        rollover = self.generate()
        self.assertEqual(rollover.output_name, "Source - Deal 1")
        for output, uris in contents.items():
            self.assertEqual(self.spotify.contents[output], uris)
        self.assertNotIn(rollover.output_id, contents)


    def test_cancel_new_rotation_and_rollover_preserves_authoritative_state(self):
        first = self.generate()
        before, contents = self.store.path.read_bytes(), deepcopy(self.spotify.contents)
        callback = Mock(return_value=None)
        self.assertIsNone(self.workflow.start_new_rotation("source", output_name_callback=callback))
        callback.assert_called_once_with("Source - Deal 1")
        self.assertEqual(self.store.path.read_bytes(), before)
        self.assertEqual(self.spotify.contents, contents)
        while not self.store.inspect_progress("source")["complete"]:
            self.generate()
        before, contents = self.store.path.read_bytes(), deepcopy(self.spotify.contents)
        self.name.return_value = None
        self.assertIsNone(self.generate())
        self.name.assert_called_with("Source - Deal 1")
        self.assertEqual(self.store.path.read_bytes(), before)
        self.assertEqual(self.spotify.contents, contents)
        self.assertEqual(self.spotify.contents[first.output_id], list(first.uris))

    def test_old_known_created_pending_id_recovers_without_persistent_binding(self):
        pending = self.fail_pending()
        registry.REGISTRY_FILE.write_text(json.dumps({"playlist_ids": [pending["output_id"]],
                                                     "opportunity_outputs": {"source": pending["output_id"]}}))
        before = registry.REGISTRY_FILE.read_bytes()
        result = self.workflow.resume_pending_session("source")
        self.assertEqual(result.output_id, pending["output_id"])
        self.assertEqual(result.uris, tuple(pending["uris"]))
        self.assertEqual(self.spotify.counter, 1)
        self.assertEqual(registry.REGISTRY_FILE.read_bytes(), before)
        self.assertNotIn(result.output_id, registry.load_managed_playlist_ids())

    def test_conflict_with_another_source_fails_before_output_writes(self):
        self.store.begin_rotation("out1", [item(100)])
        with self.assertRaises(PendingSessionError):
            self.generate()
        self.assertFalse(any(action in ("clear", "add") for action, _ in self.spotify.events))
        self.assertEqual(self.store.inspect_progress("out1")["remaining_unique_count"], 1)



    def test_legacy_substitution_recovery_resubmits_original_saved_membership(self):
        self.spotify.source = [item(i, minutes=3) for i in range(22)]
        self.seed()
        self.spotify.fail["lost_add"] = RuntimeError("lost response")
        with self.assertRaises(RuntimeError):
            self.generate()
        pending = self.store.pending("source")
        data = json.loads(self.store.path.read_text())
        data["schema_version"] = 1
        del data["sources"]["source"]["pending"]["delivery"]
        self.store.path.write_text(json.dumps(data))
        self.assertIsNone(self.store.pending("source")["delivery"])
        actual = list(pending["uris"])
        actual[17] = "spotify:track:differentVersion"
        self.spotify.raw_override = actual
        before = self.store.path.read_bytes()
        # Correct count alone never authorizes commit if new submission is unresolved.
        with self.assertRaises(RuntimeError):
            self.workflow.resume_pending_session("source")
        self.assertEqual(self.store.inspect_progress("source")["completed_sessions"], 0)
        del self.spotify.fail["lost_add"]
        with patch("opportunity_workflow.prepare_deal", side_effect=AssertionError("fresh deal")), patch.object(
                self.spotify, "create_playlist", side_effect=AssertionError("new playlist")):
            result = self.workflow.resume_pending_session("source")
        self.assertEqual(result.output_id, pending["output_id"])
        self.assertEqual(result.uris, tuple(pending["uris"]))
        self.assertEqual(result.progress["remaining_unique_count"], 2)
        self.assertNotIn("spotify:track:differentVersion", result.uris)
        self.assertIsNone(self.store.pending("source"))
        with self.assertRaises(PendingSessionError):
            self.workflow.resume_pending_session("source")

    def test_acknowledged_delivery_ignores_order_duplicate_null_and_count_readback(self):
        for actual in ([None], [], ["spotify:track:other"] * 3):
            with self.subTest(actual=actual):
                self.store.path.unlink(missing_ok=True)
                self.seed()
                self.spotify.raw_override = actual
                self.assertEqual(self.generate().progress["completed_sessions"], 1)

    def test_confirmed_pending_abandonment_preserves_existing_playlist(self):
        for failure in ("partial", "lost_create"):
            with self.subTest(failure=failure):
                self.store.path.unlink(missing_ok=True)
                self.seed()
                pending = self.fail_pending(failure)
                active = deepcopy(self.store.load()["sources"]["source"]["active"])
                contents = deepcopy(self.spotify.contents)
                self.spotify.source = [item(99), item(100)]
                original_finalize = self.store.finalize_pending
                def finalize(source, operation):
                    saved = self.store.load()["sources"][source]
                    self.assertEqual(saved["active"], active)
                    self.assertNotEqual(saved["pending"]["operation_id"], pending["operation_id"])
                    return original_finalize(source, operation)
                with patch.object(self.store, "finalize_pending", side_effect=finalize):
                    result = self.workflow.start_new_rotation("source", output_name_callback=self.name,
                                abandon_pending_operation_id=pending["operation_id"])
                self.name.assert_called_with("Source - Deal 1")
                self.assertEqual(set(result.uris), {"spotify:track:99", "spotify:track:100"})
                self.assertEqual(result.progress["completed_sessions"], 1)
                for output, uris in contents.items():
                    self.assertEqual(self.spotify.contents[output], uris)
                self.assertNotIn(result.output_id, contents)
                self.spotify.source = [item(i) for i in range(8)]

    def test_failed_abandonment_candidate_delivery_keeps_old_active_and_new_pending(self):
        self.seed()
        old_pending = self.fail_pending()
        old_active = deepcopy(self.store.load()["sources"]["source"]["active"])
        old_contents = deepcopy(self.spotify.contents[old_pending["output_id"]])
        self.spotify.source = [item(99)]
        self.spotify.fail["clear"] = RuntimeError("candidate delivery")
        with self.assertRaises(RuntimeError):
            self.workflow.start_new_rotation("source", output_name_callback=self.name,
                        abandon_pending_operation_id=old_pending["operation_id"])
        source = self.store.load()["sources"]["source"]
        self.assertEqual(source["active"], old_active)
        self.assertNotEqual(source["pending"]["operation_id"], old_pending["operation_id"])
        self.assertEqual(source["pending"]["candidate"]["completed_sessions"], 0)
        self.assertEqual(source["pending"]["uris"], ["spotify:track:99"])
        self.assertEqual(self.spotify.contents[old_pending["output_id"]], old_contents)
        self.spotify.fail.clear()
        result = self.workflow.resume_pending_session("source")
        self.assertEqual(result.uris, ("spotify:track:99",))
        self.assertEqual(self.spotify.contents[old_pending["output_id"]], old_contents)
        self.assertNotEqual(self.store.load()["sources"]["source"]["active"]["rotation_id"],
                            old_active["rotation_id"])

    def test_abandonment_naming_cancel_or_blank_preserves_pending_and_output(self):
        self.seed()
        pending = self.fail_pending()
        before, contents = self.store.path.read_bytes(), deepcopy(self.spotify.contents)
        for name in (None, " "):
            self.name.return_value = name
            if name is None:
                self.assertIsNone(self.workflow.start_new_rotation("source", output_name_callback=self.name,
                                  abandon_pending_operation_id=pending["operation_id"]))
            else:
                with self.assertRaises(ValueError):
                    self.workflow.start_new_rotation("source", output_name_callback=self.name,
                                  abandon_pending_operation_id=pending["operation_id"])
            self.assertEqual(self.store.path.read_bytes(), before)
            self.assertEqual(self.spotify.contents, contents)

    def test_abandonment_source_or_atomic_save_failure_preserves_old_pending(self):
        self.seed()
        pending = self.fail_pending()
        before, contents = self.store.path.read_bytes(), deepcopy(self.spotify.contents)
        for target, failure in (("source", RuntimeError("source")), ("save", OpportunityStateError("disk"))):
            context = (patch.object(self.spotify, "get_playlist_items", side_effect=failure) if target == "source"
                       else patch.object(self.store, "_save", side_effect=failure))
            with context, self.assertRaises(type(failure)):
                self.workflow.start_new_rotation("source", output_name_callback=self.name,
                                  abandon_pending_operation_id=pending["operation_id"])
            self.assertEqual(self.store.path.read_bytes(), before)
            self.assertEqual(self.spotify.contents, contents)

    def test_stale_abandonment_confirmation_stops_before_spotify_calls(self):
        pending = self.fail_pending()
        before, events = self.store.path.read_bytes(), len(self.spotify.events)
        with self.assertRaises(PendingSessionError):
            self.workflow.start_new_rotation("source", output_name_callback=self.name,
                                              abandon_pending_operation_id="stale")
        self.assertEqual(self.store.pending("source"), pending)
        self.assertEqual(self.store.path.read_bytes(), before)
        self.assertEqual(len(self.spotify.events), events)

    def test_pending_initial_rotation_can_be_abandoned_without_active_rotation(self):
        pending = self.fail_pending("lost_create")
        self.assertIsNone(self.store.load()["sources"]["source"]["active"])
        contents = deepcopy(self.spotify.contents)
        result = self.workflow.start_new_rotation("source", output_name_callback=self.name,
                                                  abandon_pending_operation_id=pending["operation_id"])
        self.assertEqual(result.progress["completed_sessions"], 1)
        for output, uris in contents.items():
            self.assertEqual(self.spotify.contents[output], uris)



class OpportunityRegistryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "registry.json"
        p = patch.object(registry, "REGISTRY_FILE", self.path)
        p.start()
        self.addCleanup(p.stop)

    def test_legacy_field_preserved_but_completed_outputs_not_managed(self):
        data = {"playlist_ids": ["legacy", "old"], "unknown": {"keep": 1},
                "opportunity_outputs": {"source": "old"}}
        self.path.write_text(json.dumps(data))
        self.assertEqual(registry.load_managed_playlist_ids(), {"legacy"})
        self.assertEqual(registry.load_legacy_opportunity_playlist_ids(), {"old"})
        registry.register_full_output("source", "full")
        registry.register_timed_output("source", 60, "timed")
        registry.register_timed_output("source", 37, "custom")
        saved = json.loads(self.path.read_text())
        self.assertEqual(saved["unknown"], data["unknown"])
        self.assertEqual(saved["opportunity_outputs"], data["opportunity_outputs"])
        self.assertEqual(registry.load_managed_playlist_ids(), {"legacy", "full", "timed", "custom"})

    def test_ordinary_binding_stays_managed_despite_legacy_field(self):
        registry.register_full_output("source", "full")
        registry.register_timed_output("source", 60, "timed")
        data = json.loads(self.path.read_text())
        data["opportunity_outputs"] = {"a": "full", "b": "timed"}
        self.path.write_text(json.dumps(data))
        self.assertEqual(registry.load_managed_playlist_ids(), {"full", "timed"})
        with self.assertRaises(ValueError):
            registry.register_timed_output("other", 37, "full")
        with self.assertRaises(ValueError):
            registry.register_full_output("other", "timed")

    def test_unused_legacy_field_does_not_break_ordinary_registry(self):
        for field in ([], None, {"source": 5}):
            self.path.write_text(json.dumps({"playlist_ids": ["ordinary"], "opportunity_outputs": field}))
            self.assertEqual(registry.load_managed_playlist_ids(), {"ordinary"})


class RawVerificationTests(unittest.TestCase):
    def setUp(self):
        self.client = SpotifyClient("test-token")

    @staticmethod
    def page(items, next_url=None, total=None):
        return Mock(json=Mock(return_value={"items": items, "next": next_url,
                                           "total": len(items) if total is None else total}))

    def test_raw_slots_preserve_order_multiplicity_and_unfiltered_entries(self):
        entries = [{"item": {"uri": "spotify:track:A", "type": "track"}},
                   {"item": None}, {"item": {"uri": "spotify:episode:E", "type": "episode"}},
                   {"item": {"uri": "spotify:track:B"}, "is_local": True}]
        next_url = "https://api.spotify.com/v1/playlists/out/items?offset=4&limit=50"
        final = [{"item": {"uri": "spotify:track:A", "type": "track"}}]
        with patch.object(self.client, "_request", side_effect=[
                self.page(entries, next_url, 5), self.page(final, total=5)]) as request:
            result = self.client.get_playlist_items_raw("out")
        self.assertEqual(result, ["spotify:track:A", None, None, None, "spotify:track:A"])
        self.assertEqual(request.call_args_list[0].kwargs["params"], {"limit": 50})
        self.assertEqual(request.call_args_list[1].args[1], next_url)
        self.assertEqual(request.call_args_list[1].kwargs["params"], {})

    def test_raw_incomplete_malformed_or_unstable_pages_fail_closed(self):
        pages = [self.page([], total=1), Mock(json=Mock(return_value={"items": []})),
                 self.page([{}]), self.page([{"item": 4}]),
                 self.page([], next_url="https://other.example/items")]
        for page in pages:
            with self.subTest(page=page), patch.object(self.client, "_request", return_value=page):
                with self.assertRaises(ValueError):
                    self.client.get_playlist_items_raw("out")

    def test_raw_pagination_cycles_and_changed_totals_fail_closed(self):
        url = "https://api.spotify.com/v1/playlists/out/items?offset=1"
        sequences = [
            [self.page([{"item": None}], url, 2), self.page([], url, 2)],
            [self.page([{"item": None}], url, 2), self.page([{"item": None}], total=3)],
        ]
        for sequence in sequences:
            with patch.object(self.client, "_request", side_effect=sequence):
                with self.assertRaises(ValueError):
                    self.client.get_playlist_items_raw("out")


class WriterCoordinationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def test_reentrant_same_thread_and_other_thread_busy(self):
        errors = []
        def compete():
            try:
                with writer_lock(self.root):
                    errors.append("incorrectly acquired")
            except WriterBusyError:
                errors.append("busy")
        with writer_lock(self.root):
            with writer_lock(self.root):
                thread = threading.Thread(target=compete)
                thread.start()
                thread.join(timeout=5)
                self.assertFalse(thread.is_alive())
        self.assertEqual(errors, ["busy"])

    def test_process_contention_blocks_workflow_and_registry_without_lost_updates(self):
        patcher = patch.object(registry, "REGISTRY_FILE", self.root / "registry.json")
        with patcher:
            registry.register_timed_output("source", 60, "out")
            script = (
                "from pathlib import Path\n"
                "import sys\n"
                "from writer_lock import writer_lock, WriterBusyError\n"
                "import playlist_registry as r\n"
                "from opportunity_state import OpportunityStore\n"
                "from opportunity_workflow import OpportunityWorkflow\n"
                "r.REGISTRY_FILE=Path(sys.argv[1])/'registry.json'\n"
                "store=OpportunityStore(Path(sys.argv[1])/'opportunity.json')\n"
                "class NoSpotify:\n"
                " def __getattr__(self, name): raise AssertionError('Spotify called while busy')\n"
                "for action in (lambda: r.register_full_output('other','full'), "
                "lambda: OpportunityWorkflow(NoSpotify(),store).generate_next_session('source'), lambda: store.begin_rotation('source',[])):\n"
                " try: action()\n"
                " except WriterBusyError: pass\n"
                " else: raise AssertionError('competing writer acquired lock')\n"
            )
            with writer_lock(self.root):
                result = subprocess.run([sys.executable, "-B", "-c", script, str(self.root)],
                                        capture_output=True, text=True, timeout=15)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(registry.timed_output_id("source", 60), "out")
            self.assertIsNone(registry.full_output_id("other"))
            registry.register_full_output("other", "full")
            self.assertEqual(registry.timed_output_id("source", 60), "out")
