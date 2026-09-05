# royalshuffle
Transparent, user-controlled true random shuffle for Spotify playlists.

## Linux/WSL CLI

RoyalShuffle 0.5.0rc2 is the current Linux CLI release candidate. Native Linux
acceptance is still pending. This prerelease has been validated under Ubuntu
WSL2 and automated Ubuntu CI and is intended for native-Linux acceptance
testing. It is distributed through GitHub as source, a wheel, and an sdist; it
is not published on PyPI and is not a standalone executable or distro package.

RoyalShuffle supports Python 3.10 and 3.12. The CLI does not require Tkinter;
Tkinter is needed only to run the optional desktop GUI from source. Spotify
authentication requires the Spotify account to be allowlisted as a user of the
RoyalShuffle Spotify Developer app while the app remains in development mode.

### Install the Linux release candidate

For native-Linux acceptance, install the attached wheel in a fresh virtual
environment. Do not use the source archive or clone the default branch.
Prerequisites are Python 3.10 or 3.12, the Python `venv` module, and pip. On
distributions that split these from Python, install the corresponding venv
package first (for example, `python3-venv`). Then:

```bash
python3 -m venv ~/royalshuffle-0.5.0rc2-test
source ~/royalshuffle-0.5.0rc2-test/bin/activate
python -m pip install --upgrade pip
python -m pip install "https://github.com/royalj54/royalshuffle/releases/download/v0.5.0-rc.2/royalshuffle-0.5.0rc2-py3-none-any.whl"
royalshuffle --version
royalshuffle diagnostics
```

The version command must report `RoyalShuffle 0.5.0rc2`. Follow the
[native Linux acceptance checklist](docs/native-linux-acceptance.md) for the
complete external test procedure.

### Install from source

Source installation is intended for development. Clone the repository and
check out the immutable release tag before installing so the obsolete default
branch is not installed accidentally:

```bash
git clone https://github.com/royalj54/royalshuffle.git
cd royalshuffle
git checkout v0.5.0-rc.2
python3 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install .
```

For development, activate a virtual environment and use an editable install:

```bash
python -m pip install -e .
```

The source tree can also be used without installation with
`python3 -m cli --help`, but an installed virtual environment is recommended.

To upgrade a source installation, fetch or download the newer tagged source,
activate the same virtual environment, and reinstall it. Do not upgrade from
the moving default branch:

```bash
git fetch --tags
git checkout <new-release-tag>
python -m pip install --upgrade .
```

To uninstall the program:

```bash
python -m pip uninstall royalshuffle
```

Uninstalling the package does not delete saved authentication, configuration,
state, diagnostics, or exported CSV files.

### Developer tests

The headless core/CLI suite does not require Tkinter:

```bash
python -m unittest test_app_paths test_auth_logging test_cli test_cli_phase3 test_playlist_import test_playlist_import_workflow test_playlist_service test_royalshuffle_workflow test_session_service test_spotify_client
```

Run the complete suite, including GUI tests, with:

```bash
python -m unittest discover -v
```

On a minimal Linux installation without Tkinter, the five GUI-dependent test
modules are reported as skipped. Other import failures remain test failures.

The Linux/WSL CLI supports:

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

Authentication prints the Spotify authorization URL and supports either the
localhost callback or pasting the complete callback URL. Run
`royalshuffle auth`, authorize RoyalShuffle in the browser, then either press
Enter to wait for `http://127.0.0.1:8888/callback` or paste the complete
callback URL shown by the browser.

On Linux, RoyalShuffle uses these locations unless the corresponding XDG
environment variable is set:

| Data | Default location |
| --- | --- |
| Authentication token | `~/.config/royalshuffle/token.json` |
| Managed playlist state | `~/.local/state/royalshuffle/managed_playlists.json` |
| Other application state | `~/.local/state/royalshuffle/` |
| Diagnostics log | `~/.local/state/royalshuffle/Diagnostics/royalshuffle_debug.log` |
| CSV exports | `~/.local/share/royalshuffle/Exports/` |

`royalshuffle diagnostics` prints the resolved paths and contains no access
token. When reporting a failure, include the Linux distribution, Python and
RoyalShuffle versions, the exact command and exit code, redacted diagnostics,
and whether authentication used the automatic browser callback or pasted-URL
fallback. Also report any partial Spotify playlist ID printed after a failed
write. Review the diagnostics log before sharing it, then report the result by
the channel requested by the release coordinator or in the
[GitHub issue tracker](https://github.com/royalj54/royalshuffle/issues).

`shuffle` accepts a Spotify playlist ID, URI, URL, or an unambiguous exact
playlist name. It creates or updates the managed `- RANDOM` output using
RoyalShuffle's true-random mode. RoyalShuffle requests `public: false` when it
creates an output; under Spotify Web API semantics, this asks Spotify not to
publish the playlist on the user's profile or in search, but is not an access-
control guarantee. Managed outputs are identified by stored Spotify playlist
IDs, not by their names. If a write fails after an output exists, RoyalShuffle
leaves that output in place and reports confirmed tracks written versus the
total intended instead of silently rolling it back.

`export` accepts the same playlist reference formats as `shuffle` and writes the
playlist in its current order, including duplicates and local items, using the
RoyalShuffle CSV schema. By default it writes `<playlist name>.csv` under the
platform data directory (`$XDG_DATA_HOME/royalshuffle/Exports`, or
`~/.local/share/royalshuffle/Exports` when unset). `--output` accepts an explicit
CSV filename and creates missing parent directories. Export never silently
overwrites an existing file.

`import` requires `--name` and strictly validates Spotify track URI syntax
locally before creating a playlist. It preserves CSV row order and duplicate entries
exactly; it is not a shuffle operation. Imported playlists are ordinary private
playlists, not managed RoyalShuffle outputs. If population fails after creation,
the partial playlist is preserved and the command reports its ID and confirmed
track count. Incomplete writes return exit code 8, while Ctrl+C returns 130.

Building distributions requires the `build` package. Build isolation installs
the declared setuptools requirement automatically:

```bash
python -m pip install build
python -m build
```

Exit codes:

| Code | Meaning |
| ---: | --- |
| 0 | Success |
| 2 | Invalid command-line usage |
| 3 | Authentication or saved-session requirement |
| 4 | Network or Spotify API failure |
| 5 | Spotify quota or retry-later condition |
| 6 | Invalid local state or internal failure |
| 7 | No eligible source playlists |
| 8 | Output playlist exists, but its write did not complete |
| 130 | Interrupted with Ctrl+C |
