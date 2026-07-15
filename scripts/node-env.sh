#!/usr/bin/env bash

dmconnect_use_node() {
  local bundled="$HOME/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin"
  if [[ -n "${NODE_HOME:-}" && -x "$NODE_HOME/bin/node" ]]; then
    export PATH="$NODE_HOME/bin:$PATH"
  elif [[ -x "$bundled/node" ]]; then
    export PATH="$bundled:$PATH"
  fi
  if ! command -v node >/dev/null 2>&1; then
    echo "需要 Node.js 22 或更高版本" >&2
    return 1
  fi
  local major
  major="$(node -p 'process.versions.node.split(".")[0]')"
  if (( major < 22 )); then
    echo "当前 Node.js 为 $(node --version)，需要 22 或更高版本；可通过 NODE_HOME 指定。" >&2
    return 1
  fi
}
