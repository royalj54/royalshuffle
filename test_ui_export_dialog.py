import unittest
from pathlib import Path
from unittest.mock import patch

import ui


class CsvExportDialogTests(unittest.TestCase):
    def test_dialog_receives_exports_directory_as_initialdir(self):
        exports = Path(r"C:\Users\Example\Documents\RoyalShuffle\Exports")
        outside_destination = r"D:\Other\playlist.csv"

        with patch(
            "ui.ensure_exports_folder",
            return_value=exports,
        ), patch(
            "ui.filedialog.asksaveasfilename",
            return_value=outside_destination,
        ) as save_dialog:
            result = ui.choose_csv_destination(None, "My Playlist")

        self.assertEqual(result, outside_destination)
        self.assertEqual(
            save_dialog.call_args.kwargs["initialdir"],
            str(exports),
        )
        self.assertEqual(
            save_dialog.call_args.kwargs["initialfile"],
            "My Playlist.csv",
        )

    def test_canceling_dialog_returns_empty_destination(self):
        exports = Path(r"C:\Documents\RoyalShuffle\Exports")
        with patch(
            "ui.ensure_exports_folder",
            return_value=exports,
        ), patch(
            "ui.filedialog.asksaveasfilename",
            return_value="",
        ):
            self.assertEqual(
                ui.choose_csv_destination(None, "My Playlist"),
                "",
            )

    def test_unavailable_exports_directory_uses_dialog_default(self):
        with patch(
            "ui.ensure_exports_folder",
            return_value=None,
        ), patch(
            "ui.filedialog.asksaveasfilename",
            return_value="",
        ) as save_dialog:
            ui.choose_csv_destination(None, "My Playlist")

        self.assertNotIn("initialdir", save_dialog.call_args.kwargs)


if __name__ == "__main__":
    unittest.main()
