import io
import subprocess
import sys
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import Mock, patch

import requests

import cli
from app_metadata import APP_VERSION
from session_service import InvalidSavedSessionError, NoSavedSessionError
from spotify_client import SpotifyQuotaExceededError, SpotifyRetryLaterError


class CliTests(unittest.TestCase):
    def run_cli(self, arguments):
        stdout = io.StringIO()
        stderr = io.StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            result = cli.main(arguments)
        return result, stdout.getvalue(), stderr.getvalue()

    def test_version_uses_centralized_version(self):
        stdout = io.StringIO()
        with redirect_stdout(stdout), self.assertRaises(SystemExit) as caught:
            cli.main(["--version"])

        self.assertEqual(caught.exception.code, cli.EXIT_SUCCESS)
        self.assertEqual(stdout.getvalue(), f"RoyalShuffle {APP_VERSION}\n")

    def test_missing_command_is_usage_error(self):
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as caught:
            cli.main([])

        self.assertEqual(caught.exception.code, cli.EXIT_USAGE)

    @patch("cli._is_interactive", return_value=False)
    @patch("cli.token_file")
    @patch("cli.diagnostics_folder", return_value=Path("/state/Diagnostics"))
    @patch("cli.data_folder", return_value=Path("/data"))
    @patch("cli.state_folder", return_value=Path("/state"))
    @patch("cli.config_folder", return_value=Path("/config"))
    def test_diagnostics_reports_paths_without_token_contents(
        self,
        _config,
        _state,
        _data,
        _diagnostics,
        token_file,
        _interactive,
    ):
        token_path = Mock()
        token_path.__str__ = Mock(return_value="/config/token.json")
        token_path.is_file.return_value = True
        token_file.return_value = token_path

        result, stdout, stderr = self.run_cli(["diagnostics"])

        self.assertEqual(result, cli.EXIT_SUCCESS)
        self.assertEqual(stderr, "")
        self.assertIn(f"Config directory: {Path('/config')}", stdout)
        self.assertIn("Token path: /config/token.json", stdout)
        self.assertIn("Saved session exists: yes", stdout)
        self.assertNotIn("access_token", stdout)
        self.assertNotIn("refresh_token", stdout)

    @patch("cli._is_interactive", return_value=False)
    def test_noninteractive_auth_refuses_without_starting_oauth(self, _interactive):
        with patch("cli.create_authentication_session") as create:
            result, _stdout, stderr = self.run_cli(["auth"])

        self.assertEqual(result, cli.EXIT_AUTH)
        self.assertIn("interactive terminal", stderr)
        create.assert_not_called()

    @patch("cli._is_interactive", return_value=True)
    @patch("cli.wait_for_spotify_callback", return_value=None)
    @patch("cli.save_token_data")
    @patch("cli.finish_authentication", return_value={"access_token": "secret"})
    @patch("cli.open_authentication_browser", return_value=False)
    @patch("cli.create_authentication_session")
    def test_interactive_auth_prints_url_and_saves_without_printing_token(
        self,
        create,
        _open_browser,
        _finish,
        save,
        _wait,
        _interactive,
    ):
        create.return_value = {
            "auth_url": "https://accounts.example/authorize",
            "code_verifier": "verifier",
            "state": "state",
        }
        stdout = io.StringIO()
        cli.authenticate_interactively(
            stdout,
            io.StringIO("http://127.0.0.1:8888/callback?code=code&state=state\n"),
        )

        output = stdout.getvalue()
        self.assertIn("https://accounts.example/authorize", output)
        self.assertIn("Open the printed URL manually", output)
        self.assertIn("saved successfully", output)
        self.assertNotIn("secret", output)
        save.assert_called_once_with({"access_token": "secret"})

    @patch("cli._is_interactive", return_value=True)
    @patch("cli.wait_for_spotify_callback", return_value=None)
    @patch("cli.save_token_data")
    @patch("cli.finish_authentication", return_value={"access_token": "secret"})
    @patch("cli.open_authentication_browser", side_effect=OSError("no browser"))
    @patch("cli.create_authentication_session")
    def test_browser_failure_still_allows_manual_callback(
        self,
        create,
        _open_browser,
        _finish,
        save,
        _wait,
        _interactive,
    ):
        create.return_value = {
            "auth_url": "https://accounts.example/authorize",
            "code_verifier": "verifier",
            "state": "state",
        }

        stdout = io.StringIO()
        cli.authenticate_interactively(
            stdout,
            io.StringIO("http://127.0.0.1:8888/callback?code=code&state=state\n"),
        )

        self.assertIn("Open the printed URL manually", stdout.getvalue())
        save.assert_called_once_with({"access_token": "secret"})

    @patch("cli.eligible_source_playlists")
    @patch("cli.restore_spotify_client")
    def test_playlists_uses_shared_eligibility_and_formats_output(
        self,
        restore,
        eligible,
    ):
        client = restore.return_value
        client.get_playlists.return_value = [{"id": "all-id", "name": "All"}]
        eligible.return_value = [
            {"id": "first-id", "name": "First Playlist"},
            {"id": "second-id", "name": "Second Playlist"},
        ]

        result, stdout, stderr = self.run_cli(["playlists"])

        self.assertEqual(result, cli.EXIT_SUCCESS)
        self.assertEqual(stderr, "")
        self.assertEqual(
            stdout,
            "First Playlist\tfirst-id\nSecond Playlist\tsecond-id\n",
        )
        eligible.assert_called_once_with(client.get_playlists.return_value)

    @patch("cli.restore_spotify_client")
    def test_no_eligible_playlists_has_distinct_exit(self, restore):
        restore.return_value.get_playlists.return_value = []
        result, stdout, stderr = self.run_cli(["playlists"])

        self.assertEqual(result, cli.EXIT_NO_ELIGIBLE_PLAYLISTS)
        self.assertEqual(stdout, "")
        self.assertIn("No eligible source playlists", stderr)

    def test_expected_failures_have_stable_exit_codes(self):
        response = Mock(status_code=500)
        failures = [
            (NoSavedSessionError("missing"), cli.EXIT_AUTH),
            (InvalidSavedSessionError("invalid"), cli.EXIT_LOCAL_STATE),
            (requests.ConnectionError("offline"), cli.EXIT_API),
            (requests.HTTPError("server", response=response), cli.EXIT_API),
            (SpotifyQuotaExceededError("quota"), cli.EXIT_RETRY_LATER),
            (SpotifyRetryLaterError("later"), cli.EXIT_RETRY_LATER),
        ]
        for failure, expected in failures:
            with self.subTest(failure=type(failure).__name__), patch(
                "cli.list_playlists",
                side_effect=failure,
            ):
                result, _stdout, stderr = self.run_cli(["playlists"])
                self.assertEqual(result, expected)
                self.assertTrue(stderr)

    def test_cli_import_does_not_load_tkinter(self):
        check = subprocess.run(
            [
                sys.executable,
                "-c",
                "import sys; import cli; assert 'tkinter' not in sys.modules",
            ],
            cwd=Path(__file__).resolve().parent,
            capture_output=True,
            text=True,
        )

        self.assertEqual(check.returncode, 0, check.stderr)


if __name__ == "__main__":
    unittest.main()
