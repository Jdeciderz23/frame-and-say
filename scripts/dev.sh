#!/usr/bin/env sh
set -eu
. "$(dirname "$0")/tool-env.sh"
cd "$PROJECT_ROOT"
command -v node >/dev/null 2>&1 || { echo 'Node.js 24 is required.' >&2; exit 1; }
needs_frontend=true
for arg in "$@"; do
  if [ "$arg" = "--backend-only" ]; then needs_frontend=false; fi
done
if [ "$needs_frontend" = true ] && [ ! -d frontend/node_modules ]; then
  echo 'Run pnpm --dir frontend install --frozen-lockfile first.' >&2
  exit 1
fi
./scripts/maven.sh -q -DskipTests package
exec node scripts/dev.mjs "$@"
