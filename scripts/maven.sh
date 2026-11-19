#!/usr/bin/env sh
set -eu
. "$(dirname "$0")/tool-env.sh"
cd "$PROJECT_ROOT/backend"
export MAVEN_USER_HOME="$PROJECT_ROOT/.cache/maven"
exec ./mvnw -Dmaven.repo.local="$PROJECT_ROOT/.cache/m2" "$@"
