<#
.SYNOPSIS
  Builds everything ConnectQuick ships to end users, in one run:
    dist\ConnectQuick-Setup.exe   Windows installer for the Host (tray app)
    dist\ConnectQuick.apk         Android Controller APK (debug-signed, installable)

.DESCRIPTION
  Installs missing prerequisites (via winget): .NET 8 SDK, JDK 17, Inno Setup 6.
  Downloads the Android command-line tools + SDK 34 into %LOCALAPPDATA%\ConnectQuick\android-sdk
  if no Android SDK is configured. Then publishes the Host, compiles the installer and builds the APK.

.PARAMETER Relay
  WebSocket URL of the Backend baked into the installer (written to relay.txt next to the Host exe).
  Default: ws://<this PC's LAN IP>:8443. The phone does NOT need it - it reads the relay from the QR.

.EXAMPLE
  .\build-all.ps1
  .\build-all.ps1 -Relay wss://relay.example.com
#>
[CmdletBinding()]
param(
    [string]$Relay,
    [string]$AppVersion = "0.1.0"
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$dist = Join-Path $root "dist"
$work = Join-Path $root "dist\_work"
$sdkRoot = Join-Path $env:LOCALAPPDATA "ConnectQuick\android-sdk"

function Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }

# Runs a native command and fails the script on a non-zero exit code.
function Exec([string]$file, [string[]]$arguments, [string]$cwd = $root) {
    Push-Location $cwd
    try {
        & $file @arguments
        if ($LASTEXITCODE -ne 0) { throw "'$file $($arguments -join ' ')' failed with exit code $LASTEXITCODE" }
    } finally { Pop-Location }
}

function Refresh-Path {
    $env:Path = [Environment]::GetEnvironmentVariable("Path", "Machine") + ";" +
                [Environment]::GetEnvironmentVariable("Path", "User")
}

function Winget-Install([string]$id) {
    if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
        throw "winget not found. Install 'App Installer' from the Microsoft Store (or install $id manually) and re-run."
    }
    & winget install --id $id -e --silent --accept-package-agreements --accept-source-agreements
    # 0 = ok; -1978335189 (0x8A15002B) = already installed / no applicable upgrade.
    if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne -1978335189) { throw "winget could not install $id (exit $LASTEXITCODE)" }
    Refresh-Path
}

# ---------------------------------------------------------------- prerequisites
Step "Checking .NET 8 SDK"
function Has-Dotnet8 { (Get-Command dotnet -ErrorAction SilentlyContinue) -and ((& dotnet --list-sdks) -match '^8\.') }
if (-not (Has-Dotnet8)) { Winget-Install "Microsoft.DotNet.SDK.8" }
if (-not (Has-Dotnet8)) { throw ".NET 8 SDK still not found after install. Open a new terminal and re-run." }

