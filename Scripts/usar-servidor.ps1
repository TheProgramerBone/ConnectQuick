<#
.SYNOPSIS
  Points the installed ConnectQuick (PC) at your own server in the cloud and switches off the local
  server that the installer may have added. No rebuild and no reinstall needed.

.PARAMETER ConServidorLocal
  Keeps the local server the installer added (it must have been installed with the "servidor" task) and
  makes the PC use BOTH: the cloud one and its own on the home network. The phone then picks whichever
  answers first, so at home the control never leaves your Wi-Fi and it keeps working if the cloud is down.
  The phone learns the local address by itself the next time it connects (or from a new QR).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File Scripts\usar-servidor.ps1 -Relay wss://34-56-78-90.sslip.io
  powershell -ExecutionPolicy Bypass -File Scripts\usar-servidor.ps1 -Relay wss://34-56-78-90.sslip.io -ConServidorLocal
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Relay,
    [switch]$ConServidorLocal
)
$ErrorActionPreference = "Stop"

if ($Relay -notmatch '^wss?://[^/\s]+/?$') { throw "La dirección debe ser como wss://34-56-78-90.sslip.io (sin rutas)." }
$Relay = $Relay.TrimEnd('/')

# Where the installer may have put the app (per-user install by default, or Program Files as admin).
$candidates = @(
    (Join-Path $env:LOCALAPPDATA "Programs\ConnectQuick"),
    (Join-Path $env:ProgramFiles "ConnectQuick")
) | Where-Object { Test-Path (Join-Path $_ "ConnectQuick.WindowsHost.exe") }
if (-not $candidates) { throw "No encuentro ConnectQuick instalado. Instálalo primero (dist\ConnectQuick-Setup.exe)." }
$app = $candidates | Select-Object -First 1
Write-Host "ConnectQuick instalado en: $app"

Write-Host "Cerrando ConnectQuick y el servidor local (si estaban abiertos)..."
Get-Process -Name "ConnectQuick.WindowsHost", "connectquick-server" -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 1

# The local server is no longer needed: stop it from starting with Windows.
$startup = [Environment]::GetFolderPath("Startup")
$serverLink = Join-Path $startup "ConnectQuick Servidor.lnk"
$localServer = Join-Path $app "backend\start-backend.cmd"
if ($ConServidorLocal -and -not (Test-Path $localServer)) {
    throw "No encuentro el servidor local en $app\backend. Reinstala ConnectQuick marcando la opción del servidor, o usa el script sin -ConServidorLocal."
}
if (-not $ConServidorLocal -and (Test-Path $serverLink)) { Remove-Item $serverLink -Force; Write-Host "Servidor local desactivado al iniciar Windows." }

try {
    # Line 1 = main server; line 2 (optional) = this PC's own server ({lan} = its address on the home network).
    $lines = @($Relay)
    if ($ConServidorLocal) { $lines += "ws://{lan}:8443" }
    Set-Content -Path (Join-Path $app "relay.txt") -Value $lines -Encoding ASCII
} catch {
    throw "No pude escribir relay.txt en $app. Abre PowerShell como administrador y repite."
}
Write-Host "Servidor configurado: $Relay" -ForegroundColor Green

if ($ConServidorLocal) {
    Start-Process $localServer -WorkingDirectory (Join-Path $app "backend") -WindowStyle Minimized
    Write-Host "Servidor local en marcha (puerto 8443). Si Windows pregunta por el cortafuegos, permite redes privadas." -ForegroundColor Green
}
Start-Process (Join-Path $app "ConnectQuick.WindowsHost.exe")
Write-Host ""
Write-Host "ConnectQuick se abrió con el servidor nuevo. Ahora, en el PC: 'Vincular un teléfono',"
Write-Host "y en el móvil vuelve a escanear el QR (el teléfono aprende la dirección del servidor desde el QR)."
