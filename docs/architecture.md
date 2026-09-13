# Architecture

RoyalShuffle has one shared Python implementation for Windows and Linux and a
separate native Android implementation.

## Shared Python/Core

The shared modules own Spotify authentication and transport, session state,
playlist discovery and selection, shuffle orchestration, managed-playlist
tracking, and CSV import/export. Platform-aware paths are resolved by the same
Core so Windows and Linux can use their native storage conventions.

`main` is the canonical integration branch for this shared Core, both Python
interfaces, CI, packaging definitions, tests, and repository-wide
documentation. Shared fixes for Windows and Linux normally land on `main` once
they pass both relevant test surfaces.

## Windows

Windows uses `ui.py` as its Tkinter desktop interface. `RoyalShuffle.spec`,
`packaging/version_info.txt`, the application icon, and
`installer/RoyalShuffle.iss` produce the Windows executable and installer.
Windows releases have an independent version track.

See the [Windows guide](windows/README.md).

## Linux

Linux uses `cli.py`, exposed as the `royalshuffle` console command by
`pyproject.toml`. The Python project builds a platform-neutral wheel and source
distribution. Linux follows XDG configuration, state, and data locations when
those variables are set.

See the [Linux guide](linux/README.md).

## Android

Android is a separate Kotlin/Gradle application with its own authentication,
Spotify API, shuffle, diagnostics, UI, and test code. It remains on the
[`android-prototype`](https://github.com/royalj54/royalshuffle/tree/android-prototype/android)
branch while its product and branch lifecycle are still under development.
It is not built from the Python source tree on `main`.

## Branch boundaries

- General shared/Core, Windows GUI, and Linux CLI development targets `main`.
- Supported older platform releases may use a temporary maintenance branch.
- Immutable releases are identified by tags.
- Android-only work remains on the Android development line until an explicit
  integration decision is made.
