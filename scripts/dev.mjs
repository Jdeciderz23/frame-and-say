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
if (local) {
  if (!existsSync(resolve(root, '.env'))) {
    console.error('Run ./scripts/init-env.sh and docker compose up -d mysql first.');
    process.exit(1);
  }
  // Parse dotenv with Node; never execute .env as shell code.
  const { parseEnv } = await import('node:util');
  Object.assign(env, parseEnv(readFileSync(resolve(root, '.env'), 'utf8')), process.env);
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
const children = [
  spawn('java', ['-jar', 'backend/target/group-photo-consensus.jar'], { cwd: root, env, stdio: 'inherit' }),
  spawn(process.execPath, ['node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', '5173', '--strictPort'], {
    cwd: resolve(root, 'frontend'), env, stdio: 'inherit',
  }),
];
let stopping = false;
function stop(code) {
  if (stopping) return;
  stopping = true;
  for (const child of children) if (child.exitCode === null) child.kill('SIGTERM');
  const timer = setTimeout(() => {
    for (const child of children) if (child.exitCode === null) child.kill('SIGKILL');
  }, 5000);
  Promise.all(children.map((child) => new Promise((done) => {
    if (child.exitCode !== null || child.signalCode !== null) done();
    else child.once('exit', done);
  }))).then(() => { clearTimeout(timer); process.exit(code); });
}
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => stop(0));
for (const child of children) {
  child.on('error', (error) => { console.error(error.message); stop(1); });
  child.on('exit', (code) => { if (!stopping) stop(code ?? 1); });
}
console.log(`Frontend http://127.0.0.1:5173 · Backend http://127.0.0.1:8080 · ${local ? 'MySQL local' : 'H2 demo (no business data)'}`);
