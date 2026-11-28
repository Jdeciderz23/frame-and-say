import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';

const [statePath, ...extra] = process.argv.slice(2);
assert.ok(statePath && !extra.length,
  'Usage: node --env-file=.env scripts/seed-dark-photo-viewer.mjs <new-state-file>');
assert.ok(!existsSync(statePath), 'Use a state path that does not exist yet');

const origin = `http://127.0.0.1:${process.env.FRONTEND_PORT || '5173'}`;
const organizer = process.env.APP_BOOTSTRAP_ORGANIZER_TOKEN;
const participant = process.env.APP_BOOTSTRAP_PARTICIPANT_TOKEN;
assert.ok(organizer && participant && organizer !== participant, 'Two distinct demo tokens are required');

async function request(path, { method = 'GET', token = organizer, body, expected = 200 } = {}) {
  const headers = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body && !(body instanceof FormData)) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(body);
  }
  const response = await fetch(origin + path, { method, headers, body, signal: AbortSignal.timeout(15000) });
  assert.equal(response.status, expected, `${method} ${path}`);
  return response;
}

async function upload(path, file, baseVersionId) {
  const body = new FormData();
  const bytes = readFileSync(new URL(`./fixtures/dark-photo-viewer/${file}`, import.meta.url));
  body.set('file', new Blob([bytes], { type: 'image/png' }), file);
  if (baseVersionId) body.set('baseVersionId', baseVersionId);
  return (await request(path, { method: 'POST', body, expected: 201 })).json();
}

function resource(photos, photo) {
  return `${photos}/${photo.photoId}`;
}

async function confirmSubject(photos, photo) {
  const path = `${resource(photos, photo)}/versions/${photo.photoVersionId}/subjects`;
  const result = await (await request(path, {
    method: 'PUT', body: { subjectRevision: photo.subjectRevision, userIds: ['participant'] },
  })).json();
  const roster = await (await request(path)).json();
  assert.deepEqual(roster.userIds, ['participant']);
  assert.equal(roster.subjectRevision, result.subjectRevision);
  return result.subjectRevision;
}

const suffix = randomUUID().slice(0, 8);
const event = await (await request('/api/events', {
  method: 'POST', body: { name: `深色观片 74 · ${suffix}` }, expected: 201,
})).json();
const root = `/api/events/${event.id}`;
const photos = root + '/photos';
const original = await upload(photos, 'group-v1.png');
await request(original.thumbnailUrl, { token: null, expected: 401 });
await request(original.thumbnailUrl, { token: participant, expected: 403 });
await request(root + '/members', {
  method: 'POST', body: { userId: 'participant' }, expected: 204,
});

const group = resource(photos, original);
const originalRevision = await confirmSubject(photos, original);
await request(group + '/preferences/me', {
  method: 'PUT', token: participant, expected: 204, body: { photoVersionId: original.photoVersionId, value: -1 },
});
await request(group + '/consents/me', {
  method: 'PUT', token: participant, expected: 204,
  body: { photoVersionId: original.photoVersionId, subjectRevision: originalRevision, purpose: 'INTERNAL_SHARE', decision: 'DENY' },
});
await request(group + '/consents/me', {
  method: 'PUT', token: participant, expected: 204,
  body: { photoVersionId: original.photoVersionId, subjectRevision: originalRevision, purpose: 'PUBLIC_POST', decision: 'ALLOW' },
});

const { photo: edited, versionNumber } = await upload(group + '/versions', 'group-v2.png', original.photoVersionId);
assert.equal(versionNumber, 2);
assert.equal(edited.photoId, original.photoId);
assert.notEqual(edited.photoVersionId, original.photoVersionId);
assert.equal(edited.rosterConfirmed, false);
const editedRevision = await confirmSubject(photos, edited);
const second = await upload(photos, 'landscape.png');
const secondRevision = await confirmSubject(photos, second);

const album = await (await request(photos, { token: participant })).json();
assert.deepEqual(album.map(photo => photo.photoVersionId).sort(), [edited.photoVersionId, second.photoVersionId].sort());

for (const photo of [edited, second]) {
  const path = resource(photos, photo);
  const query = `photoVersionId=${photo.photoVersionId}`;
  const preference = await (await request(`${path}/preferences/me?${query}`, { token: participant })).json();
  assert.equal(preference.value, null);
  for (const purpose of ['INTERNAL_SHARE', 'PUBLIC_POST']) {
    const consent = await (await request(`${path}/consents/me?${query}&purpose=${purpose}`, { token: participant })).json();
    assert.equal(consent.decision, 'PENDING');
    assert.equal(consent.canDecide, true);
  }
}

const nonMemberEvent = await (await request('/api/events', {
  method: 'POST', body: { name: `非成员对照 74 · ${suffix}` }, expected: 201,
})).json();
const hidden = await upload(`/api/events/${nonMemberEvent.id}/photos`, 'landscape.png');
await request(hidden.thumbnailUrl, { token: participant, expected: 403 });

writeFileSync(statePath, JSON.stringify({
  eventId: event.id,
  eventName: event.name,
  oldVersion: { ...original, subjectRevision: originalRevision, rating: -1, internal: 'DENY', public: 'ALLOW' },
  currentVersion: { ...edited, subjectRevision: editedRevision, rating: null, internal: 'PENDING', public: 'PENDING' },
  otherPhoto: { ...second, subjectRevision: secondRevision, rating: null, internal: 'PENDING', public: 'PENDING' },
  nonMemberEventId: nonMemberEvent.id,
  nonMemberThumbnail: hidden.thumbnailUrl,
}, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
console.log(`Created ${event.name}. Member and non-member access baseline verified. State: ${statePath}`);
