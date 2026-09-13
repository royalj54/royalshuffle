# Windows

RoyalShuffle for Windows is the stable Tkinter desktop application. The
current release is **0.4.2**.

## Install

Download the installer and published checksum from
[RoyalShuffle for Windows 0.4.2](https://github.com/royalj54/royalshuffle/releases/tag/windows%2Fv0.4.2).
Verify the download against the checksum supplied with the release, run the
installer, and launch RoyalShuffle from the Start menu or optional desktop
shortcut.

The immutable release source is tagged `windows/v0.4.2`. The retained
`windows/0.4.2` branch is the Windows 0.4.x maintenance line; general Windows
and shared/Core development belongs on `main`.

## Basic usage

1. Select **Connect Spotify** and complete authorization in the browser.
2. Select a source playlist.
3. Select **Royal Shuffle** to create or update its managed randomized copy.
4. Use **Export CSV...** to save a playlist in its current order.
5. Use **Import CSV...** to create a private playlist while preserving valid
   row order and duplicates.
6. Use **Open RoyalShuffle Folder** to reach exports and diagnostics.

RoyalShuffle reports partial writes rather than silently deleting a playlist
that Spotify has already created or partly populated.

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

Install PyInstaller in the build environment, then build from the repository
root:

```powershell
python -m pip install pyinstaller
python -m PyInstaller --clean --noconfirm RoyalShuffle.spec
```

The executable is written to `dist/RoyalShuffle.exe`. Its icon and Windows
version resources come from `assets/royalshuffle.ico` and
`packaging/version_info.txt`.

With Inno Setup 6 installed, compile `installer/RoyalShuffle.iss`. The script
packages `dist/RoyalShuffle.exe` and writes the versioned installer beneath
`installer/output/`.

Before publishing a Windows release, verify the executable and installer
versions, smoke-test the installed application, and publish a checksum with
the installer.
