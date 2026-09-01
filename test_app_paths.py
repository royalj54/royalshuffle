import ctypes
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import app_paths


class FakeFunction:
    def __init__(self, callback):
        self.callback = callback

    def __call__(self, *args):
        return self.callback(*args)


class AppPathsTests(unittest.TestCase):
    def test_documents_folder_uses_known_folder_api_and_frees_path(self):
        documents_path = r"C:\Users\Example\Redirected Documents"
        path_buffer = ctypes.create_unicode_buffer(documents_path)
        calls = {"freed": False, "uninitialized": False}

        def get_known_folder_path(folder_id, flags, token, output):
            resolved_id = ctypes.cast(
                folder_id,
                ctypes.POINTER(app_paths.GUID),
            ).contents
            self.assertEqual(resolved_id.Data1, 0xFDD39AD0)
            self.assertEqual(flags, 0)
            self.assertIsNone(token)
            ctypes.cast(
                output,
                ctypes.POINTER(ctypes.c_wchar_p),
            )[0] = ctypes.cast(path_buffer, ctypes.c_wchar_p)
            return 0

        shell32 = type("FakeShell32", (), {})()
        shell32.SHGetKnownFolderPath = FakeFunction(
            get_known_folder_path
        )
        ole32 = type("FakeOle32", (), {})()
        ole32.CoInitializeEx = FakeFunction(lambda reserved, mode: 0)
        ole32.CoTaskMemFree = FakeFunction(
            lambda pointer: calls.__setitem__("freed", True)
        )
        ole32.CoUninitialize = FakeFunction(
            lambda: calls.__setitem__("uninitialized", True)
        )

        with patch(
            "app_paths.ctypes.WinDLL",
            side_effect=[shell32, ole32],
        ):
            result = app_paths.documents_folder()

        self.assertEqual(result, Path(documents_path))
        self.assertTrue(calls["freed"])
        self.assertTrue(calls["uninitialized"])

    def test_user_folders_are_composed_below_documents(self):
        documents = Path(r"D:\User Documents")
        with patch("app_paths.documents_folder", return_value=documents):
            self.assertEqual(
                app_paths.royalshuffle_folder(),
                documents / "RoyalShuffle",
            )
            self.assertEqual(
                app_paths.exports_folder(),
                documents / "RoyalShuffle" / "Exports",
            )
            self.assertEqual(
                app_paths.diagnostics_folder(),
                documents / "RoyalShuffle" / "Diagnostics",
            )

    def test_folders_are_created_lazily(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            documents = Path(temporary_directory) / "Documents"
            exports = documents / "RoyalShuffle" / "Exports"

            with patch(
                "app_paths.documents_folder",
                return_value=documents,
            ):
                self.assertFalse(exports.exists())
                self.assertEqual(app_paths.ensure_exports_folder(), exports)
                self.assertTrue(exports.is_dir())

    def test_existing_folder_is_accepted(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            diagnostics = Path(temporary_directory) / "Diagnostics"
            diagnostics.mkdir()

            with patch(
                "app_paths.diagnostics_folder",
                return_value=diagnostics,
            ):
                self.assertEqual(
                    app_paths.ensure_diagnostics_folder(),
                    diagnostics,
                )

    def test_directory_failure_is_graceful(self):
        with patch(
            "app_paths.exports_folder",
            side_effect=OSError("unavailable"),
        ):
            self.assertIsNone(app_paths.ensure_exports_folder())


if __name__ == "__main__":
    unittest.main()
