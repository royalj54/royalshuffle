# Development and testing

This guide applies to the shared Python/Core, Windows GUI, and Linux CLI on
`main`. Android remains a separate development line; use its
[branch documentation](https://github.com/royalj54/royalshuffle/tree/android-prototype/android)
for repository-visible Android instructions.

## Python environment

RoyalShuffle supports Python 3.10 and 3.12 for the Linux/Core release line;
Windows CI uses Python 3.12.

```bash
python -m venv .venv
python -m pip install --upgrade pip
python -m pip install build -r requirements.txt
```

Activate the environment using the command appropriate for your shell.

## Test suites

Run the headless shared Core/CLI suite with:

```bash
python -m unittest test_app_paths test_auth_logging test_cli test_cli_phase3 test_playlist_import test_playlist_import_workflow test_playlist_service test_royalshuffle_workflow test_session_service test_spotify_client
```

Run all tests, including GUI tests when Tkinter is available, with:

```bash
python -m unittest discover -v
```

The GitHub Actions matrix runs:

- Linux/Core packaging and tests on Python 3.10;
- Linux/Core packaging and tests on Python 3.12;
- the complete Windows regression suite on Python 3.12.

## Build surfaces

- Python wheel and source distribution: `python -m build`
- Windows executable: `python -m PyInstaller --clean --noconfirm RoyalShuffle.spec`
- Windows installer: compile `installer/RoyalShuffle.iss` with Inno Setup 6

See the [Windows](windows/README.md) and [Linux](linux/README.md) guides for
platform details.

## Contribution workflow

1. Start a focused branch from the appropriate current development line.
2. Target `main` for shared Python/Core, Windows GUI, Linux CLI, CI, packaging,
   and repository documentation changes.
3. Keep Android-only Kotlin/Gradle changes on the Android development line.
4. Add focused tests for behavior changes.
5. Run the relevant local suite and allow the complete CI matrix to finish.
6. Keep release-specific backports separate from general development.

Never build a published release from a moving branch when an immutable release
tag exists.
