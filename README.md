# RoyalShuffle

RoyalShuffle creates transparent, user-controlled randomized copies of Spotify
playlists. The repository supports three platform surfaces with independent
release tracks.

## Platforms

| Platform | Status | Get started |
| --- | --- | --- |
| **Windows** | **Stable — 0.4.2** | [Download the installer](https://github.com/royalj54/royalshuffle/releases/tag/windows%2Fv0.4.2) · [Usage and build guide](docs/windows/README.md) |
| **Linux** | **Prerelease — 0.5.0rc2** | [Download RC2](https://github.com/royalj54/royalshuffle/releases/tag/v0.5.0-rc.2) · [CLI guide](docs/linux/README.md) · [Acceptance checklist](docs/native-linux-acceptance.md) |
| **Android** | **Prototype — 0.2.0 development line** | [Android source and documentation](https://github.com/royalj54/royalshuffle/tree/android/main/android) |

Spotify authentication currently requires an account allowlisted for the
RoyalShuffle Spotify Developer app.

## Architecture

Windows and Linux share the Python/Core implementation. Windows adds a Tkinter
desktop GUI plus PyInstaller and Inno Setup packaging; Linux uses the CLI and
Python wheel/source-distribution packaging. Android is currently a separate
native Kotlin/Gradle implementation maintained on `android/main`.

Shared Windows/Linux fixes normally belong on `main`. See the
[architecture overview](docs/architecture.md) for component and branch
boundaries.

## Development and testing

Clone `main`, create a virtual environment, and install the runtime dependency:

```bash
git clone https://github.com/royalj54/royalshuffle.git
cd royalshuffle
python -m venv .venv
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
```

The CI matrix covers Linux/Core on Python 3.10 and 3.12 and the complete
Windows regression suite on Python 3.12. Platform builds, test commands, and
contributor workflow are documented in the
[development guide](docs/development.md).

## Versioning and releases

Windows, Linux, and Android currently have independent versions. Published
releases are immutable and should be reproduced from their tags rather than
from a moving branch. Existing release tags remain valid; future
platform-specific releases use platform-prefixed tags such as
`windows/v0.4.3`, `linux/v0.5.0`, and `android/v0.3.0`.

See [versioning and releases](docs/versioning.md) for the complete policy and
the preserved legacy Linux RC tag names.

## Contributing

Use a focused branch, keep shared Python changes cross-platform, add tests for
behavioral changes, and run the relevant local suites before proposing a
change. Start with the [development guide](docs/development.md) and keep
platform-specific release work separate from general development.

## License

RoyalShuffle is distributed under the [MIT License](LICENSE).
