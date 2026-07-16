#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"
source "$ROOT_DIR/scripts/node-env.sh"
dmconnect_use_node
APP_VERSION="$(node -p "require('./desktop/package.json').version")"
REQUESTED_ARCH="${1:-}"
MACHINE_ARCH="${REQUESTED_ARCH:-$(uname -m)}"
case "$MACHINE_ARCH" in
  arm64) ELECTRON_ARCH="arm64" ;;
  x86_64|x64) ELECTRON_ARCH="x64" ;;
  *) echo "不支持的 macOS 架构：$MACHINE_ARCH" >&2; exit 1 ;;
esac
export JAVA_HOME="${JAVA_HOME_17:-$(/usr/libexec/java_home -v 17)}"
export PATH="$JAVA_HOME/bin:$PATH"

mvn clean verify
DMCONNECT_SKIP_MAVEN=true "$ROOT_DIR/scripts/prepare-electron-backend.sh"
"$ROOT_DIR/scripts/generate-icon.sh"

cd "$ROOT_DIR/desktop"
npm ci
npm test
npm run build
CSC_IDENTITY_AUTO_DISCOVERY=false npx electron-builder --mac dir --"$ELECTRON_ARCH"

APP_PATH="$ROOT_DIR/target/electron/mac-$ELECTRON_ARCH/数据库连接工具.app"
if [[ ! -d "$APP_PATH" ]]; then
  APP_PATH="$(find "$ROOT_DIR/target/electron" -maxdepth 2 -type d -name '数据库连接工具.app' -print -quit)"
fi
if [[ -z "$APP_PATH" || ! -d "$APP_PATH" ]]; then
  echo "Electron 应用包生成失败" >&2
  exit 1
fi

codesign --force --deep --sign - "$APP_PATH"
codesign --verify --deep --strict --verbose=2 "$APP_PATH"

DMG_STAGE="$(mktemp -d "${TMPDIR:-/tmp}/dm-connect-dmg.XXXXXX")"
DMG_PATH="$ROOT_DIR/target/installer/数据库连接工具-$APP_VERSION-$ELECTRON_ARCH.dmg"
mkdir -p "$ROOT_DIR/target/installer"
rm -f "$DMG_PATH"
trap 'rm -rf "$DMG_STAGE"' EXIT
ditto "$APP_PATH" "$DMG_STAGE/数据库连接工具.app"
ln -s /Applications "$DMG_STAGE/Applications"
hdiutil create -volname "数据库连接工具" -srcfolder "$DMG_STAGE" -ov -format UDZO "$DMG_PATH" >/dev/null
codesign --force --sign - "$DMG_PATH"
codesign --verify --verbose=2 "$DMG_PATH"
hdiutil verify "$DMG_PATH" >/dev/null

UPDATE_ZIP="$ROOT_DIR/target/installer/DM-Connect-$APP_VERSION-$ELECTRON_ARCH.app.zip"
rm -f "$UPDATE_ZIP"
ditto -c -k --keepParent "$APP_PATH" "$UPDATE_ZIP"

echo "DMG 已生成：$DMG_PATH"
echo "自动更新包已生成：$UPDATE_ZIP"
