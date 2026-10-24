<#
.SYNOPSIS
  Points the installed ConnectQuick (PC) at your own server in the cloud and switches off the local
  server that the installer may have added. No rebuild and no reinstall needed.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File Scripts\usar-servidor.ps1 -Relay wss://34-56-78-90.sslip.io
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Relay
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
if (Test-Path $serverLink) { Remove-Item $serverLink -Force; Write-Host "Servidor local desactivado al iniciar Windows." }

try {
    Set-Content -Path (Join-Path $app "relay.txt") -Value $Relay -Encoding ASCII
} catch {
    throw "No pude escribir relay.txt en $app. Abre PowerShell como administrador y repite."
}
Write-Host "Servidor configurado: $Relay" -ForegroundColor Green

Start-Process (Join-Path $app "ConnectQuick.WindowsHost.exe")
Write-Host ""
Write-Host "ConnectQuick se abrió con el servidor nuevo. Ahora, en el PC: 'Vincular un teléfono',"
Write-Host "y en el móvil vuelve a escanear el QR (el teléfono aprende la dirección del servidor desde el QR)."
