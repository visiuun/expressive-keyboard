# Builds signed release APKs and publishes them as a GitHub release.
# Needs keystore.properties (see README, "Signing") and the GitHub CLI (gh) logged in.
#
#   tools\release.ps1                       uses versionName from app\build.gradle.kts
#   tools\release.ps1 -Notes "What changed"
#   tools\release.ps1 -NotesFile notes.md

param([string]$Notes = "", [string]$NotesFile = "")
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot
Set-Location $root

if (-not (Test-Path "keystore.properties")) { throw "keystore.properties is missing: release APKs must be signed with the release key." }
$version = (Select-String -Path "app\build.gradle.kts" -Pattern 'versionName = "([^"]+)"').Matches[0].Groups[1].Value
$tag = "v$version"
Write-Host "Releasing $tag"

& .\setup.ps1 -Release
if ($LASTEXITCODE -ne 0) { throw "Build failed" }

# The in-app updater picks ExpressiveKeyboard-<abi>.apk for the device, else ExpressiveKeyboard.apk (universal).
$out = Join-Path $root "build\release-$version"
Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $out | Out-Null
$src = "app\build\outputs\apk\release"
Copy-Item "$src\app-universal-release.apk" "$out\ExpressiveKeyboard.apk"
foreach ($abi in "arm64-v8a", "armeabi-v7a", "x86_64", "x86") {
    Copy-Item "$src\app-$abi-release.apk" "$out\ExpressiveKeyboard-$abi.apk"
}

$ghArgs = @($tag, "--title", "Expressive Keyboard $version")
if ($NotesFile) { $ghArgs += @("--notes-file", $NotesFile) }
elseif ($Notes) { $ghArgs += @("--notes", $Notes) }
else { $ghArgs += "--generate-notes" }
gh release create @ghArgs (Get-ChildItem $out -Filter *.apk | ForEach-Object FullName)
if ($LASTEXITCODE -ne 0) { throw "gh release create failed" }
Write-Host "Published $tag"
