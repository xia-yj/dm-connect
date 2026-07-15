$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not $env:JAVA_HOME) {
  throw "JAVA_HOME 未设置，请使用 JDK 17 运行此脚本"
}

& mvn -q -DskipTests package
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$backend = Join-Path $root "target/electron-backend"
Remove-Item -Recurse -Force $backend -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force (Join-Path $backend "app/lib") | Out-Null

$version = (& mvn -q -DforceStdout help:evaluate "-Dexpression=project.version").Trim()
Copy-Item (Join-Path $root "target/dm-connect-$version.jar") (Join-Path $backend "app/dm-connect.jar")

& mvn -q dependency:copy-dependencies `
  "-DincludeScope=runtime" `
  "-DexcludeArtifactIds=javafx-controls,javafx-graphics,javafx-base,richtextfx,flowless,undofx,reactfx,wellbehavedfx" `
  "-DoutputDirectory=$backend/app/lib"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

& (Join-Path $env:JAVA_HOME "bin/jlink.exe") `
  "--add-modules" "java.base,java.desktop,java.logging,java.management,java.naming,java.net.http,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.sql.rowset,java.transaction.xa,java.xml,java.xml.crypto,jdk.charsets,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.localedata,jdk.security.auth,jdk.unsupported" `
  "--strip-debug" "--no-header-files" "--no-man-pages" "--compress=2" "--include-locales=en,zh" `
  "--output" (Join-Path $backend "runtime")
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Electron Java 后端已准备：$backend"
