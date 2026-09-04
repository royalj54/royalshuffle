# royalshuffle
Transparent, user-controlled true random shuffle for Spotify playlists.

## Linux/WSL CLI

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
localhost callback or pasting the complete callback URL.

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

`import` requires `--name` and validates the entire CSV and Spotify catalog
before creating a playlist. It preserves CSV row order and duplicate entries
exactly; it is not a shuffle operation. Imported playlists are ordinary private
playlists, not managed RoyalShuffle outputs. If population fails after creation,
the partial playlist is preserved and the command reports its ID and confirmed
track count. Incomplete writes return exit code 8, while Ctrl+C returns 130.

Building or installing from source requires Python 3.10 or newer and a modern
build environment with setuptools 61 or newer. Standard build isolation will
install the declared build requirement automatically.

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
