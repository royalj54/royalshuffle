# royalshuffle
Transparent, user-controlled true random shuffle for Spotify playlists.

## Linux/WSL CLI

The initial read-only CLI supports:

```text
royalshuffle --version
royalshuffle --help
royalshuffle diagnostics
royalshuffle auth
royalshuffle playlists
```

Authentication prints the Spotify authorization URL and supports either the
localhost callback or pasting the complete callback URL. Playlist-writing
commands are not included yet.

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
| 130 | Interrupted with Ctrl+C |
