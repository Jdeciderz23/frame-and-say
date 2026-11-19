#!/usr/bin/env sh
set -eu
. "$(dirname "$0")/tool-env.sh"
cd "$PROJECT_ROOT"
command -v pnpm >/dev/null 2>&1 || { echo 'Install pnpm 11.25.0 first (see README).' >&2; exit 1; }
CI=true pnpm --dir frontend install --frozen-lockfile
pnpm --dir frontend test
pnpm --dir frontend build
./scripts/maven.sh -B verify
