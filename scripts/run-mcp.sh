#!/usr/bin/env bash
# 以 stdio 方式启动 MCP 服务，供 Claude Code 等客户端调用。
# stdout 只能输出 MCP 协议消息，提示信息一律写 stderr。
# 默认只读；--allow-write（DMCONNECT_MCP_ALLOW_WRITE=true）允许写入，
# --full-access（DMCONNECT_MCP_FULL_ACCESS=true）连危险 SQL 和 Redis 管理命令也放行。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BACKEND="$ROOT_DIR/target/electron-backend"

if [[ ! -f "$BACKEND/app/dm-connect.jar" ]]; then
  echo "Java 后端尚未构建，请先执行 ./scripts/prepare-electron-backend.sh" >&2
  exit 1
fi

JAVA="$BACKEND/runtime/bin/java"
if [[ ! -x "$JAVA" ]]; then
  JAVA="$(/usr/libexec/java_home -v 17)/bin/java"
fi

# 与桌面端一致：不继承系统 SOCKS 代理，避免内网数据库连接被送进代理软件。
exec "$JAVA" -Dfile.encoding=UTF-8 -DsocksProxyHost= \
  -cp "$BACKEND/app/dm-connect.jar:$BACKEND/app/lib/*" \
  com.dmconnect.mcp.McpLauncher "$@"
