#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT_DIR/scripts/node-env.sh"
dmconnect_use_node

"$ROOT_DIR/scripts/prepare-electron-backend.sh"
cd "$ROOT_DIR/desktop"
if [[ ! -d node_modules ]]; then npm install; fi
exec npm run dev
