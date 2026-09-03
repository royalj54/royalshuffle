[Setup]
AppName=RoyalShuffle
AppVersion=0.4.1
AppPublisher=RoyalShuffle
VersionInfoVersion=0.4.1.0
VersionInfoProductName=RoyalShuffle
VersionInfoDescription=RoyalShuffle Spotify Playlist Randomizer
DefaultDirName={autopf}\RoyalShuffle
DefaultGroupName=RoyalShuffle
OutputDir=output
OutputBaseFilename=RoyalShuffle-0.4.1-Setup
SetupIconFile=..\assets\royalshuffle.ico
UninstallDisplayIcon={app}\RoyalShuffle.exe
Compression=lzma
SolidCompression=yes
WizardStyle=modern

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional icons:"; Flags: unchecked

[Files]
Source: "..\dist\RoyalShuffle.exe"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\RoyalShuffle"; Filename: "{app}\RoyalShuffle.exe"
Name: "{autodesktop}\RoyalShuffle"; Filename: "{app}\RoyalShuffle.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\RoyalShuffle.exe"; Description: "Launch RoyalShuffle"; Flags: nowait postinstall skipifsilent
