import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';

const [phase, statePath] = process.argv.slice(2);
assert.ok(['create', 'verify'].includes(phase) && statePath,
  'Usage: node --env-file=.env scripts/smoke-photo-deletion.mjs <create|verify> <temporary-state-file>');
const proxy = `http://127.0.0.1:${process.env.FRONTEND_PORT || '5173'}`;
const organizer = process.env.APP_BOOTSTRAP_ORGANIZER_TOKEN;
const participant = process.env.APP_BOOTSTRAP_PARTICIPANT_TOKEN;
assert.ok(organizer && participant && organizer !== participant, 'Two distinct local demo tokens are required');

async function request(path, { method = 'GET', token = organizer, body, expected = 200 } = {}) {
  const headers = { Authorization: `Bearer ${token}` };
  if (body && !(body instanceof FormData)) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(body);
  }
  const response = await fetch(proxy + path, { method, headers, body, signal: AbortSignal.timeout(15000) });
  assert.equal(response.status, expected, `${method} ${path}`);
  return response;
}

async function verify({ photos, removed, kept, deletionRequest, approval, exportPath }) {
  for (const thumbnail of removed) {
    const missing = await request(thumbnail, { token: participant, expected: 404 });
    assert.match(missing.headers.get('content-type') || '', /application\/problem\+json/);
  }
  const currentAlbum = await (await request(photos, { token: participant })).json();
  assert.deepEqual(currentAlbum.map(item => item.photoId), [kept]);
  const revoked = await request(exportPath, { expected: 409 });
  assert.equal(revoked.headers.get('content-disposition'), null);
  assert.match(revoked.headers.get('content-type') || '', /application\/problem\+json/);
  const record = await (await request(deletionRequest, { token: participant })).json();
  assert.deepEqual(record, approval);
}

if (phase === 'verify') {
  await verify(JSON.parse(readFileSync(statePath, 'utf8')));
  console.log('After restart, both old links and the old export still fail; the approval record is unchanged.');
} else {
  const event = await (await request('/api/events', { method: 'POST', body: { name: 'Photo deletion smoke' }, expected: 201 })).json();
  const root = '/api/events/' + event.id;
  const photos = root + '/photos';
  await request(root + '/members', { method: 'POST', body: { userId: 'participant' }, expected: 204 });
  const png = [
    Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAEklEQVR4nGP4z8DAAMIM/4EAAB/uBfsL2WiLAAAAAElFTkSuQmCC', 'base64'),
    Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAMAAAACCAIAAAASFvFNAAAAEElEQVR4XmNgQAYH642AJAAH3QFzrQxB5wAAAABJRU5ErkJggg==', 'base64'),
  ];
  const upload = async (path, token, image, base) => {
    const form = new FormData();
    form.set('file', new Blob([image], { type: 'image/png' }), 'synthetic.png');
    if (base) form.set('baseVersionId', base);
    return (await request(path, { method: 'POST', token, body: form, expected: 201 })).json();
  };
  const original = await upload(photos, participant, png[0]);
  const resource = `${photos}/${original.photoId}`;
  const { photo: edited } = await upload(resource + '/versions', participant, png[1], original.photoVersionId);
  const other = await upload(photos, organizer, png[0]);
  for (const item of [edited, other]) {
    await request(`${photos}/${item.photoId}/versions/${item.photoVersionId}/subjects`, {
      method: 'PUT', body: { subjectRevision: 0, userIds: [] },
    });
  }
  const selection = await (await request(root + '/confirmed-selections', {
    method: 'POST', expected: 201,
    body: { purpose: 'INTERNAL_SHARE', items: [edited, other].map(item => ({
      photoId: item.photoId, photoVersionId: item.photoVersionId, subjectRevision: 1,
    })) },
  })).json();
  const exportPath = `${root}/confirmed-selections/${selection.selectionId}/export`;
  const deletionRequest = resource + '/deletion-request';
  const pending = await (await request(deletionRequest, { method: 'POST', token: participant })).json();
  assert.equal(pending.status, 'PENDING');
  assert.equal(pending.requestedBy, 'participant');
  const before = await request(exportPath);
  assert.equal(before.headers.get('content-type'), 'application/zip');
  await before.arrayBuffer();
  await request(original.thumbnailUrl, { token: participant });
  await request(deletionRequest + '/approval', { method: 'PUT', token: participant, expected: 403 });
  const approval = await (await request(deletionRequest + '/approval', { method: 'PUT' })).json();
  assert.equal(approval.status, 'APPROVED');
  assert.equal(approval.processedBy, 'organizer');
  assert.ok(Date.parse(approval.processedAt) >= Date.parse(approval.requestedAt));
  const state = { photos, removed: [original.thumbnailUrl, edited.thumbnailUrl], kept: other.photoId, deletionRequest, approval, exportPath };
  await verify(state);
  writeFileSync(statePath, JSON.stringify(state), { mode: 0o600 });
  console.log('Uploader requested deletion. Organizer approved; no old image or ZIP served and approval is auditable.');
}
