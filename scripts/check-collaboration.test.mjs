import assert from 'node:assert/strict';
import test from 'node:test';
import { checkEvent } from './check-collaboration.mjs';

const repository = { full_name: 'Jdeciderz23/frame-and-say' };
const research = { ref: 'feature/identity-authorization', repo: repository };
const pr = {
  number: 30, state: 'open', base: { ref: 'main' }, head: { ref: 'chore/8-team-workflow', repo: repository },
  title: 'chore(ci): 统一协作要求', body: 'Refs #8',
};
const push = { ref: 'refs/heads/feature/4-recommendation-dto', created: true };
const checkPR = (changes = {}, read = async () => ({ number: 8 })) =>
  checkEvent('pull_request', { repository, pull_request: { ...pr, ...changes } }, read);
const checkPush = (changes = {}, read = assert.fail) => checkEvent('push', { repository, ...push, ...changes }, read);

test('阶段交付和完成任务都可以关联真实 Issue', async () => {
  for (const body of ['Refs #8\r\nRefs #4', 'Closes #8']) {
    await checkPR({ body }, async path => {
      assert.equal(path, '/issues/8');
      return { number: 8 };
    });
  }
});

test('拒绝错误目标、名称、标题、任务号和伪装成任务的 PR', async () => {
  await assert.rejects(checkPR({ base: { ref: 'dev' } }), /main/);
  await assert.rejects(checkPR({ head: { ref: 'feature/w1-04-dto' } }), /命名分支/);
  await assert.rejects(checkPR({ title: 'fix: 统一协作要求' }), /标题/);
  for (const body of ['Refs #80', '<!--\nRefs #8\n-->', '```md\nRefs #8\n```', '> Refs #8']) {
    await assert.rejects(checkPR({ body }), /正文/);
  }
  await assert.rejects(checkPR({}, async () => ({ pull_request: {} })), /实际的 Issue/);
});

test('普通分支推送检查命名和强推', async () => {
  await checkPush();
  await assert.rejects(checkPush({ ref: 'refs/heads/toepyue' }), /命名分支/);
  await assert.rejects(checkPush({ forced: true, created: false }), /强制推送/);
});

test('旧分支名仅限本仓库已打开的 #16', async () => {
  const old = { ...pr, number: 16, head: research, title: 'docs: 整理调研报告', body: 'Refs #6' };
  await checkPR(old);
  await assert.rejects(checkPR({ ...old, number: 18 }), /命名分支/);
  await assert.rejects(checkPR({ ...old, head: { ...research, repo: { full_name: 'other/repo' } } }), /命名分支/);
  const update = { ref: 'refs/heads/' + research.ref, created: false };
  await checkPush(update, async () => old);
  await assert.rejects(checkPush({ ...update, created: true }), /命名分支/);
  await assert.rejects(checkPush(update, async () => ({ ...old, state: 'closed' })), /命名分支/);
});

test('main 更新必须对应以该提交合入本仓库主线的 PR', async () => {
  const update = { ref: 'refs/heads/main', created: false, after: 'merged-sha' };
  const merged = { base: { ref: 'main', repo: repository }, merged_at: '2026-11-19', merge_commit_sha: 'merged-sha' };
  await checkPush(update, async path => {
    assert.equal(path, '/commits/merged-sha/pulls');
    return [merged];
  });
  for (const pulls of [[], [{ ...merged, merged_at: null }], [{ ...merged, merge_commit_sha: 'different' }]]) {
    await assert.rejects(checkPush(update, async () => pulls), /已合并 PR/);
  }
});
