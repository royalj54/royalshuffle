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
| 8 | Managed output exists, but its write did not complete |
| 130 | Interrupted with Ctrl+C |
