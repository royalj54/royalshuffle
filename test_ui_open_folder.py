import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import ui


class OpenRoyalShuffleFolderTests(unittest.TestCase):
    def test_success_opens_folder_path_unchanged(self):
        folder = Path(r"C:\Users\Example\Documents\RoyalShuffle")

        with patch(
            "ui.ensure_royalshuffle_folder",
            return_value=folder,
        ), patch("ui.os.startfile", create=True) as startfile, patch(
            "ui.messagebox.showerror"
        ) as showerror:
            ui.open_royalshuffle_folder(Mock())

        startfile.assert_called_once_with(str(folder))
        showerror.assert_not_called()

    def test_redirected_onedrive_path_needs_no_special_handling(self):
        folder = Path(
            r"C:\Users\Example\OneDrive\Documents\RoyalShuffle"
        )

        with patch(
            "ui.ensure_royalshuffle_folder",
            return_value=folder,
        ), patch("ui.os.startfile", create=True) as startfile:
            ui.open_royalshuffle_folder(Mock())

        startfile.assert_called_once_with(str(folder))

    def test_folder_creation_failure_logs_and_shows_parented_error(self):
        parent = Mock()

        with patch(
            "ui.ensure_royalshuffle_folder",
            return_value=None,
        ), patch("ui.os.startfile", create=True) as startfile, patch(
            "ui.log_debug"
        ) as log_debug, patch(
            "ui.messagebox.showerror"
        ) as showerror:
            ui.open_royalshuffle_folder(parent)

        startfile.assert_not_called()
        log_debug.assert_called_once_with(
            "RoyalShuffle user folder could not be resolved or created"
        )
        showerror.assert_called_once_with(
            "Open RoyalShuffle Folder",
            ui.OPEN_FOLDER_ERROR_MESSAGE,
            parent=parent,
        )

    def test_startfile_failure_is_logged_and_shown_without_propagating(self):
        parent = Mock()
        folder = Path(r"C:\Documents\RoyalShuffle")
        error = OSError("sensitive raw error")
        error.winerror = 5

        with patch(
            "ui.ensure_royalshuffle_folder",
            return_value=folder,
        ), patch(
            "ui.os.startfile",
            side_effect=error,
            create=True,
        ), patch("ui.log_debug") as log_debug, patch(
            "ui.messagebox.showerror"
        ) as showerror:
            ui.open_royalshuffle_folder(parent)

        log_debug.assert_called_once_with(
            "RoyalShuffle user folder open failed; "
            "exception_type=OSError; winerror=5"
        )
        showerror.assert_called_once_with(
            "Open RoyalShuffle Folder",
            ui.OPEN_FOLDER_ERROR_MESSAGE,
            parent=parent,
        )
        self.assertNotIn(
            "sensitive raw error",
            showerror.call_args.args[1],
        )

    def test_button_invokes_handler_without_spotify_state(self):
        parent = Mock()
        button = Mock()

        with patch("ui.tk.Button", return_value=button) as button_type, patch(
            "ui.open_royalshuffle_folder"
        ) as open_folder:
            result = ui.create_open_folder_button(parent)
            command = button_type.call_args.kwargs["command"]
            command()

        self.assertIs(result, button)
        self.assertNotIn("state", button_type.call_args.kwargs)
        open_folder.assert_called_once_with(parent)
        button.place.assert_called_once_with(
            relx=1.0,
            x=-10,
            y=10,
            anchor="ne",
        )


if __name__ == "__main__":
    unittest.main()
