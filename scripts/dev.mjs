import { spawn } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { resolve } from 'node:path';

const root = resolve(import.meta.dirname, '..');
const local = process.argv.includes('--local');
if (process.argv.some((arg, i) => i > 1 && arg !== '--local')) {
  console.error('Usage: ./scripts/dev.sh [--local]');
  process.exit(1);
}
const env = { ...process.env };
const envPath = resolve(root, '.env');
if (existsSync(envPath)) {
  // Parse dotenv with Node; never execute .env as shell code.
  const { parseEnv } = await import('node:util');
  Object.assign(env, parseEnv(readFileSync(envPath, 'utf8')), process.env);
} else if (local && !env.MYSQL_PASSWORD) {
  console.error('Configure MySQL credentials or run ./scripts/init-env.sh and docker compose up -d --wait mysql.');
  process.exit(1);
}
// Vite proxy and local ports are deliberately fixed for the first two weeks.
env.SERVER_PORT = '8080';
env.SERVER_ADDRESS = '127.0.0.1';
env.SPRING_PROFILES_ACTIVE = local ? 'local' : 'demo';
for (const port of [8080, 5173]) {
  await new Promise((accept, reject) => {
    const server = createServer();
    server.once('error', reject);
    server.listen(port, '127.0.0.1', () => server.close(accept));
  }).catch(() => {
    console.error(`Port ${port} is occupied; stop its owner or use separate startup commands.`);
    process.exit(1);
  });
}
const java = env.JAVA_HOME ? resolve(env.JAVA_HOME, 'bin/java') : 'java';
const frontEnv = { ...process.env };
// The browser process needs neither storage credentials nor the API's bearer tokens.
for (const key of Object.keys(frontEnv)) {
  if (/^(APP_BOOTSTRAP_|MYSQL_|MINIO_)/.test(key)) delete frontEnv[key];
}
const children = [
  spawn(java, ['-jar', 'backend/target/group-photo-consensus.jar'], { cwd: root, env, stdio: 'inherit' }),
  spawn(process.execPath, ['node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', '5173', '--strictPort'], {
    cwd: resolve(root, 'frontend'), env: frontEnv, stdio: 'inherit',
  }),
];
const closed = children.map((child) => new Promise((done) => child.once('close', done)));
let stopping = false;
function stop(code) {
  if (stopping) return;
  stopping = true;
  for (const child of children) if (child.pid && child.exitCode === null) child.kill('SIGTERM');
  const timer = setTimeout(() => {
    for (const child of children) if (child.pid && child.exitCode === null) child.kill('SIGKILL');
  }, 5000);
  Promise.all(closed).then(() => { clearTimeout(timer); process.exit(code); });
}
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => stop(0));
for (const child of children) {
  child.on('error', (error) => { console.error(error.message); stop(1); });
  child.on('exit', (code) => { if (!stopping) stop(code ?? 1); });
}
console.log(`Frontend http://127.0.0.1:5173 · Backend http://127.0.0.1:8080 · ${local ? 'MySQL local' : 'H2 demo (in-memory)'}`);
