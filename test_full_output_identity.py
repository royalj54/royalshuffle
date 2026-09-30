import json
import unittest
from pathlib import Path
from unittest.mock import patch

import requests
import playlist_registry as registry
from royalshuffle import royal_shuffle, SessionLengthError, RoyalShufflePartialWriteError
import test_session_length as session
from test_spotify_client import response


class FullOutputIdentityTests(unittest.TestCase):
    setUp = session.SessionLengthTests.setUp

    def prepare(self, bound=True):
        self.spotify.get_playlist_items.return_value = [session.item(1000)]
        self.spotify.get_playlists.return_value = []
        if bound:
            registry.register_full_output("source", "old")
        return self.registry_path.read_bytes() if self.registry_path.exists() else None

    def assert_no_writes(self):
        self.spotify.create_playlist.assert_not_called()
        self.spotify.clear_playlist.assert_not_called()
        self.spotify.add_playlist_items.assert_not_called()

    def test_registration_lookup_legacy_and_unknown_fields(self):
        self.registry_path.write_text(json.dumps({"playlist_ids": ["legacy"], "extra": {"keep": True}}))
        self.assertIsNone(registry.full_output_id("source"))
        registry.register_timed_output("source", 240, "timed")
        registry.register_full_output("source", "full")
        registry.register_full_output("source", "full")
        data = json.loads(self.registry_path.read_text())
        self.assertEqual(data, {"playlist_ids": ["full", "legacy", "timed"], "extra": {"keep": True},
                                "source_outputs": {"source": {"240": "timed"}},
                                "full_outputs": {"source": "full"}})
        self.assertEqual(registry.full_output_id("source"), "full")
        self.assertFalse(self.registry_path.with_suffix(".tmp").exists())

    def test_registration_collisions_and_self_binding_do_not_write(self):
        registry.register_full_output("source", "full")
        registry.register_timed_output("source", 60, "timed")
        before = self.registry_path.read_bytes()
        operations = [lambda: registry.register_full_output("self", "self"),
                      lambda: registry.register_full_output("other", "full"),
                      lambda: registry.register_full_output("source", "different"),
                      lambda: registry.register_full_output("other", "timed"),
                      lambda: registry.register_timed_output("other", 90, "full")]
        for operation in operations:
            with self.subTest(operation=operation), self.assertRaises(ValueError):
                operation()
            self.assertEqual(self.registry_path.read_bytes(), before)

    def test_loader_rejects_invalid_full_bindings(self):
        for full, timed in [([], {}), ({"": "out"}, {}), ({"s": ""}, {}),
                            ({"s": "s"}, {}), ({"s": "unmanaged"}, {}),
                            ({"s": "out", "t": "out"}, {}),
                            ({"s": "out"}, {"t": {"60": "out"}})]:
            with self.subTest(full=full):
                self.registry_path.write_text(json.dumps({"playlist_ids": ["out", "s"],
                    "source_outputs": timed, "full_outputs": full}))
                before = self.registry_path.read_bytes()
                with self.assertRaises(ValueError):
                    registry.load_managed_playlist_ids()
                self.assertEqual(self.registry_path.read_bytes(), before)

    def test_rename_and_alternate_name_cannot_redirect_reuse(self):
        before = self.prepare()
        self.source["name"] = "Renamed source"
        self.spotify.get_playlists.return_value = [{"id": "old", "name": "User renamed output"},
                                                  {"id": "other", "name": "Alternate"}]
        result = royal_shuffle(self.spotify, self.source, output_playlist_name="Alternate")
        self.assertEqual((result.output_id, result.output_name, result.action),
                         ("old", "User renamed output", "updated"))
        self.spotify.clear_playlist.assert_called_once_with("old")
        self.spotify.create_playlist.assert_not_called()
        self.spotify.get_playlist.assert_not_called()
        self.spotify.find_playlists_by_name.assert_not_called()
        self.assertEqual(self.registry_path.read_bytes(), before)

    def test_same_name_sources_are_isolated_and_legacy_is_untouched(self):
        self.prepare(bound=False)
        registry.add_managed_playlist_id("legacy")
        self.spotify.get_playlists.return_value = [{"id": "legacy", "name": "Chosen"}]
        self.spotify.find_playlists_by_name.return_value = self.spotify.get_playlists.return_value
        self.spotify.create_playlist.side_effect = [{"id": "first"}, {"id": "second"}]
        for source_id, output_id in (("a", "first"), ("b", "second")):
            result = royal_shuffle(self.spotify, {"id": source_id, "name": "Same"},
                                   output_playlist_name="Chosen")
            self.assertEqual(result.output_id, output_id)
            self.assertEqual(registry.full_output_id(source_id), output_id)
        self.assertEqual([call.args[0] for call in self.spotify.clear_playlist.call_args_list],
                         ["first", "second"])
        for call in self.spotify.create_playlist.call_args_list:
            self.assertEqual(call.kwargs["name"], "Chosen")
            self.assertFalse(call.kwargs["public"])
        self.spotify.find_playlists_by_name.assert_not_called()
        self.assertIn("legacy", registry.load_managed_playlist_ids())

    def test_absent_listing_direct_success_reuses_actual_name(self):
        before = self.prepare()
        self.spotify.get_playlist.return_value = {"id": "old", "name": "Actual name"}
        result = royal_shuffle(self.spotify, self.source, output_playlist_name="Ignored")
        self.assertEqual((result.output_id, result.output_name), ("old", "Actual name"))
        self.spotify.get_playlist.assert_called_once_with("old")
        self.spotify.create_playlist.assert_not_called()
        self.assertEqual(self.registry_path.read_bytes(), before)

    def test_confirmed_404_replaces_only_stale_full_binding_before_population(self):
        self.prepare()
        registry.register_full_output("other", "other-full")
        registry.register_timed_output("source", 240, "timed")
        registry.add_managed_playlist_id("legacy")
        self.spotify.get_playlist.side_effect = response(404).raise_for_status.side_effect
        def create(**kwargs):
            self.assertIsNone(registry.full_output_id("source"))
            self.assertNotIn("old", registry.load_managed_playlist_ids())
            return {"id": "replacement"}
        def clear(output_id):
            self.assertEqual(registry.full_output_id("source"), output_id)
            self.assertIn(output_id, registry.load_managed_playlist_ids())
        self.spotify.create_playlist.side_effect = create
        self.spotify.clear_playlist.side_effect = clear
        result = royal_shuffle(self.spotify, self.source)
        self.assertEqual(result.output_id, "replacement")
        self.assertEqual(registry.full_output_id("other"), "other-full")
        self.assertEqual(registry.timed_output_id("source", 240), "timed")
        self.assertIn("legacy", registry.load_managed_playlist_ids())

    def test_non_404_and_listing_failures_preserve_binding(self):
        before = self.prepare()
        failures = [response(code).raise_for_status.side_effect for code in (400, 401, 403, 429, 500, 503)]
        failures += [requests.ConnectionError("offline"), TimeoutError("timeout"), ValueError("invalid JSON")]
        for failure in failures:
            with self.subTest(failure=failure):
                self.spotify.get_playlist.side_effect = failure
                with self.assertRaises(type(failure)):
                    royal_shuffle(self.spotify, self.source)
                self.assert_no_writes()
                self.assertEqual(self.registry_path.read_bytes(), before)
        self.spotify.get_playlists.side_effect = TimeoutError("listing failed")
        with self.assertRaises(TimeoutError):
            royal_shuffle(self.spotify, self.source)
        self.assert_no_writes()
        self.assertEqual(self.registry_path.read_bytes(), before)

    def test_invalid_direct_payload_and_self_binding_abort(self):
        before = self.prepare()
        for payload in (None, {}, {"id": "other", "name": "Wrong"}, {"id": "old"}):
            with self.subTest(payload=payload):
                self.spotify.get_playlist.return_value = payload
                with self.assertRaises(SessionLengthError):
                    royal_shuffle(self.spotify, self.source)
                self.assert_no_writes()
                self.assertEqual(self.registry_path.read_bytes(), before)
        with patch("royalshuffle.full_output_id", return_value="source"), self.assertRaises(SessionLengthError):
            royal_shuffle(self.spotify, self.source)
        self.assert_no_writes()

    def test_expected_id_and_removal_persistence_failure_prevent_replacement(self):
        before = self.prepare()
        with self.assertRaises(ValueError):
            registry.remove_full_output("source", "different")
        self.assertEqual(self.registry_path.read_bytes(), before)
        self.spotify.get_playlist.side_effect = response(404).raise_for_status.side_effect
        with patch.object(Path, "replace", side_effect=OSError("disk full")), self.assertRaises(OSError):
            royal_shuffle(self.spotify, self.source)
        self.assert_no_writes()
        self.assertEqual(self.registry_path.read_bytes(), before)
        with patch("royalshuffle.remove_full_output", side_effect=ValueError("binding changed")), self.assertRaises(ValueError):
            royal_shuffle(self.spotify, self.source)
        self.assert_no_writes()
        self.assertEqual(self.registry_path.read_bytes(), before)

    def test_registration_persistence_failure_reports_orphan_without_population(self):
        self.prepare(bound=False)
        with patch.object(Path, "replace", side_effect=OSError("disk full")):
            with self.assertRaises(RoyalShufflePartialWriteError) as caught:
                royal_shuffle(self.spotify, self.source)
        self.assertEqual(caught.exception.result.output_id, "new")
        self.assertEqual(caught.exception.result.items_written, 0)
        self.spotify.clear_playlist.assert_not_called()
        self.spotify.add_playlist_items.assert_not_called()
        self.assertFalse(self.registry_path.exists())

    def test_new_binding_is_durable_before_clear_and_survives_partial_write(self):
        self.prepare(bound=False)
        self.spotify.clear_playlist.side_effect = lambda output_id: self.assertEqual(
            registry.full_output_id("source"), output_id)
        failure = RuntimeError("write failed")
        failure.items_written = 0
        self.spotify.add_playlist_items.side_effect = failure
        with self.assertRaises(RoyalShufflePartialWriteError):
            royal_shuffle(self.spotify, self.source)
        self.assertEqual(registry.full_output_id("source"), "new")
        self.assertIn("new", registry.load_managed_playlist_ids())

    def test_atomic_registration_and_removal_preserve_unrelated_registry_state(self):
        self.registry_path.write_text(json.dumps({"playlist_ids": ["legacy"], "extra": {"keep": True}}))
        registry.register_timed_output("source", 60, "timed")
        before = self.registry_path.read_bytes()
        with patch.object(Path, "replace", side_effect=OSError("disk full")), self.assertRaises(OSError):
            registry.register_full_output("source", "full")
        self.assertEqual(self.registry_path.read_bytes(), before)
        registry.register_full_output("source", "full")
        registry.register_full_output("other", "other-full")
        registry.add_managed_playlist_id("another-legacy")
        registry.remove_full_output("source", "full")
        data = json.loads(self.registry_path.read_text())
        self.assertEqual(data["extra"], {"keep": True})
        self.assertEqual(data["source_outputs"], {"source": {"60": "timed"}})
        self.assertEqual(data["full_outputs"], {"other": "other-full"})
        self.assertEqual(set(data["playlist_ids"]), {"legacy", "another-legacy", "timed", "other-full"})
        registry.remove_timed_output("source", 60, "timed")
        self.assertEqual(registry.full_output_id("other"), "other-full")
        self.assertIn("other-full", registry.load_managed_playlist_ids())
