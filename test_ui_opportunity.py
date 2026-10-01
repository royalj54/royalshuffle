import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

import ui
import test_ui_playlist_discovery as discovery


class OpportunityUiTests(unittest.TestCase):
    def setUp(self):
        self.workflow_patch = patch("ui.OpportunityWorkflow")
        self.workflow_type = self.workflow_patch.start()
        self.addCleanup(self.workflow_patch.stop)
        self.workflow = self.workflow_type.return_value
        self.source = None
        self.workflow.store.load.side_effect = lambda: {"sources": {"z": self.source}} if self.source else {"sources": {}}
        self.real_legacy_review = ui.review_legacy_playlists
        discovery.PlaylistDiscoveryTests.setUp(self)
        self.source = None
        calls = ui.tk.Checkbutton.call_args_list
        self.toggle = next(c.kwargs["command"] for c in calls if c.kwargs["text"] == "Balanced Opportunity")
        self.rotation = next(label for kwargs, label in self.labels if kwargs.get("text") == "No rotation yet")
        self.new = self.buttons["Start New Rotation"]
        self.session_variable.set.side_effect = lambda value: setattr(self.session_variable.get, "return_value", value)
        self.tasks = []
        self.thread_patch = patch("ui.threading.Thread", side_effect=lambda **kw: SimpleNamespace(start=lambda: self.tasks.append(kw["target"])))
        self.thread_patch.start()
        self.addCleanup(self.thread_patch.stop)
        self.workflow.generate_next_session.return_value = SimpleNamespace(output_name="Out", uris=("uri",))
        self.workflow.start_new_rotation.return_value = self.workflow.generate_next_session.return_value
        self.workflow.resume_pending_session.return_value = self.workflow.generate_next_session.return_value

    def enable(self):
        self.opportunity_variable.get.return_value = True
        self.toggle()

    def finish(self):
        self.tasks.pop(0)()
        self.root.after.call_args.args[1]()

    def active(self, remaining=2):
        return {"completed_sessions": 3, "original_unique_count": 5,
                "undealt": {str(n): {"duration_ms": 1000} for n in range(remaining)}}

    def test_off_ordinary_path(self):
        with patch("ui.royal_shuffle", return_value=None) as ordinary:
            self.shuffle.invoke()
        ordinary.assert_called_once()
        self.workflow.generate_next_session.assert_not_called()

    def test_custom_value_and_selection_survive_toggle(self):
        choose = self.option_menu.call_args.kwargs["command"]
        with patch("ui.simpledialog.askstring", return_value="37"):
            choose("Custom...")
        self.assertEqual(self.session_variable.get(), "37M")
        self.enable()
        self.assertEqual(self.session_variable.get(), "60M")
        self.assertEqual(self.option_menu.return_value.config.call_args.kwargs["state"], "disabled")
        choose("Custom...")
        self.assertEqual(self.session_variable.get(), "60M")
        self.opportunity_variable.get.return_value = False
        self.toggle()
        self.assertEqual(self.session_variable.get(), "37M")
        with patch("ui.simpledialog.askstring", return_value=None) as ask:
            choose("Custom...")
        self.assertEqual(ask.call_args.kwargs["initialvalue"], "37")

    def test_full_and_60_restore(self):
        for label in ("Full Playlist", "60M"):
            self.session_variable.get.return_value = label
            self.enable()
            self.assertEqual(self.session_variable.get(), "60M")
            self.opportunity_variable.get.return_value = False
            self.toggle()
            self.assertEqual(self.session_variable.get(), label)

    def test_generate_artist_snapshot_and_busy_guards(self):
        self.enable()
        self.artist_variable.get.return_value = True
        self.shuffle.invoke()
        self.assertEqual(self.shuffle.config.call_args.kwargs["state"], "disabled")
        self.shuffle.invoke()
        self.new.invoke()
        self.export.invoke()
        self.bindings["<Return>"](None)
        self.assertEqual(len(self.tasks), 1)
        self.client.get_playlist_items.assert_not_called()
        self.artist_variable.get.return_value = False
        self.finish()
        self.workflow.generate_next_session.assert_called_once()
        self.assertEqual(self.workflow.generate_next_session.call_args.args, ("z",))
        self.assertIs(self.workflow.generate_next_session.call_args.kwargs["artist_separation"], True)
        self.assertEqual(self.workflow.generate_next_session.call_args.kwargs["source_name"], "Zulu")
        self.assertNotIn("session_minutes", self.workflow.generate_next_session.call_args.kwargs)
        self.assertEqual(self.shuffle.config.call_args.kwargs["state"], "normal")

    def test_new_rotation_confirmation_cancel_no_work(self):
        self.source = {"active": self.active(), "pending": None}
        self.enable()
        with patch("ui.messagebox.askyesno", return_value=False) as confirm:
            self.new.invoke()
        self.assertIn("2 remaining unique", confirm.call_args.args[1])
        self.assertFalse(self.tasks)
        self.workflow.start_new_rotation.assert_not_called()
        self.client.get_playlist_items.assert_not_called()
        self.workflow.store.load.assert_called()
        self.assertEqual(self.source["active"]["undealt"].keys(), {"0", "1"})

    def test_new_rotation_confirmed_and_exhausted_no_warning(self):
        for remaining in (2, 0):
            self.source = {"active": self.active(remaining), "pending": None}
            self.enable()
            with patch("ui.messagebox.askyesno", return_value=True) as confirm:
                self.new.invoke()
                self.finish()
            self.assertEqual(confirm.call_count, int(remaining > 0))
        self.assertEqual(self.workflow.start_new_rotation.call_count, 2)

    def test_pending_exposes_resume_and_keeps_membership_undealt(self):
        self.source = {"active": self.active(), "pending": {"candidate": None, "uris": ["0"]}}
        self.enable()
        self.assertEqual(self.shuffle.config.call_args.kwargs["text"], "Resume Pending Session")
        self.assertEqual(self.new.config.call_args.kwargs["state"], "normal")
        self.assertIn("2 of 5 tracks remaining", self.rotation.config.call_args.kwargs["text"])
        self.assertIn("Session 4 incomplete", self.rotation.config.call_args.kwargs["text"])
        with patch("ui.messagebox.askyesno", return_value=False):
            self.new.invoke()
        self.assertFalse(self.tasks)
        self.shuffle.invoke()
        self.finish()
        self.workflow.resume_pending_session.assert_called_once()
        self.assertEqual(self.workflow.resume_pending_session.call_args.args, ("z",))
        self.assertEqual(self.workflow.resume_pending_session.call_args.kwargs["source_name"], "Zulu")
        self.workflow.generate_next_session.assert_not_called()
        self.workflow.start_new_rotation.assert_not_called()

    def test_failure_restores_controls_and_presents_recovery(self):
        self.enable()
        def fail(*args, **kwargs):
            self.source = {"active": self.active(), "pending": {"candidate": None}}
            raise RuntimeError("resume saved operation")
        self.workflow.generate_next_session.side_effect = fail
        self.shuffle.invoke()
        self.finish()
        self.assertIn("resume saved operation", self.status.config.call_args.kwargs["text"])
        self.assertEqual(self.shuffle.config.call_args.kwargs["text"], "Resume Pending Session")
        self.assertEqual(self.shuffle.config.call_args.kwargs["state"], "normal")
        self.assertEqual(self.new.config.call_args.kwargs["state"], "normal")
        self.assertEqual(self.entry.config.call_args.kwargs["state"], self.entry.cget.return_value)

    def test_unreadable_state_blocks_actions(self):
        self.workflow.store.load.side_effect = OSError("state unreadable")
        self.enable()
        self.assertEqual(self.shuffle.config.call_args.kwargs["state"], "disabled")
        self.shuffle.invoke()
        self.assertFalse(self.tasks)
        self.assertIn("state unreadable", self.rotation.config.call_args.kwargs["text"])

    def test_cancelled_workflow_restores_actions(self):
        self.enable()
        self.workflow.generate_next_session.return_value = None
        self.shuffle.invoke()
        self.finish()
        self.assertIn("cancelled", self.status.config.call_args.kwargs["text"])
        self.assertEqual(self.shuffle.config.call_args.kwargs["state"], "normal")
        self.assertEqual(self.new.config.call_args.kwargs["state"], "normal")

    def test_import_in_progress_blocks_opportunity(self):
        self.enable()
        self.buttons["Import CSV..."].cget.return_value = "disabled"
        self.shuffle.invoke()
        self.assertFalse(self.tasks)

    def test_name_dialog_runs_on_ui_thread_and_trims(self):
        import threading
        import time
        self.thread_patch.stop()
        self.enable()
        main_thread = threading.get_ident()
        writes = []
        done = threading.Event()
        def generate(*args, **kwargs):
            try:
                self.assertEqual(kwargs["source_name"], "Zulu")
                chosen = kwargs["output_name_callback"]("Zulu - Deal 1")
                if chosen is not None and chosen.strip():
                    writes.append(chosen)
                    return SimpleNamespace(output_name=chosen, uris=("uri",))
                return None
            finally:
                done.set()
        self.workflow.generate_next_session.side_effect = generate
        def name(*args, **kwargs):
            self.assertEqual(threading.get_ident(), main_thread)
            self.assertEqual(kwargs["initialvalue"], "Zulu - Deal 1")
            return "  User name  "
        with patch("ui.simpledialog.askstring", side_effect=name):
            self.shuffle.invoke()
            deadline = time.monotonic() + 3
            while time.monotonic() < deadline:
                self.root.after.call_args.args[1]()
                if done.is_set() and self.shuffle.config.call_args.kwargs.get("state") == "normal":
                    break
                time.sleep(0.005)
        self.assertTrue(done.is_set())
        self.assertEqual(writes, ["User name"])
        self.assertIn("Generated: User name", self.status.config.call_args.kwargs["text"])

    def test_pending_start_new_requires_confirmation_and_cancel_preserves_source(self):
        from copy import deepcopy
        self.source = {"active": self.active(), "pending": {"candidate": None, "operation_id": "pending-id"}}
        before = deepcopy(self.source)
        self.enable()
        self.assertEqual(self.new.config.call_args.kwargs["state"], "normal")
        with patch("ui.messagebox.askyesno", return_value=False) as confirm:
            self.new.invoke()
        text = confirm.call_args.args[1]
        for phrase in ("pending deal is incomplete", "abandon", "remaining current rotation",
                       "left untouched", "will not delete or modify", "fresh rotation", "source playlist"):
            self.assertIn(phrase, text)
        self.assertEqual(self.source, before)
        self.assertFalse(self.tasks)
        self.workflow.start_new_rotation.assert_not_called()
        self.workflow.resume_pending_session.assert_not_called()

    def test_confirmed_pending_start_new_forwards_exact_operation_id(self):
        self.source = {"active": self.active(), "pending": {"candidate": None, "operation_id": "pending-id"}}
        self.enable()
        with patch("ui.messagebox.askyesno", return_value=True) as confirm:
            self.new.invoke()
            self.assertEqual(len(self.tasks), 1)
            self.assertEqual(self.new.config.call_args.kwargs["state"], "disabled")
            self.finish()
        confirm.assert_called_once()
        self.workflow.start_new_rotation.assert_called_once()
        self.assertEqual(self.workflow.start_new_rotation.call_args.kwargs["abandon_pending_operation_id"],
                         "pending-id")
        self.assertEqual(self.workflow.start_new_rotation.call_args.kwargs["source_name"], "Zulu")
        self.workflow.resume_pending_session.assert_not_called()
        self.workflow.generate_next_session.assert_not_called()

    def test_pending_status_reports_committed_remaining_not_projected_depletion(self):
        active = {"completed_sessions": 4, "original_unique_count": 288,
                  "undealt": {str(n): {"duration_ms": 1000} for n in range(206)}}
        source = {"active": active, "pending": {"candidate": None, "uris": [str(n) for n in range(20)]}}
        text = ui.opportunity_status(source)
        self.assertIn("Session 5 incomplete", text)
        self.assertIn("206 of 288 tracks remaining", text)
        self.assertNotIn("186 of 288", text)

    def test_completed_deals_are_not_reclaimed_during_legacy_review(self):
        from opportunity_workflow import OPPORTUNITY_PLAYLIST_DESCRIPTION
        playlists = [
            {"id": "new", "name": "Deal 1", "description": OPPORTUNITY_PLAYLIST_DESCRIPTION},
            {"id": "old", "name": "Old deal", "description": ui.MANAGED_PLAYLIST_DESCRIPTION},
            {"id": "ordinary", "name": "Legacy ordinary", "description": ui.MANAGED_PLAYLIST_DESCRIPTION},
        ]
        with patch("ui.load_managed_playlist_ids", return_value=set()), patch(
                "ui.load_reviewed_legacy_playlist_ids", return_value=set()), patch(
                "ui.load_legacy_opportunity_playlist_ids", return_value={"old"}), patch(
                "ui.messagebox.askyesno", return_value=True) as ask, patch(
                "ui.add_managed_playlist_id") as manage, patch(
                "ui.add_reviewed_legacy_playlist_id") as reviewed:
            self.real_legacy_review(playlists, Mock())
        ask.assert_called_once()
        manage.assert_called_once_with("ordinary")
        reviewed.assert_called_once_with("ordinary")

    def test_status_candidate_and_complete(self):
        candidate = self.active(5)
        candidate["completed_sessions"] = 0
        source = {"active": self.active(), "pending": {"candidate": candidate}}
        self.assertIn("Session 1 incomplete", ui.opportunity_status(source))
        self.assertIn("5 of 5", ui.opportunity_status(source))
        source = {"active": self.active(0), "pending": None}
        self.assertIn("Rotation complete", ui.opportunity_status(source))
        self.assertIn("0 of 5", ui.opportunity_status(source))
        self.assertEqual(ui.opportunity_status(None), "No rotation yet")


if __name__ == "__main__":
    unittest.main()
