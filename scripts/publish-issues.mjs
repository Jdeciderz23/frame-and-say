import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';

const args = process.argv.slice(2);
const repoIndex = args.indexOf('--repo');
const repository = repoIndex >= 0 ? args[repoIndex + 1] : undefined;
const apply = args.includes('--apply');
const issues = JSON.parse(readFileSync(resolve(import.meta.dirname, '../docs/issues.json'), 'utf8'));
if (!Array.isArray(issues) || issues.some((issue) => !issue.id || !issue.title || !issue.owner || !issue.body || !Array.isArray(issue.depends_on))) {
  throw new Error('docs/issues.json must be an array with id/title/owner/body/depends_on.');
}
const titleFor = (issue) => issue.title.startsWith(`[${issue.id}] `) ? issue.title : `[${issue.id}] ${issue.title}`;
const known = new Set();
for (const issue of issues) {
  if (known.has(issue.id) || issue.depends_on.some((id) => !known.has(id))) {
    throw new Error(`Duplicate issue ID or dependency listed after ${issue.id}.`);
  }
  known.add(issue.id);
}
if (!apply) {
  for (const issue of issues) console.log(`${titleFor(issue)} | ${issue.owner}`);
  console.log('Preview only. To create: node scripts/publish-issues.mjs --repo Jdeciderz23/frame-and-say --apply');
  process.exit(0);
}
if (!repository || !/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repository)) {
  throw new Error('Use --repo OWNER/REPOSITORY --apply.');
}
function gh(args, input) {
  const result = spawnSync('gh', args, { encoding: 'utf8', input });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(result.stderr || 'GitHub CLI command failed.');
  return result.stdout.trim();
}
gh(['auth', 'status']);
const existing = JSON.parse(gh(['issue', 'list', '--repo', repository, '--state', 'all', '--limit', '1000', '--json', 'title,number']));
const numbers = new Map();
for (const issue of issues) {
  const prefix = `[${issue.id}]`;
  const found = existing.find((item) => item.title.startsWith(`${prefix} `));
  if (found) {
    numbers.set(issue.id, found.number);
    console.log(`Skip ${prefix}: #${found.number} already exists.`);
    continue;
  }
  const dependencies = issue.depends_on.map((id) => `#${numbers.get(id)} (${id})`);
  const body = `负责人：${issue.owner}\n项目相对周：${issue.week}\n依赖链接：${dependencies.join(', ') || '无'}\n\n${issue.body}\n\n> 成员编号待映射真实 GitHub 账号，创建后由仓库管理员设置 Assignees。`;
  const url = gh(['issue', 'create', '--repo', repository, '--title', titleFor(issue), '--body-file', '-'], body);
  const number = url.match(/\/issues\/(\d+)$/)?.[1];
  if (!number) throw new Error(`Created ${prefix}, but couldn't read its number from: ${url}`);
  numbers.set(issue.id, Number(number));
  console.log(`${prefix} ${url}`);
}
