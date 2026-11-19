import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';

const args = process.argv.slice(2);
const repoIndex = args.indexOf('--repo');
const repository = repoIndex >= 0 ? args[repoIndex + 1] : undefined;
const apply = args.includes('--apply');
const issues = JSON.parse(readFileSync(resolve(import.meta.dirname, '../docs/issues.json'), 'utf8'));
if (!Array.isArray(issues) || issues.some((issue) => !issue.id || !issue.title || !issue.owner || !issue.body)) {
  throw new Error('docs/issues.json must be an array with id/title/owner/body.');
}
const titleFor = (issue) => issue.title.startsWith(`[${issue.id}] `) ? issue.title : `[${issue.id}] ${issue.title}`;
if (!apply) {
  for (const issue of issues) console.log(`${titleFor(issue)} | ${issue.owner}`);
  console.log('Preview only. To create: node scripts/publish-issues.mjs --repo OWNER/group-photo-consensus --apply');
  process.exit(0);
}
if (!repository || !/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repository)) {
  throw new Error('Use --repo OWNER/group-photo-consensus --apply.');
}
function gh(args, input) {
  const result = spawnSync('gh', args, { encoding: 'utf8', input });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(result.stderr || 'GitHub CLI command failed.');
  return result.stdout.trim();
}
gh(['auth', 'status']);
const existing = JSON.parse(gh(['issue', 'list', '--repo', repository, '--state', 'all', '--limit', '1000', '--json', 'title,number']));
for (const issue of issues) {
  const prefix = `[${issue.id}]`;
  const found = existing.find((item) => item.title.startsWith(`${prefix} `));
  if (found) { console.log(`Skip ${prefix}: #${found.number} already exists.`); continue; }
  const body = `负责人：${issue.owner}\n评审人：${issue.reviewer}\n项目相对周：${issue.week}\n依赖：${issue.depends_on.join(', ') || '无'}\n\n${issue.body}\n\n> 成员编号待映射真实 GitHub 账号，创建后由仓库管理员设置 Assignees。`;
  const url = gh(['issue', 'create', '--repo', repository, '--title', titleFor(issue), '--body-file', '-'], body);
  console.log(`${prefix} ${url}`);
}
