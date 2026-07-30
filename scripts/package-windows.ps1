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
# 发布由 GitHub Actions 的 release job 统一负责；tag 构建阶段不得隐式发布。
& npx electron-builder --win nsis --x64 --publish never
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
