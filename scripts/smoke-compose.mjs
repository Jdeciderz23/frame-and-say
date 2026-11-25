import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';

const [phase, statePath] = process.argv.slice(2);
if (!['create', 'verify'].includes(phase) || !statePath) {
  throw new Error('Usage: node --env-file=.env scripts/smoke-compose.mjs <create|verify> <temporary-state-file>');
}

const proxy = `http://127.0.0.1:${process.env.FRONTEND_PORT || '5173'}`;
const backend = `http://127.0.0.1:${process.env.BACKEND_PORT || '8080'}`;
const organizer = process.env.APP_BOOTSTRAP_ORGANIZER_TOKEN;
const participant = process.env.APP_BOOTSTRAP_PARTICIPANT_TOKEN;
assert.ok(organizer && participant && organizer !== participant, 'Two distinct local demo tokens are required');

async function request(path, { method = 'GET', token, body, status = 200, base = proxy, requestKey } = {}) {
  const headers = {};
  if (requestKey) headers['Idempotency-Key'] = requestKey;
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body && !(body instanceof FormData)) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(body);
  }
  const response = await fetch(`${base}${path}`, { method, headers, body, signal: AbortSignal.timeout(15000) });
  assert.equal(response.status, status, `${method} ${path}`);
  return response;
}

await request('/');
const ready = await request('/actuator/health/readiness', { base: backend });
assert.equal((await ready.json()).status, 'UP');

if (phase === 'create') {
  await request('/api/events', { status: 401 });
  const creation = {
    method: 'POST', token: organizer, requestKey: crypto.randomUUID(), body: { name: 'Compose smoke test', desiredPhotoCount: 1 }, status: 201,
  };
  const { id: eventId } = await (await request('/api/events', creation)).json();
  assert.equal((await (await request('/api/events', creation)).json()).id, eventId);
  assert.ok(eventId);
  const root = `/api/events/${eventId}`;
  await request(`${root}/members`, { token: participant, status: 403 });
  await request(`${root}/members`, {
    method: 'POST', token: organizer, body: { userId: 'participant' }, status: 204,
  });
  const members = await (await request(`${root}/members`, { token: participant })).json();
  assert.equal(members.find((member) => member.userId === 'organizer')?.role, 'ORGANIZER');
  assert.equal(members.find((member) => member.userId === 'participant')?.role, 'PARTICIPANT');
  const otherEvent = await (await request('/api/events', {
    method: 'POST', token: participant, body: { name: 'Separate authorization activity' }, status: 201,
  })).json();
  await request(`/api/events/${otherEvent.id}/members`, { token: organizer, status: 403 });
  // Synthetic 2x2 RGB PNG with no photo or metadata.
  const pixel = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAEklEQVR4nGP4z8DAAMIM/4EAAB/uBfsL2WiLAAAAAElFTkSuQmCC', 'base64');
  const upload = new FormData();
  upload.set('file', new Blob([pixel], { type: 'image/png' }), 'synthetic.png');
  const created = await request(`${root}/photos`, { method: 'POST', token: organizer, body: upload, status: 201 });
  const photo = await created.json();
  assert.ok(photo.photoId && photo.photoVersionId && photo.thumbnailUrl);
  const thumbnail = await request(photo.thumbnailUrl, { token: participant });
  assert.match(thumbnail.headers.get('content-type') ?? '', /^image\//);
  const resource = `${root}/photos/${photo.photoId}`;
  const subjects = `${resource}/versions/${photo.photoVersionId}/subjects`;
  const consents = `${resource}/consents/me`;
  await request(`/api/events/${otherEvent.id}/photos/${photo.photoId}/consents/me?photoVersionId=${photo.photoVersionId}&purpose=PUBLIC_POST`, {
    token: participant, status: 404,
  });
  const roster = await (await request(subjects, {
    method: 'PUT', token: organizer, body: { subjectRevision: photo.subjectRevision, userIds: ['participant'] },
  })).json();
  const consent = { photoVersionId: photo.photoVersionId, purpose: 'PUBLIC_POST', decision: 'ALLOW' };
  const readConsent = async (purpose, token = participant) =>
    (await request(`${consents}?photoVersionId=${photo.photoVersionId}&purpose=${purpose}`, { token })).json();
  assert.equal((await readConsent('PUBLIC_POST')).decision, 'PENDING');
  await request(consents, { method: 'PUT', token: organizer, body: { ...consent, subjectRevision: roster.subjectRevision }, status: 403 });
  await request(consents, {
    method: 'PUT', token: organizer,
    body: { ...consent, subjectRevision: roster.subjectRevision, userId: 'participant' }, status: 400,
  });
  await request(consents, { method: 'PUT', token: participant, body: { ...consent, subjectRevision: roster.subjectRevision }, status: 204 });
  assert.equal((await readConsent('PUBLIC_POST')).decision, 'ALLOW');
  assert.equal((await readConsent('PUBLIC_POST', organizer)).canDecide, false);
  assert.equal((await readConsent('INTERNAL_SHARE')).decision, 'PENDING');
  await request(consents, {
    method: 'PUT', token: participant,
    body: { ...consent, purpose: 'INTERNAL_SHARE', decision: 'DENY', subjectRevision: roster.subjectRevision }, status: 204,
  });
  assert.equal((await readConsent('INTERNAL_SHARE')).decision, 'DENY');
  assert.equal((await readConsent('PUBLIC_POST')).decision, 'ALLOW');
  await request(subjects, {
    method: 'PUT', token: organizer, body: { subjectRevision: roster.subjectRevision, userIds: ['organizer', 'participant'] },
  });
  await request(consents, { method: 'PUT', token: participant, body: { ...consent, subjectRevision: roster.subjectRevision }, status: 409 });
  const current = await readConsent('PUBLIC_POST');
  assert.equal(current.decision, 'PENDING');
  assert.equal(current.canDecide, true);
  assert.equal(current.subjectRevision, roster.subjectRevision + 1);
  assert.equal((await readConsent('INTERNAL_SHARE')).decision, 'PENDING');
  // A new explicit confirmation uses freshly read context; the old decision is never replayed automatically.
  await request(consents, {
    method: 'PUT', token: participant,
    body: { ...consent, subjectRevision: current.subjectRevision }, status: 204,
  });
  await request(consents, {
    method: 'PUT', token: organizer,
    body: { ...consent, subjectRevision: current.subjectRevision }, status: 204,
  });
  assert.equal((await readConsent('PUBLIC_POST')).decision, 'ALLOW');
  assert.equal((await readConsent('PUBLIC_POST', organizer)).decision, 'ALLOW');
  writeFileSync(statePath, JSON.stringify({ eventId, photoId: photo.photoId, thumbnailUrl: photo.thumbnailUrl }), { mode: 0o600 });
  console.log('Two identities, activity isolation, personal consent, independent purposes and stale-revision refresh verified through the proxy');
} else {
  const { eventId, photoId, thumbnailUrl } = JSON.parse(readFileSync(statePath, 'utf8'));
  const events = await (await request('/api/events', { token: participant })).json();
  assert.ok(events.some((event) => event.id === eventId), 'Event membership must persist across restart');
  const photos = await (await request(`/api/events/${eventId}/photos`, { token: participant })).json();
  assert.ok(photos.some((photo) => photo.photoId === photoId), 'Photo metadata must persist across restart');
  const thumbnail = await request(thumbnailUrl, { token: participant });
  assert.ok((await thumbnail.arrayBuffer()).byteLength > 0, 'Thumbnail bytes must persist across restart');
  console.log('MySQL metadata and private file volume still serve the event and thumbnail after restart');
}
