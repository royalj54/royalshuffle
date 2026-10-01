"""Acknowledged submission, binding, and crash-boundary regression tests."""
from copy import deepcopy
import json
import unittest
from unittest.mock import Mock, patch

from delivery_receipts import delivery_plan, payload_digest, validate_delivery
from spotify_client import SpotifyClient
from opportunity_state import OpportunityStateError
import test_opportunity_workflow as workflow_tests


class ReceiptTests(unittest.TestCase):
    def setUp(self):
        self.client = SpotifyClient("test")
        self.uris = [f"spotify:track:{i}" for i in range(205)]
        self.evidence = delivery_plan("operation", "output", self.uris)

    def response(self, status, snapshot="snapshot"):
        return Mock(status_code=status, json=Mock(return_value={"snapshot_id": snapshot}))

    def persist(self, receipt):
        self.evidence["receipts"].append(deepcopy(receipt))
        validate_delivery(self.evidence, "operation", "output", self.uris)

    def test_complete_multi_batch_receipts_cover_payload_and_order(self):
        with patch.object(self.client, "_request", side_effect=[self.response(200)] + [self.response(201)] * 3) as request:
            self.client.submit_opportunity_deal(self.evidence, self.uris, self.persist)
        self.assertTrue(validate_delivery(self.evidence, "operation", "output", self.uris))
        self.assertEqual([(r["start"], r["end"]) for r in self.evidence["receipts"]], [(0, 0), (0, 100), (100, 200), (200, 205)])
        self.assertEqual([c.kwargs["json"]["uris"] for c in request.call_args_list], [[], self.uris[:100], self.uris[100:200], self.uris[200:]])

    def test_one_batch_complete(self):
        uris = self.uris[:20]
        evidence = delivery_plan("operation", "output", uris)
        with patch.object(self.client, "_request", side_effect=[self.response(200), self.response(201)]):
            self.client.submit_opportunity_deal(evidence, uris, evidence["receipts"].append)
        self.assertTrue(validate_delivery(evidence, "operation", "output", uris))

    def test_invalid_acknowledgment_stops_before_next_batch(self):
        for invalid in (self.response(201, ""), self.response(201, None), self.response(200), Mock(status_code=201, json=Mock(return_value={})), Mock(status_code=201, json=Mock(return_value=[]))):
            with self.subTest(invalid=invalid):
                evidence = delivery_plan("operation", "output", self.uris)
                with patch.object(self.client, "_request", side_effect=[self.response(200), invalid]) as request:
                    with self.assertRaises(ValueError):
                        self.client.submit_opportunity_deal(evidence, self.uris, evidence["receipts"].append)
                self.assertEqual(request.call_count, 2)
                self.assertFalse(validate_delivery(evidence, "operation", "output", self.uris))

    def test_later_failure_or_lost_response_leaves_partial_evidence(self):
        with patch.object(self.client, "_request", side_effect=[self.response(200), self.response(201), RuntimeError("unknown write")]) as request:
            with self.assertRaises(RuntimeError):
                self.client.submit_opportunity_deal(self.evidence, self.uris, self.persist)
        self.assertEqual(request.call_count, 3)
        self.assertEqual(len(self.evidence["receipts"]), 2)
        self.assertFalse(validate_delivery(self.evidence, "operation", "output", self.uris))

    def test_receipt_persistence_failure_stops_subsequent_writes(self):
        persist = Mock(side_effect=[None, OSError("disk")])
        with patch.object(self.client, "_request", side_effect=[self.response(200), self.response(201)]) as request:
            with self.assertRaises(OSError):
                self.client.submit_opportunity_deal(self.evidence, self.uris, persist)
        self.assertEqual(request.call_count, 2)

    def test_binding_and_receipt_tampering_rejected(self):
        with patch.object(self.client, "_request", side_effect=[self.response(200)] + [self.response(201)] * 3):
            self.client.submit_opportunity_deal(self.evidence, self.uris, self.persist)
        for key, value in (("operation_id", "other"), ("output_id", "other"), ("payload_digest", payload_digest(list(reversed(self.uris)))), ("attempt_id", "bad")):
            evidence = deepcopy(self.evidence)
            evidence[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_delivery(evidence, "operation", "output", self.uris)
        for key, value in (("attempt_id", "other"), ("index", 4), ("start", 1), ("end", 99), ("method", "PUT"), ("status", 200), ("snapshot_id", ""), ("payload_digest", "wrong")):
            evidence = deepcopy(self.evidence)
            evidence["receipts"][1][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_delivery(evidence, "operation", "output", self.uris)

    def test_partial_attempt_cannot_be_appended_again(self):
        with patch.object(self.client, "_request", side_effect=[self.response(200), RuntimeError("lost")]):
            with self.assertRaises(RuntimeError):
                self.client.submit_opportunity_deal(self.evidence, self.uris, self.persist)
        with patch.object(self.client, "_request") as request, self.assertRaises(ValueError):
            self.client.submit_opportunity_deal(self.evidence, self.uris, self.persist)
        request.assert_not_called()


class DurableRecoveryTests(unittest.TestCase):
    def setUp(self):
        fixture = workflow_tests.OpportunityWorkflowTests()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        self.fixture = fixture
        fixture.seed()

    def test_complete_receipts_survive_crash_before_delivered_phase_and_deleted_output(self):
        f = self.fixture
        original = f.store.update_pending
        def update(source, operation, **changes):
            if changes.get("phase") == "delivered":
                raise OpportunityStateError("crash before local commit")
            return original(source, operation, **changes)
        with patch.object(f.store, "update_pending", side_effect=update), self.assertRaises(OpportunityStateError):
            f.generate()
        pending = f.store.pending("source")
        self.assertEqual(pending["phase"], "populating")
        self.assertTrue(validate_delivery(pending["delivery"], pending["operation_id"], pending["output_id"], pending["uris"]))
        f.spotify.playlists.clear()
        f.spotify.contents.clear()
        with patch.object(f.spotify, "get_playlist_items_raw", side_effect=AssertionError("readback")), patch.object(f.spotify, "submit_opportunity_deal", side_effect=AssertionError("rewrite")), patch.object(f.spotify, "get_playlists", side_effect=AssertionError("lookup")):
            result = f.workflow.resume_pending_session("source")
        self.assertEqual(result.uris, tuple(pending["uris"]))
        self.assertEqual(result.progress["completed_sessions"], 1)
        self.assertIsNone(f.store.pending("source"))
        with self.assertRaises(Exception):
            f.workflow.resume_pending_session("source")
        self.assertEqual(f.store.inspect_progress("source")["completed_sessions"], 1)

    def test_success_before_receipt_persistence_is_uncertain_then_exact_fallback(self):
        f = self.fixture
        original = f.store.update_pending
        def update(source, operation, **changes):
            delivery = changes.get("delivery")
            if delivery and len(delivery["receipts"]) == 2:
                raise OpportunityStateError("receipt lost")
            return original(source, operation, **changes)
        with patch.object(f.store, "update_pending", side_effect=update), self.assertRaises(OpportunityStateError):
            f.generate()
        pending = f.store.pending("source")
        self.assertEqual(len(pending["delivery"]["receipts"]), 1)
        self.assertEqual(f.store.inspect_progress("source")["completed_sessions"], 0)
        with patch.object(f.spotify, "submit_opportunity_deal", side_effect=AssertionError("no rewrite")):
            result = f.workflow.resume_pending_session("source")
        self.assertEqual(result.uris, tuple(pending["uris"]))

    def test_legacy_migration_read_only_and_preserves_identity(self):
        f = self.fixture
        f.spotify.fail["lost_add"] = RuntimeError("lost")
        with self.assertRaises(RuntimeError):
            f.generate()
        data = json.loads(f.store.path.read_text())
        data["schema_version"] = 1
        pending = data["sources"]["source"]["pending"]
        del pending["delivery"]
        f.store.path.write_text(json.dumps(data))
        before = f.store.path.read_bytes()
        migrated = f.store.pending("source")
        self.assertIsNone(migrated.pop("delivery"))
        self.assertEqual(migrated, pending)
        self.assertEqual(f.store.path.read_bytes(), before)

    def test_mismatching_readback_cannot_commit_unresolved_order_duplicate_null_or_count(self):
        f = self.fixture
        for transform in (lambda u: list(reversed(u)), lambda u: [u[0]] * len(u), lambda u: [None] * len(u), lambda u: u[:-1]):
            with self.subTest(transform=transform):
                f.store.path.unlink(missing_ok=True)
                f.seed()
                f.spotify.fail["lost_add"] = RuntimeError("ambiguous")
                with self.assertRaises(RuntimeError):
                    f.generate()
                pending = f.store.pending("source")
                f.spotify.raw_override = transform(pending["uris"])
                with self.assertRaises(RuntimeError):
                    f.workflow.resume_pending_session("source")
                self.assertEqual(f.store.inspect_progress("source")["completed_sessions"], 0)
                self.assertEqual(f.store.pending("source")["uris"], pending["uris"])

    def test_state_rejects_receipts_from_other_operation_output_payload_or_attempt(self):
        f = self.fixture
        with patch.object(f.store, "finalize_pending", side_effect=OpportunityStateError("crash")), self.assertRaises(OpportunityStateError):
            f.generate()
        data = json.loads(f.store.path.read_text())
        for key, value in (("operation_id", "other"), ("output_id", "other"), ("payload_digest", "wrong"), ("attempt_id", "invalid")):
            changed = deepcopy(data)
            changed["sources"]["source"]["pending"]["delivery"][key] = value
            f.store.path.write_text(json.dumps(changed))
            with self.subTest(key=key), self.assertRaises(OpportunityStateError):
                f.store.load()
        f.store.path.write_text(json.dumps(data))

    def test_multi_batch_durable_recovery_uses_original_membership(self):
        f = self.fixture
        from test_opportunity_workflow import item
        f.store.path.unlink(missing_ok=True)
        f.spotify.source = [{**item(i), "duration_ms": 6000} for i in range(205)]
        f.seed()
        with patch.object(f.store, "finalize_pending", side_effect=OpportunityStateError("crash")), self.assertRaises(OpportunityStateError):
            f.generate()
        pending = f.store.pending("source")
        self.assertEqual(len(pending["uris"]), 205)
        self.assertEqual(len(pending["delivery"]["receipts"]), 4)
        with patch.object(f.spotify, "submit_opportunity_deal", side_effect=AssertionError("rewrite")):
            result = f.workflow.resume_pending_session("source")
        self.assertEqual(result.uris, tuple(pending["uris"]))
        self.assertEqual(result.progress["remaining_unique_count"], 0)
