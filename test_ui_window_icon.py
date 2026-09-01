import unittest
import sys
import types
from pathlib import Path
from unittest.mock import Mock, patch

try:
    import requests
except ModuleNotFoundError:
    requests = types.ModuleType("requests")
    requests.get = Mock()
    requests.post = Mock()
    requests.put = Mock()
    sys.modules["requests"] = requests

import ui


class WindowIconTests(unittest.TestCase):
    def test_asset_path_is_relative_to_ui_module(self):
        module_path = Path(r"C:\Program Files\RoyalShuffle\ui.py")

        with patch.object(ui, "__file__", str(module_path)):
            result = ui.application_asset_path(
                ui.WINDOW_ICON_RELATIVE_PATH
            )

        self.assertEqual(
            result,
            module_path.parent / "assets" / "royalshuffle.ico",
        )

    @patch("ui.log_debug")
    def test_configures_existing_icon_without_logging(self, log_debug):
        root = Mock()

        ui.configure_window_icon(root)

        root.iconbitmap.assert_called_once_with(
            str(
                Path(ui.__file__).resolve().parent
                / "assets"
                / "royalshuffle.ico"
            )
        )
        log_debug.assert_not_called()

    @patch("ui.log_debug")
    def test_icon_load_failure_is_logged_without_propagating(self, log_debug):
        root = Mock()
        root.iconbitmap.side_effect = RuntimeError("sensitive path details")

        ui.configure_window_icon(root)

        log_debug.assert_called_once_with(
            "RoyalShuffle window icon could not be loaded; "
            "exception_type=RuntimeError"
        )
        self.assertNotIn(
            "sensitive path details",
            log_debug.call_args.args[0],
        )


if __name__ == "__main__":
    unittest.main()
