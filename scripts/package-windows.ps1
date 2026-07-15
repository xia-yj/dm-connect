$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

& (Join-Path $root "scripts/prepare-electron-backend.ps1")
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Set-Location (Join-Path $root "desktop")
& npm ci
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& npm test
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& npm run build
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$env:CSC_IDENTITY_AUTO_DISCOVERY = "false"
& npx electron-builder --win nsis --x64
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