Step "Checking JDK 17+"
function Find-Jdk {
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) { return $env:JAVA_HOME }
    $hit = Get-ChildItem "$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Java", "$env:ProgramFiles\Microsoft" `
        -Directory -Filter "jdk*" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^jdk-?(17|18|19|20|21)' } | Sort-Object Name -Descending | Select-Object -First 1
    if ($hit) { return $hit.FullName }
    return $null
}
$jdk = Find-Jdk
if (-not $jdk) { Winget-Install "EclipseAdoptium.Temurin.17.JDK"; $jdk = Find-Jdk }
if (-not $jdk) { throw "JDK 17 not found after install. Open a new terminal and re-run." }
$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$env:Path"
Write-Host "JAVA_HOME = $jdk"

Step "Checking Inno Setup 6"
function Find-Iscc {
    $c = Get-Command iscc -ErrorAction SilentlyContinue
    if ($c) { return $c.Source }
    foreach ($p in "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe", "$env:ProgramFiles\Inno Setup 6\ISCC.exe", "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe") {
        if (Test-Path $p) { return $p }
    }
    return $null
}
$iscc = Find-Iscc
if (-not $iscc) { Winget-Install "JRSoftware.InnoSetup"; $iscc = Find-Iscc }
if (-not $iscc) { throw "Inno Setup (ISCC.exe) not found after install." }

Step "Checking Android SDK (platform 34, build-tools 34.0.0)"
$existing = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT) | Where-Object { $_ -and (Test-Path "$_\platforms\android-34") -and (Test-Path "$_\build-tools\34.0.0") } | Select-Object -First 1
if ($existing) {
    $sdkRoot = $existing
} else {
    $sdkmanager = Join-Path $sdkRoot "cmdline-tools\latest\bin\sdkmanager.bat"
    if (-not (Test-Path $sdkmanager)) {
        Write-Host "Downloading Android command-line tools..."
        New-Item -ItemType Directory -Force $sdkRoot | Out-Null
        $zip = Join-Path $env:TEMP "cq-cmdline-tools.zip"
        $tmp = Join-Path $env:TEMP "cq-cmdline-tools"
        Invoke-WebRequest "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" -OutFile $zip
        if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
        Expand-Archive $zip -DestinationPath $tmp
        New-Item -ItemType Directory -Force (Join-Path $sdkRoot "cmdline-tools") | Out-Null
        Move-Item (Join-Path $tmp "cmdline-tools") (Join-Path $sdkRoot "cmdline-tools\latest")
        Remove-Item $zip, $tmp -Recurse -Force
    }
    Write-Host "Accepting licenses and installing SDK packages (first run downloads ~200 MB)..."
    1..60 | ForEach-Object { "y" } | & $sdkmanager "--sdk_root=$sdkRoot" --licenses | Out-Null
    Exec $sdkmanager @("--sdk_root=$sdkRoot", "platform-tools", "platforms;android-34", "build-tools;34.0.0")
}
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot
Write-Host "ANDROID_HOME = $sdkRoot"

# ---------------------------------------------------------------- relay
if (-not $Relay) {
    $ip = Get-NetIPConfiguration -ErrorAction SilentlyContinue |
        Where-Object { $_.IPv4DefaultGateway -and $_.NetAdapter.Status -eq "Up" } |
        Select-Object -First 1 -ExpandProperty IPv4Address | Select-Object -First 1 -ExpandProperty IPAddress
    if (-not $ip) { $ip = "localhost" }
    $Relay = "ws://${ip}:8443"
}
Write-Host "Relay baked into the installer: $Relay"

# ---------------------------------------------------------------- clean output
if (Test-Path $dist) { Remove-Item $dist -Recurse -Force }
New-Item -ItemType Directory -Force $work | Out-Null

# ---------------------------------------------------------------- Windows Host
Step "Publishing Windows Host (self-contained, win-x64)"
$publishDir = Join-Path $work "host"
Exec "dotnet" @("publish", "src\ConnectQuick.WindowsHost\ConnectQuick.WindowsHost.csproj",
    "-c", "Release", "-r", "win-x64", "--self-contained", "true", "-o", $publishDir, "-nologo") (Join-Path $root "WindowsHost")

Step "Building installer with Inno Setup"
$iss = Join-Path $work "ConnectQuick.iss"
@"
#define AppName "ConnectQuick"
[Setup]
AppId={{6F1D2B7A-3C54-4E0A-9B1E-5A8C0D4E7F21}
AppName={#AppName}
AppVersion=$AppVersion
AppPublisher=ConnectQuick
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=$dist
OutputBaseFilename=ConnectQuick-Setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\ConnectQuick.WindowsHost.exe
CloseApplications=yes

[Languages]
Name: "spanish"; MessagesFile: "compiler:Languages\Spanish.isl"

[Tasks]
Name: "autostart"; Description: "Iniciar ConnectQuick con Windows"; Flags: checkedonce

[Files]
Source: "$publishDir\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\ConnectQuick"; Filename: "{app}\ConnectQuick.WindowsHost.exe"
Name: "{userstartup}\ConnectQuick"; Filename: "{app}\ConnectQuick.WindowsHost.exe"; Tasks: autostart

[Run]
Filename: "{app}\ConnectQuick.WindowsHost.exe"; Description: "Abrir ConnectQuick ahora"; Flags: nowait postinstall skipifsilent
"@ | Set-Content -Path $iss -Encoding UTF8
# relay.txt is written into the publish dir so the Host reads it next to its exe (see HostConfig.cs).
Set-Content -Path (Join-Path $publishDir "relay.txt") -Value $Relay -Encoding ASCII
Exec $iscc @("/Q", $iss)

# ---------------------------------------------------------------- Android APK
Step "Building Android APK (first run downloads Gradle + dependencies)"
Exec (Join-Path $root "Android\gradlew.bat") @("assembleDebug", "--no-daemon") (Join-Path $root "Android")
$apk = Join-Path $root "Android\app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apk)) { throw "APK not found at $apk" }
Copy-Item $apk (Join-Path $dist "ConnectQuick.apk")

# ---------------------------------------------------------------- done
Remove-Item $work -Recurse -Force
Step "Done"
Get-ChildItem $dist | ForEach-Object { Write-Host ("  {0}  ({1:N1} MB)" -f $_.FullName, ($_.Length / 1MB)) }
Write-Host "`nPC:      run dist\ConnectQuick-Setup.exe"
Write-Host "Phone:   copy dist\ConnectQuick.apk to the phone and open it (allow 'install unknown apps')"
Write-Host "         or with USB debugging:  adb install dist\ConnectQuick.apk"
