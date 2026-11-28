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
  const desiredPhotoCount = 4;
  await request(`${root}/selection-target`, {
    method: 'PUT', token: organizer, body: { desiredPhotoCount }, status: 204,
  });
  await request(`${root}/selection-target`, {
    method: 'PUT', token: participant, body: { desiredPhotoCount: 2 }, status: 403,
  });
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
  const originalThumbnail = Buffer.from(await thumbnail.arrayBuffer());
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
  const versions = `${resource}/versions`;
  const edit = new FormData();
  edit.set('baseVersionId', photo.photoVersionId);
  const changedPixels = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAMAAAACCAIAAAASFvFNAAAAEElEQVR4XmNgQAYH642AJAAH3QFzrQxB5wAAAABJRU5ErkJggg==', 'base64');
  edit.set('file', new Blob([changedPixels], { type: 'image/png' }), 'edited.png');
  await request(versions, { method: 'POST', token: participant, body: edit, status: 403 });
  const saved = await (await request(versions, { method: 'POST', token: organizer, body: edit, status: 201 })).json();
  assert.equal(saved.versionNumber, 2);
  assert.equal(saved.photo.photoId, photo.photoId);
  assert.notEqual(saved.photo.photoVersionId, photo.photoVersionId);
  assert.equal(saved.photo.rosterConfirmed, false);
  await request(versions, { method: 'POST', token: organizer, body: edit, status: 409 });
  const nextConsent = await (await request(`${consents}?photoVersionId=${saved.photo.photoVersionId}&purpose=PUBLIC_POST`, { token: participant })).json();
  assert.equal(nextConsent.canDecide, false);
  assert.equal(nextConsent.decision, 'PENDING');
  const history = await (await request(versions, { token: participant })).json();
  assert.equal(history.canCreateVersion, false);
  assert.deepEqual(history.versions.map(item => item.photo.photoVersionId), [saved.photo.photoVersionId, photo.photoVersionId]);
  const previous = await request(photo.thumbnailUrl, { token: participant });
  assert.deepEqual(Buffer.from(await previous.arrayBuffer()), originalThumbnail);
  const latest = await request(saved.photo.thumbnailUrl, { token: participant });
  const latestThumbnail = Buffer.from(await latest.arrayBuffer());
  assert.notDeepEqual(latestThumbnail, originalThumbnail);
  const latestVersionId = saved.photo.photoVersionId;
  const latestRoster = await (await request(`${versions}/${latestVersionId}/subjects`, {
    method: 'PUT', token: organizer, body: { subjectRevision: saved.photo.subjectRevision, userIds: ['participant'] },
  })).json();
  await request(consents, {
    method: 'PUT', token: participant, status: 204,
    body: { ...consent, photoVersionId: latestVersionId, subjectRevision: latestRoster.subjectRevision },
  });
  await request(`${resource}/preferences/me`, {
    method: 'PUT', token: participant, status: 204, body: { photoVersionId: latestVersionId, value: 0 },
  });
  writeFileSync(statePath, JSON.stringify({
    eventId, desiredPhotoCount, photoId: photo.photoId,
    thumbnails: [
      { url: saved.photo.thumbnailUrl, versionId: saved.photo.photoVersionId, bytes: latestThumbnail.toString('base64') },
      { url: photo.thumbnailUrl, versionId: photo.photoVersionId, bytes: originalThumbnail.toString('base64') },
    ],
  }), { mode: 0o600 });
  console.log('Membership, personal consent and separate photo versions verified through the proxy');
} else {
  const { eventId, desiredPhotoCount = 1, photoId, thumbnails } = JSON.parse(readFileSync(statePath, 'utf8'));
  const events = await (await request('/api/events', { token: participant })).json();
  const event = events.find((item) => item.id === eventId);
  assert.ok(event, 'Event membership must persist across restart');
  assert.equal(event.desiredPhotoCount, desiredPhotoCount, 'The saved event target must persist across restart');
  const root = `/api/events/${eventId}`;
  const photos = await (await request(`${root}/photos`, { token: participant })).json();
  assert.equal(photos.find(photo => photo.photoId === photoId)?.photoVersionId, thumbnails[0].versionId);
  const resource = `${root}/photos/${photoId}`;
  const history = await (await request(`${resource}/versions`, { token: participant })).json();
  assert.deepEqual(history.versions.map(item => item.photo.photoVersionId), thumbnails.map(item => item.versionId));
  for (const item of thumbnails) {
    const thumbnail = await request(item.url, { token: participant });
    assert.deepEqual(Buffer.from(await thumbnail.arrayBuffer()), Buffer.from(item.bytes, 'base64'));
  }
  const query = `photoVersionId=${thumbnails[0].versionId}`;
  const preference = await (await request(`${resource}/preferences/me?${query}`, { token: participant })).json();
  assert.equal(preference.value, 0);
  const consent = await (await request(`${resource}/consents/me?${query}&purpose=PUBLIC_POST`, { token: participant })).json();
  assert.equal(consent.decision, 'ALLOW');
  assert.equal(consent.canDecide, true);
  await request(thumbnails[0].url, { status: 401 });
  console.log('Activity, both photo versions, rating and consent retained across restart');
}
