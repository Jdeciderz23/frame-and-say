#!/usr/bin/env sh
set -eu
. "$(dirname "$0")/tool-env.sh"
cd "$PROJECT_ROOT"
command -v node >/dev/null 2>&1 || { echo 'Node.js 24 is required.' >&2; exit 1; }
node --input-type=module - <<'JS'
import { readFileSync, writeFileSync } from 'node:fs';
import { randomBytes } from 'node:crypto';
const password = () => randomBytes(24).toString('hex');
const template = readFileSync('.env.example', 'utf8')
  .replace('replace-with-local-random-password', password())
  .replace('replace-with-another-local-random-password', password())
  .replace('replace-with-local-random-password', password());
try {
  writeFileSync('.env', template, { flag: 'wx', mode: 0o600 });
  console.log('Created untracked .env with local random passwords. Existing files are never overwritten.');
} catch (error) {
  if (error.code === 'EEXIST') console.log('.env already exists; kept unchanged.');
  else throw error;
}
JS
