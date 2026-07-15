#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

export JAVA_HOME="${JAVA_HOME_17:-$(/usr/libexec/java_home -v 17)}"
export PATH="$JAVA_HOME/bin:$PATH"

if [[ "${DMCONNECT_SKIP_MAVEN:-false}" != "true" ]]; then
  mvn -q -DskipTests package
fi

BACKEND="$ROOT_DIR/target/electron-backend"
rm -rf "$BACKEND"
mkdir -p "$BACKEND/app/lib"
APP_VERSION="$(mvn -q -DforceStdout help:evaluate -Dexpression=project.version)"
cp "$ROOT_DIR/target/dm-connect-$APP_VERSION.jar" "$BACKEND/app/dm-connect.jar"

mvn -q dependency:copy-dependencies \
  -DincludeScope=runtime \
  -DexcludeArtifactIds=javafx-controls,javafx-graphics,javafx-base,richtextfx,flowless,undofx,reactfx,wellbehavedfx \
  -DoutputDirectory="$BACKEND/app/lib"

jlink \
  --add-modules java.base,java.desktop,java.logging,java.management,java.naming,java.net.http,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.sql.rowset,java.transaction.xa,java.xml,java.xml.crypto,jdk.charsets,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.localedata,jdk.security.auth,jdk.unsupported \
  --strip-debug \
  --no-header-files \
  --no-man-pages \
  --compress=2 \
  --include-locales=en,zh \
  --output "$BACKEND/runtime"

if ! "$BACKEND/runtime/bin/java" --list-modules | grep -q '^jdk.charsets@'; then
  echo "Java Runtime 缺少达梦驱动所需的 jdk.charsets 模块" >&2
  exit 1
fi
if ! "$BACKEND/runtime/bin/java" --list-modules | grep -q '^java.sql.rowset@'; then
  echo "Java Runtime 缺少达梦驱动所需的 java.sql.rowset 模块" >&2
  exit 1
fi

echo "Electron Java 后端已准备：$BACKEND"
