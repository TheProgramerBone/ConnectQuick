<#
  Arranca el servidor (relé) para pruebas en la red local. Requiere Node.js >= 22.5.
  Uso:  .\Scripts\start-backend.ps1
  Deja esta ventana abierta mientras pruebas. El servidor habla ws:// sin cifrar: SOLO red local de pruebas.
#>
$ErrorActionPreference = "Stop"
$backend = Join-Path (Split-Path -Parent $PSScriptRoot) "Backend"
if (-not (Get-Command node -ErrorAction SilentlyContinue)) { throw "Falta Node.js >= 22.5: https://nodejs.org" }
Push-Location $backend
try {
    if (-not (Test-Path "node_modules")) { npm install }
    $ips = Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" } | Select-Object -ExpandProperty IPAddress
    Write-Host "Direccion(es) de este PC en la red (usa una en CONNECTQUICK_RELAY): " ($ips -join ", ") -ForegroundColor Cyan
    Write-Host "Ejemplo: ws://$($ips | Select-Object -First 1):8443"
    npm start
} finally { Pop-Location }
