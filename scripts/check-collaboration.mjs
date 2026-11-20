import { readFileSync } from 'node:fs';
import { setTimeout } from 'node:timers/promises';

const branchPattern = /^(feature|fix|docs|test|refactor|chore)\/([1-9]\d*)-[a-z][a-z0-9]*(?:-[a-z0-9]+)*$/;
const legacyBranch = 'feature/identity-authorization';

function isExistingResearchPR(pr, repository) {
  return pr.number === 16 && pr.state === 'open' && pr.head.ref === legacyBranch
    && pr.head.repo?.full_name === repository;
}

function parseBranch(branch) {
  const match = branch.match(branchPattern);
  if (!match) throw new Error('请使用「类型/Issue号-英文短描述」命名分支，例如 feature/4-recommendation-dto。');
  return { type: match[1] === 'feature' ? 'feat' : match[1], issue: match[2] };
}

function hasIssueLine(body, number) {
  const lines = (body ?? '').replace(/<!--[\s\S]*?(?:-->|$)/g, '').split(/\r?\n/);
  let fence = '';
  for (const line of lines) {
    const marker = /^ {0,3}(`{3,}|~{3,})/.exec(line)?.[1];
    if (fence) {
      if (marker?.startsWith(fence) && line.trim() === marker) fence = '';
    } else if (marker) {
      fence = marker;
    } else if (line.trimEnd() === `Refs #${number}` || line.trimEnd() === `Closes #${number}`) {
      return true;
    }
  }
  return false;
}

export async function checkEvent(name, event, read) {
  const repository = event.repository.full_name;
  if (name === 'pull_request') {
    const pr = event.pull_request;
    if (pr.base.ref !== 'main') throw new Error('请把 PR 的目标分支设为 main。');
    const { type, issue } = isExistingResearchPR(pr, repository)
      ? { type: 'docs', issue: '6' } : parseBranch(pr.head.ref);
    const title = /^([a-z]+)(?:\([a-z][a-z0-9-]*\))?:\s+(\S.*)$/.exec(pr.title);
    if (title?.[1] !== type) throw new Error(`PR 标题应以 ${type}: 或 ${type}(模块): 开头，并说明改动。`);
    if (!hasIssueLine(pr.body, issue)) throw new Error(`请在 PR 正文独立写一行 Refs #${issue} 或 Closes #${issue}。`);
    const linked = await read(`/issues/${issue}`);
    if (linked.pull_request) throw new Error(`#${issue} 是 PR，请关联实际的 Issue。`);
    return;
  }

  if (name !== 'push') throw new Error(`未支持的事件 ${name}`);
  if (event.forced) throw new Error('检测到强制推送。请合并最新主线并正常推送。');
  if (event.ref === 'refs/heads/main') {
    const pulls = await read(`/commits/${event.after}/pulls`);
    const fromMerge = pulls.some(pr => pr.merged_at && pr.base.ref === 'main'
      && pr.base.repo.full_name === repository && pr.merge_commit_sha === event.after);
    if (!fromMerge) throw new Error('main 的新提交没有对应的已合并 PR。请通过 PR 的 Squash and merge 更新主线。');
    return;
  }

  const branch = event.ref.replace(/^refs\/heads\//, '');
  if (branch === legacyBranch && !event.created && isExistingResearchPR(await read('/pulls/16'), repository)) return;
  parseBranch(branch);
}

if (import.meta.main) {
  const event = JSON.parse(readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'));
  async function read(path) {
    const url = `${process.env.GITHUB_API_URL}/repos/${process.env.GITHUB_REPOSITORY}${path}`;
    const headers = { Authorization: `Bearer ${process.env.GITHUB_TOKEN}`, Accept: 'application/vnd.github+json' };
    const response = await fetch(url, { headers });
    if (!response.ok) throw new Error(`GitHub 读取 ${path} 失败（HTTP ${response.status}），请核对任务编号或检查权限。`);
    return response.json();
  }
  try {
    const name = process.env.GITHUB_EVENT_NAME;
    // 合并触发 push 后，GitHub 的关联 PR 列表可能稍晚可见。
    if (name === 'push' && event.ref === 'refs/heads/main') {
      await checkEvent(name, event, async path => {
        const pulls = await read(path);
        if (pulls.some(pr => pr.merge_commit_sha === event.after && pr.merged_at)) return pulls;
        await setTimeout(3000);
        return read(path);
      });
    } else {
      await checkEvent(name, event, read);
    }
    console.log('协作规则检查通过。');
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
