# Run from any directory; all build inputs are relative to this script.
[CmdletBinding()]
param(
    [string]$BuildEnvironment = 'C:\Users\royal\royalshuffle-build-env',
    [string]$ReleaseRoot = (Join-Path $env:USERPROFILE 'RoyalShuffle-Releases')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-SingleValue([string]$Text, [string]$Pattern, [string]$Label) {
    $matchesFound = [regex]::Matches($Text, $Pattern)
    if ($matchesFound.Count -ne 1) { throw "Expected exactly one $Label in Windows build metadata." }
    return $matchesFound[0].Groups[1].Value.Trim()
}

function Get-WindowsVersion([string]$Root) {
    $installer = Get-Content -LiteralPath (Join-Path $Root 'installer\RoyalShuffle.iss') -Raw
    $metadata = Get-Content -LiteralPath (Join-Path $Root 'packaging\version_info.txt') -Raw
    $version = Get-SingleValue $installer '(?m)^AppVersion=([^\r\n]+)\r?$' 'AppVersion'
    if ($version -notmatch '^\d+\.\d+\.\d+$') { throw "Unsupported Windows AppVersion: $version (expected X.Y.Z)." }
    $numeric = "$version.0"
    $checks = @{
        VersionInfoVersion = Get-SingleValue $installer '(?m)^VersionInfoVersion=([^\r\n]+)\r?$' 'VersionInfoVersion'
        FileVersion = Get-SingleValue $metadata 'StringStruct\("FileVersion",\s*"([^"]+)"\)' 'FileVersion'
        filevers = (Get-SingleValue $metadata 'filevers=\(([^)]+)\)' 'filevers') -replace '\s', '' -replace ',', '.'
        prodvers = (Get-SingleValue $metadata 'prodvers=\(([^)]+)\)' 'prodvers') -replace '\s', '' -replace ',', '.'
    }
    foreach ($field in $checks.Keys) {
        if ($checks[$field] -cne $numeric) { throw "$field is $($checks[$field]); expected $numeric." }
    }
    $product = Get-SingleValue $metadata 'StringStruct\("ProductVersion",\s*"([^"]+)"\)' 'ProductVersion'
    $filename = Get-SingleValue $installer '(?m)^OutputBaseFilename=([^\r\n]+)\r?$' 'OutputBaseFilename'
    $outputDir = Get-SingleValue $installer '(?m)^OutputDir=([^\r\n]+)\r?$' 'OutputDir'
    if ($product -cne $version) { throw "ProductVersion is $product; expected $version." }
    if ($filename -cne "RoyalShuffle-$version-Setup") { throw "Unexpected OutputBaseFilename: $filename." }
    if ($outputDir -cne 'output') { throw "Expected installer staging OutputDir=output; found $outputDir." }
    # app_metadata.APP_VERSION belongs to the independent Linux/Core/CLI line.
    return $version
}

function Get-ArtifactVersionInfo([string]$Path) {
    return [System.Diagnostics.FileVersionInfo]::GetVersionInfo($Path)
}

function Assert-ArtifactVersion([string]$Path, [string]$Version) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Expected build artifact missing: $Path" }
    $info = Get-ArtifactVersionInfo $Path
    $fixedFile = "$($info.FileMajorPart).$($info.FileMinorPart).$($info.FileBuildPart).$($info.FilePrivatePart)"
    $fixedProduct = "$($info.ProductMajorPart).$($info.ProductMinorPart).$($info.ProductBuildPart).$($info.ProductPrivatePart)"
    if ($fixedFile -cne "$Version.0" -or $fixedProduct -cne "$Version.0" -or
        $info.FileVersion.Trim() -cne "$Version.0" -or $info.ProductVersion.Trim() -cne $Version) {
        throw "Version mismatch in ${Path}: fixed file=$fixedFile, fixed product=$fixedProduct, string file=$($info.FileVersion), string product=$($info.ProductVersion); expected $Version / $Version.0."
    }
}

