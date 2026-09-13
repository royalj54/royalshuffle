# Linux CLI

RoyalShuffle **0.5.0rc2** is the current Linux CLI release candidate. It has
passed Ubuntu CI and WSL validation; native Linux acceptance remains pending.
It is published as a wheel and source distribution, not as a standalone
executable or distribution package.

## Install RC2

Use Python 3.10 or 3.12 and a fresh virtual environment. Install the published
wheel rather than a moving branch or the release source archive:

```bash
python3 -m venv ~/royalshuffle-0.5.0rc2-test
source ~/royalshuffle-0.5.0rc2-test/bin/activate
python -m pip install --upgrade pip
python -m pip install "https://github.com/royalj54/royalshuffle/releases/download/v0.5.0-rc.2/royalshuffle-0.5.0rc2-py3-none-any.whl"
royalshuffle --version
royalshuffle diagnostics
```

The version command must report `RoyalShuffle 0.5.0rc2`. Complete release
testing with the [native Linux acceptance checklist](../native-linux-acceptance.md).

## CLI commands

```text
royalshuffle --version
royalshuffle --help
royalshuffle diagnostics
royalshuffle auth
royalshuffle playlists
royalshuffle shuffle <playlist>
royalshuffle export <playlist> [--output <csv-file>]
royalshuffle import <csv> --name "Playlist Name"
```

Playlist references may be Spotify IDs, URIs, URLs, or unambiguous exact
names. Authentication supports the localhost callback and a pasted complete
callback URL. Imported playlists preserve CSV row order and duplicates;
managed shuffle outputs are excluded from future source selection.

## Files and diagnostics

Unless the corresponding XDG variable is set, Linux uses:

| Data | Default location |
| --- | --- |
| Authentication token | `~/.config/royalshuffle/token.json` |
| Managed playlist state | `~/.local/state/royalshuffle/managed_playlists.json` |
| Other state | `~/.local/state/royalshuffle/` |
| Diagnostics | `~/.local/state/royalshuffle/Diagnostics/royalshuffle_debug.log` |
| CSV exports | `~/.local/share/royalshuffle/Exports/` |

`royalshuffle diagnostics` reports resolved paths and environment information
without printing the access token. Review diagnostic logs before sharing them.

## Develop and test

For current development, use `main`:

```bash
git clone https://github.com/royalj54/royalshuffle.git
cd royalshuffle
python3 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip build -r requirements.txt
python -m unittest test_app_paths test_auth_logging test_cli test_cli_phase3 test_playlist_import test_playlist_import_workflow test_playlist_service test_royalshuffle_workflow test_session_service test_spotify_client
python -m build
```

The headless Core/CLI suite does not require Tkinter. Full discovery skips the
GUI-dependent modules on minimal Linux installations where Tkinter is absent:

```bash
python -m unittest discover -v
```

See the repository-wide [development guide](../development.md) for CI and
cross-platform contribution expectations.
