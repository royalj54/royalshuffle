# Windows

RoyalShuffle for Windows is the stable Tkinter desktop application. The
current Windows version is **0.4.7**, prepared for acceptance. Windows **0.4.6**
was the previous accepted/released version.

## Install

For Windows 0.4.7, use the verified installer and its matching SHA-256 checksum.
Verify the
installer against that checksum, run it, and launch RoyalShuffle from the
Start menu or optional desktop shortcut. Local release-build artifacts are
placed in `%USERPROFILE%\RoyalShuffle-Releases\<version>\` as described below.

Windows has its own release version. The `release/windows/0.4` branch is the
Windows 0.4.x maintenance line; general Windows and shared/Core development
belongs on `main`.

## Current Windows features and usage

1. Select **Connect Spotify** and complete authorization in the browser.
2. Search/filter the playlist list, then select a source playlist.
3. Choose **Full Playlist**, **60M**, or **Custom...** Session Length. Custom
   accepts positive whole minutes. Timed selection uses a prefix of the
   randomized eligible tracks until the requested duration is reached, or all
   eligible tracks if the source is shorter.
4. Optionally enable **Artist Separation** to minimize adjacent tracks sharing
   the same primary artist while preserving the selected track occurrences.
5. Select **Royal Shuffle** for a True Random managed copy. Full Playlist asks
   for an editable name only when creating a new output or replacing one whose
   registered ID is confirmed missing. Cancel or a blank name prevents creation.
6. Use **Export CSV...** to save a playlist in its current order, or **Import
   CSV...** to create a private playlist preserving valid row order and duplicates.
7. Use **Open RoyalShuffle Folder** to reach exports and diagnostics.

Full Playlist outputs are bound by source playlist ID to output playlist ID;
Timed outputs are bound by source ID and canonical minute value. Names are
presentation only: source/output renames do not redirect reuse, and existing
outputs retain their current Spotify names. Legacy managed outputs are left
untouched; without a Full binding, a new bound output is created even if a
legacy output has the same name.

Managed output IDs are excluded from source selection. If a bound output is
absent from the Spotify listing, RoyalShuffle checks its exact ID directly.
Only a confirmed HTTP 404 permits replacement; other lookup failures abort.
Unsupported/local items are skipped, and zero eligible tracks abort before
output changes. Partial writes are reported rather than silently deleting a
playlist that Spotify has already created or partly populated.

## Run from source

Use Python 3.12 for parity with Windows CI:

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
python ui.py
```

Run the Windows regression suite with:

```powershell
python -m unittest discover -v
```

## Build the executable and installer

From a clean Windows/Core checkout or worktree, run:

```powershell
.\scripts\build-windows.ps1
```

The script resolves inputs relative to its own location, so it does not depend
on the checkout's absolute path. It rejects tracked or untracked Git changes.
By default it uses `C:\Users\royal\royalshuffle-build-env\Scripts\python.exe`;
that environment must contain PyInstaller and the application's dependencies.
Inno Setup 6 must already be installed. Compiler discovery checks
`%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe` first, then standard Program Files
locations and `ISCC.exe` on PATH; it does not install or modify Inno Setup.

Optional overrides:

```powershell
.\scripts\build-windows.ps1 -BuildEnvironment 'D:\Build Environments\RoyalShuffle' -ReleaseRoot 'D:\Release Artifacts'
```

`AppVersion` in `installer/RoyalShuffle.iss` is the Windows version authority.
The script checks installer `VersionInfoVersion`, packaging fixed numeric and
string versions, and the installer filename against it. `app_metadata.APP_VERSION`
is the independent Linux/Core/CLI version and is excluded from these checks.

The script runs `python.exe -m PyInstaller --clean --noconfirm RoyalShuffle.spec`,
then compiles `installer/RoyalShuffle.iss` using Inno Setup. It verifies both
fixed numeric and string FileVersion/ProductVersion in the built EXE and
installer before promoting the installer.

| Artifact | Location |
| --- | --- |
| Worktree EXE | `dist\RoyalShuffle.exe` |
| Worktree staging installer | `installer\output\RoyalShuffle-<version>-Setup.exe` |
| Canonical installer | `%USERPROFILE%\RoyalShuffle-Releases\<version>\RoyalShuffle-<version>-Setup.exe` |
| Canonical checksum | `%USERPROFILE%\RoyalShuffle-Releases\<version>\RoyalShuffle-Windows-<version>-SHA256SUMS.txt` |

The staging installer remains in the active checkout/worktree. The canonical
release directory is independent of that worktree; `-ReleaseRoot` overrides
its default parent directory. The checksum is calculated from the canonical
copied installer, with format `<SHA256>  RoyalShuffle-<version>-Setup.exe`.

On success, the script prints the version, verification results, SHA-256,
final artifact paths, and **READY FOR MANUAL ACCEPTANCE**. Smoke-test the
installed application before publication. The script does not publish a
GitHub release or create tags.