function Find-InnoSetup {
    $candidates = @()
    foreach ($base in @($env:LOCALAPPDATA, $env:ProgramFiles, ${env:ProgramFiles(x86)})) {
        if ($base) {
            $relative = if ($base -eq $env:LOCALAPPDATA) { 'Programs\Inno Setup 6\ISCC.exe' } else { 'Inno Setup 6\ISCC.exe' }
            $candidates += Join-Path $base $relative
        }
    }
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
    $onPath = Get-Command ISCC.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($onPath) { return $onPath.Source }
    throw "Inno Setup compiler missing. Checked: $($candidates -join '; '); PATH (ISCC.exe)."
}

$root = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $root
try {
    $status = & git status --porcelain=v1 --untracked-files=all
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect Git working tree; run from a Windows/Core Git checkout or worktree.' }
    if ($status) { throw "Release build requires a clean Git working tree. Commit or resolve tracked/untracked changes first.`n$($status -join "`n")" }
    foreach ($inputFile in @('RoyalShuffle.spec', 'installer\RoyalShuffle.iss', 'packaging\version_info.txt')) {
        if (-not (Test-Path -LiteralPath $inputFile -PathType Leaf)) { throw "Windows build input missing: $inputFile" }
    }
    $version = Get-WindowsVersion $root
    $python = Join-Path $BuildEnvironment 'Scripts\python.exe'
    if (-not (Test-Path -LiteralPath $python -PathType Leaf)) { throw "Windows build environment missing: $python. Supply -BuildEnvironment if installed elsewhere." }
    $iscc = Find-InnoSetup
    & $python -m PyInstaller --version
    if ($LASTEXITCODE -ne 0) { throw "PyInstaller unavailable in build environment: $python" }

    $exe = Join-Path $root 'dist\RoyalShuffle.exe'
    $staging = Join-Path $root "installer\output\RoyalShuffle-$version-Setup.exe"
    # Remove only expected staging outputs so a successful command cannot reuse stale artifacts.
    foreach ($artifact in @($exe, $staging)) {
        if (Test-Path -LiteralPath $artifact) { Remove-Item -LiteralPath $artifact }
    }
    & $python -m PyInstaller --clean --noconfirm RoyalShuffle.spec
    if ($LASTEXITCODE -ne 0) { throw 'PyInstaller build failed.' }
    Assert-ArtifactVersion $exe $version
    & $iscc (Join-Path $root 'installer\RoyalShuffle.iss')
    if ($LASTEXITCODE -ne 0) { throw 'Inno Setup compilation failed.' }
    Assert-ArtifactVersion $staging $version

    $releaseDir = Join-Path $ReleaseRoot $version
    New-Item -ItemType Directory -Path $releaseDir -Force | Out-Null
    $releaseInstaller = Join-Path $releaseDir "RoyalShuffle-$version-Setup.exe"
    Copy-Item -LiteralPath $staging -Destination $releaseInstaller -Force
    Assert-ArtifactVersion $releaseInstaller $version
    $hash = (Get-FileHash -LiteralPath $releaseInstaller -Algorithm SHA256).Hash
    if ($hash -cne (Get-FileHash -LiteralPath $staging -Algorithm SHA256).Hash) { throw 'Release copy hash differs from staging installer.' }
    $checksumFile = Join-Path $releaseDir "RoyalShuffle-Windows-$version-SHA256SUMS.txt"
    "$hash  RoyalShuffle-$version-Setup.exe" | Set-Content -LiteralPath $checksumFile -Encoding ascii
    Write-Host "`nRoyalShuffle Windows Build`n--------------------------"
    Write-Host "Version:       $version`nEXE metadata:  PASS`nInstaller:     PASS`nRelease copy:  PASS`nSHA-256:       $hash"
    Write-Host "`nRelease artifacts:`n$releaseInstaller`n$checksumFile`n`nREADY FOR MANUAL ACCEPTANCE"
}
finally {
    Pop-Location
}
