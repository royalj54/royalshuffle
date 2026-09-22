import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import playlist_registry as registry
from playlist_service import eligible_source_playlists


class PlaylistRegistryTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.path = Path(temp.name) / "registry.json"
        patcher = patch.object(registry, "REGISTRY_FILE", self.path)
        patcher.start()
        self.addCleanup(patcher.stop)

    def write(self, data):
        self.path.write_text(json.dumps(data), encoding="utf-8")

    def test_legacy_and_absent_bindings(self):
        self.assertEqual(registry.load_managed_playlist_ids(), set())
        self.write({"playlist_ids": ["legacy"], "extra": {"keep": True}})
        self.assertIsNone(registry.timed_output_id("source", 30))
        registry.register_timed_output("source", 30, "timed")
        registry.add_managed_playlist_id("another")
        data = json.loads(self.path.read_text())
        self.assertEqual(data["extra"], {"keep": True})
        self.assertEqual(set(data["playlist_ids"]), {"legacy", "timed", "another"})
        self.assertEqual(data["source_outputs"], {"source": {"30": "timed"}})
        self.assertFalse(self.path.with_suffix(".tmp").exists())

    def test_failed_atomic_replace_preserves_original(self):
        self.write({"playlist_ids": ["legacy"]})
        original = self.path.read_bytes()
        with patch.object(Path, "replace", side_effect=OSError("disk error")):
            with self.assertRaises(OSError):
                registry.register_timed_output("source", 30, "timed")
        self.assertEqual(self.path.read_bytes(), original)

    def test_conflicts_and_self_binding_rejected(self):
        registry.register_timed_output("source", 30, "output")
        for args in [("other", 30, "output"), ("source", 60, "output"),
                     ("source", 30, "other"), ("source", 90, "source")]:
            with self.subTest(args=args), self.assertRaises(ValueError):
                registry.register_timed_output(*args)
        registry.register_timed_output("source", 30, "output")
        self.assertEqual(registry.timed_output_id("source", 30), "output")

    def test_malformed_registry_rejected(self):
        for bindings in [[], {"s": []}, {"s": {"30": "unregistered"}},
                         {"s": {"30": "s"}}, {"s": {"120": "o"}},
                         {"s": {"30": "o", "60": "o"}},
                         {"s": {"30": "o"}, "t": {"30": "o"}}]:
            with self.subTest(bindings=bindings):
                self.write({"playlist_ids": ["o", "s"], "source_outputs": bindings})
                with self.assertRaises(ValueError):
                    registry.load_managed_playlist_ids()

    def test_all_variants_excluded_by_id_only(self):
        registry.add_managed_playlist_id("full")
        for minutes in (30, 60, 90):
            registry.register_timed_output("source", minutes, str(minutes))
        items = [{"id": value, "name": "Source - RANDOM"}
                 for value in ("full", "30", "60", "90", "ordinary")]
        self.assertEqual(eligible_source_playlists(items), [items[-1]])
