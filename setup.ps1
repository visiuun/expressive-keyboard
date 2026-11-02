# Sets up everything needed to build Expressive Keyboard on Windows and builds the APK.
# Installs a JDK and the Android SDK into .tools\ if they aren't already on this machine.
#
#   .\setup.ps1             debug build  -> app\build\outputs\apk\debug\
#   .\setup.ps1 -Release    release build -> app\build\outputs\apk\release\
#   .\setup.ps1 -NoBuild    only install the toolchain

param([switch]$Release, [switch]$NoBuild)
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$root = $PSScriptRoot
$tools = Join-Path $root ".tools"
$cmdlineTools = "https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip"
$jdkUrl = "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse"

function Get-JavaMajor($javaExe) {
    # java -version prints to stderr; read it through cmd so PowerShell doesn't treat it as an error
    $line = cmd /c "`"$javaExe`" -version 2>&1" | Select-Object -First 1
    if ("$line" -match '"(\d+)') { return [int]$Matches[1] }
    return 0
}

# ---------------------------------------------------------------- JDK 17+
$java = $null
if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe") -and (Get-JavaMajor "$env:JAVA_HOME\bin\java.exe") -ge 17) {
    $java = $env:JAVA_HOME
} elseif (Get-Command java -ErrorAction SilentlyContinue) {
    $exe = (Get-Command java).Source
    if ((Get-JavaMajor $exe) -ge 17) { $java = Split-Path (Split-Path $exe) }
}
if (-not $java) {
    $local = Get-ChildItem "$tools\jdk" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $local) {
        Write-Host "Downloading JDK 21 (Temurin)..."
        New-Item -ItemType Directory -Force "$tools\jdk" | Out-Null
        Invoke-WebRequest $jdkUrl -OutFile "$tools\jdk.zip"
        Expand-Archive "$tools\jdk.zip" "$tools\jdk" -Force
        Remove-Item "$tools\jdk.zip"
        $local = Get-ChildItem "$tools\jdk" -Directory | Select-Object -First 1
    }
    $java = $local.FullName
}
$env:JAVA_HOME = $java
Write-Host "JDK: $java"

# ---------------------------------------------------------------- Android SDK
$sdk = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk", "$tools\android-sdk") |
    Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $sdk) { $sdk = "$tools\android-sdk" }
$sdkmanager = Get-ChildItem "$sdk\cmdline-tools" -Recurse -Filter sdkmanager.bat -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $sdkmanager) {
    Write-Host "Downloading Android command-line tools..."
    New-Item -ItemType Directory -Force "$sdk\cmdline-tools" | Out-Null
    Invoke-WebRequest $cmdlineTools -OutFile "$tools\cmdline-tools.zip"
    Expand-Archive "$tools\cmdline-tools.zip" "$sdk\cmdline-tools" -Force
    Remove-Item "$tools\cmdline-tools.zip"
    Rename-Item "$sdk\cmdline-tools\cmdline-tools" "latest"
    $sdkmanager = Get-Item "$sdk\cmdline-tools\latest\bin\sdkmanager.bat"
}
$env:ANDROID_HOME = $sdk
Write-Host "Android SDK: $sdk"

# Accept the Android SDK licenses (these are the hashes sdkmanager writes when you accept them),
# so Gradle can download the platform and build tools it needs on its own.
Write-Host "Accepting Android SDK licenses..."
New-Item -ItemType Directory -Force "$sdk\licenses" | Out-Null
if (-not (Test-Path "$sdk\licenses\android-sdk-license")) {
    "`n24333f8a63b6825ea9c5514f83c2829b004d1fee`nd56f5187479451eabf01fb78af6dfcb131a6481e`n8933bad161af4178b1185d1a37fbf41ea5269c55" |
        Set-Content -Encoding ascii -NoNewline "$sdk\licenses\android-sdk-license"
}
if (-not (Test-Path "$sdk\platform-tools")) {
    cmd /c "`"$($sdkmanager.FullName)`" --sdk_root=`"$sdk`" platform-tools >nul 2>&1"
}

"sdk.dir=$($sdk -replace '\\', '/')" | Set-Content -Encoding ascii (Join-Path $root "local.properties")

if ($NoBuild) { Write-Host "Toolchain ready."; exit 0 }

# ---------------------------------------------------------------- build
Push-Location $root
try {
    $task = if ($Release) { "assembleRelease" } else { "assembleDebug" }
    # Gradle logs warnings to stderr; don't let PowerShell treat them as errors
    $ErrorActionPreference = "Continue"
    cmd /c "`"$root\gradlew.bat`" $task 2>&1"
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed ($LASTEXITCODE)" }
    $out = if ($Release) { "app\build\outputs\apk\release" } else { "app\build\outputs\apk\debug" }
    Write-Host ""
    Write-Host "Done. APKs:"
    Get-ChildItem $out -Filter *.apk | ForEach-Object { Write-Host "  $($_.FullName)" }
} finally {
    Pop-Location
}
