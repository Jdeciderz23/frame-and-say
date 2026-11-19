#!/usr/bin/env sh
# Source this file; keep tool caches inside this repository.
PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
RUNTIME_BASE="$HOME/.cache/codex-runtimes/codex-primary-runtime/dependencies"
if ! command -v node >/dev/null 2>&1 && [ -x "$RUNTIME_BASE/node/bin/node" ]; then
  PATH="$RUNTIME_BASE/node/bin:$PATH"
fi
if ! command -v pnpm >/dev/null 2>&1 && [ -x "$RUNTIME_BASE/bin/fallback/pnpm" ]; then
  PATH="$RUNTIME_BASE/bin/fallback:$PATH"
fi
export PATH
mkdir -p "$PROJECT_ROOT/.cache"
export COREPACK_HOME="$PROJECT_ROOT/.cache/corepack"
export npm_config_cache="$PROJECT_ROOT/.cache/npm"
