import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import playlist_registry as registry
from royalshuffle import royal_shuffle
import test_ui_playlist_discovery as discovery
from test_spotify_client import response


class FullCreationNamingUiTests(unittest.TestCase):
    def setUp(self):
        discovery.PlaylistDiscoveryTests.setUp(self)
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.registry_path = Path(temporary.name) / "registry.json"
        for target, value in (("playlist_registry.REGISTRY_FILE", self.registry_path),
                              ("royalshuffle.log_debug", Mock())):
            patcher = patch(target, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        self.client.get_playlist_items.return_value = [{"uri": "spotify:track:one", "duration_ms": 1000}]
        self.client.get_playlists.return_value = []
        self.client.create_playlist.return_value = {"id": "new"}
        self.client.add_playlist_items.return_value = 1

    def assert_no_output_writes(self):
        self.client.create_playlist.assert_not_called()
        self.client.clear_playlist.assert_not_called()
        self.client.add_playlist_items.assert_not_called()

    @patch("ui.simpledialog.askstring")
    def test_existing_binding_and_renamed_output_omit_prompt(self, ask):
        registry.register_full_output("z", "old")
        before = self.registry_path.read_bytes()
        for name in ("Zulu - RANDOM", "User renamed output"):
            with self.subTest(name=name):
                self.client.reset_mock()
                self.client.get_playlists.return_value = [{"id": "old", "name": name}]
                self.shuffle.invoke()
                ask.assert_not_called()
                self.client.create_playlist.assert_not_called()
                self.client.clear_playlist.assert_called_once_with("old")
                self.assertIn(name, self.status.config.call_args.kwargs["text"])
                self.assertEqual(self.registry_path.read_bytes(), before)

    @patch("ui.simpledialog.askstring")
    def test_direct_success_also_omits_prompt(self, ask):
        registry.register_full_output("z", "old")
        self.client.get_playlist.return_value = {"id": "old", "name": "Direct current name"}
        self.shuffle.invoke()
        self.client.get_playlist.assert_called_once_with("old")
        ask.assert_not_called()
        self.client.create_playlist.assert_not_called()
        self.assertIn("Direct current name", self.status.config.call_args.kwargs["text"])

    @patch("ui.simpledialog.askstring", return_value="  Chosen output  ")
    def test_new_output_prompts_with_default_and_trims_custom_name(self, ask):
        self.shuffle.invoke()
        ask.assert_called_once_with("RoyalShuffle", "Name your shuffled playlist:",
                                    initialvalue="Zulu - RANDOM", parent=self.root)
        self.assertEqual(self.client.create_playlist.call_args.kwargs["name"], "Chosen output")
        self.assertEqual(registry.full_output_id("z"), "new")

    @patch("ui.simpledialog.askstring", return_value="   ")
    def test_blank_name_rejected_without_writes(self, ask):
        self.shuffle.invoke()
        ask.assert_called_once()
        self.assert_no_output_writes()
        self.assertFalse(self.registry_path.exists())
        self.assertEqual(self.status.config.call_args.kwargs["text"], "Playlist name cannot be empty")
        self.assertEqual(self.shuffle.config.call_args.kwargs["state"], "normal")

    @patch("ui.simpledialog.askstring", return_value=None)
    def test_creation_cancel_leaves_registry_and_spotify_unmodified(self, ask):
        registry.add_managed_playlist_id("legacy")
        before = self.registry_path.read_bytes()
        self.shuffle.invoke()
        ask.assert_called_once()
        self.assert_no_output_writes()
        self.assertEqual(self.registry_path.read_bytes(), before)
        self.assertEqual(self.status.config.call_args.kwargs["text"], "Royal Shuffle cancelled.")
        self.assertEqual(self.shuffle.config.call_args.kwargs["state"], "normal")

    @patch("ui.simpledialog.askstring")
    def test_stale_404_prompts_before_removal_creation_and_population(self, ask):
        registry.register_full_output("z", "old")
        self.client.get_playlist.side_effect = response(404).raise_for_status.side_effect
        events = []
        def name(*args, **kwargs):
            self.assertEqual(registry.full_output_id("z"), "old")
            self.assert_no_output_writes()
            events.append("name")
            return " Replacement "
        def create(**kwargs):
            self.assertIsNone(registry.full_output_id("z"))
            self.assertEqual(kwargs["name"], "Replacement")
            events.append("create")
            return {"id": "new"}
        def clear(output_id):
            self.assertEqual(registry.full_output_id("z"), output_id)
            events.append("clear")
        ask.side_effect = name
        self.client.create_playlist.side_effect = create
        self.client.clear_playlist.side_effect = clear
        self.shuffle.invoke()
        self.assertEqual(events, ["name", "create", "clear"])
        ask.assert_called_once()
        self.assertEqual(registry.full_output_id("z"), "new")

    @patch("ui.simpledialog.askstring")
    def test_stale_cancel_and_blank_preserve_original_binding(self, ask):
        registry.register_full_output("z", "old")
        before = self.registry_path.read_bytes()
        self.client.get_playlist.side_effect = response(404).raise_for_status.side_effect
        for answer in (None, "   "):
            with self.subTest(answer=answer):
                ask.reset_mock()
                ask.return_value = answer
                self.shuffle.invoke()
                ask.assert_called_once()
                self.assert_no_output_writes()
                self.assertEqual(self.registry_path.read_bytes(), before)

    @patch("ui.simpledialog.askstring", return_value="Timed custom name")
    def test_timed_preset_prompts_on_creation(self, ask):
        self.session_variable.get.return_value = "60M"
        self.shuffle.invoke()
        ask.assert_called_once()
        self.assertEqual(ask.call_args.kwargs["initialvalue"], "Zulu - RANDOM 60M")
        self.assertEqual(self.client.create_playlist.call_args.kwargs["name"], "Timed custom name")
        self.assertEqual(registry.timed_output_id("z", 60), "new")

    def test_default_core_cli_call_creates_without_callback(self):
        result = royal_shuffle(self.client, {"id": "z", "name": "Zulu"})
        self.assertEqual(result.output_name, "Zulu - RANDOM")
        self.assertEqual(registry.full_output_id("z"), result.output_id)

    def test_timed_core_uses_creation_name_callback(self):
        callback = Mock(return_value="Chosen timed name")
        result = royal_shuffle(self.client, {"id": "z", "name": "Zulu"},
                               session_minutes=240, output_name_callback=callback)
        callback.assert_called_once_with("Zulu - RANDOM 240M")
        self.assertEqual(result.output_name, "Chosen timed name")

    @patch("ui.simpledialog.askstring")
    def test_timed_reuse_and_replacement_naming_for_preset_and_custom(self, ask):
        for minutes in (60, 240):
            with self.subTest(minutes=minutes):
                if minutes == 240:
                    ask.return_value = "240"
                    self.session_variable.get.return_value = "Custom..."
                    self.option_menu.call_args.kwargs["command"]("Custom...")
                    self.session_variable.get.return_value = "240M"
                else:
                    self.session_variable.get.return_value = "60M"
                ask.reset_mock()
                registry.register_timed_output("z", minutes, f"old-{minutes}")
                self.client.reset_mock()
                self.client.get_playlist.side_effect = None
                self.client.get_playlists.return_value = [{"id": f"old-{minutes}", "name": "Renamed timed"}]
                self.shuffle.invoke()
                ask.assert_not_called()
                self.client.create_playlist.assert_not_called()
                self.assertIn("Renamed timed", self.status.config.call_args.kwargs["text"])
                self.client.reset_mock()
                self.client.get_playlists.return_value = []
                self.client.get_playlist.side_effect = response(404).raise_for_status.side_effect
                before = self.registry_path.read_bytes()
                for answer in (None, "   "):
                    ask.return_value = answer
                    self.shuffle.invoke()
                    self.assert_no_output_writes()
                    self.assertEqual(self.registry_path.read_bytes(), before)
                ask.reset_mock()
                ask.return_value = " Replacement timed "
                def create(**kwargs):
                    self.assertIsNone(registry.timed_output_id("z", minutes))
                    self.assertEqual(kwargs["name"], "Replacement timed")
                    return {"id": f"replacement-{minutes}"}
                self.client.create_playlist.side_effect = create
                self.shuffle.invoke()
                ask.assert_called_once()
                self.assertEqual(ask.call_args.kwargs["initialvalue"], f"Zulu - RANDOM {minutes}M")
                self.assertEqual(registry.timed_output_id("z", minutes), f"replacement-{minutes}")
                self.client.create_playlist.side_effect = None

    def test_custom_creation_cancel_blank_and_name_preserve_identity(self):
        for answer in (None, " ", " Chosen Custom "):
            with self.subTest(answer=answer):
                callback = Mock(return_value=answer)
                if answer == " ":
                    from royalshuffle import SessionLengthError
                    with self.assertRaisesRegex(SessionLengthError, "Playlist name cannot be empty"):
                        royal_shuffle(self.client, {"id": "z", "name": "Zulu"},
                                      session_minutes=240, output_name_callback=callback)
                else:
                    result = royal_shuffle(self.client, {"id": "z", "name": "Zulu"},
                                           session_minutes=240, output_name_callback=callback)
                    if answer is None:
                        self.assertIsNone(result)
                    else:
                        self.assertEqual(result.output_name, "Chosen Custom")
                        self.assertEqual(registry.timed_output_id("z", 240), result.output_id)
                callback.assert_called_once_with("Zulu - RANDOM 240M")
                if answer in (None, " "):
                    self.assert_no_output_writes()
                    self.assertFalse(self.registry_path.exists())
