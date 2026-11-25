import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import { inflateRawSync } from 'node:zlib';

const [phase, statePath] = process.argv.slice(2);
assert.ok(['create', 'verify'].includes(phase) && statePath,
  'Usage: node --env-file=.env scripts/smoke-reviewed-export.mjs <create|verify> <temporary-state-file>');
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

async function assertInvalidated(path) {
  // A different purpose in the URL cannot override the server's confirmed purpose.
  const response = await request(path + '?purpose=PUBLIC_POST', { expected: 409 });
  assert.equal(response.headers.get('content-disposition'), null);
  assert.match(response.headers.get('content-type') || '', /application\/problem\+json/);
  assert.match((await response.json()).detail, /no longer valid/);
}

function readSmallTestZip(bytes) {
  // The server creates a ZIP with no trailing comment; use the central directory for entry offsets.
  const end = bytes.length - 22;
  assert.equal(bytes.readUInt32LE(end), 0x06054b50);
  const count = bytes.readUInt16LE(end + 10);
  let cursor = bytes.readUInt32LE(end + 16);
  const entries = [];
  for (let i = 0; i < count; i++) {
    assert.equal(bytes.readUInt32LE(cursor), 0x02014b50);
    const size = bytes.readUInt32LE(cursor + 20);
    const nameLength = bytes.readUInt16LE(cursor + 28);
    const local = bytes.readUInt32LE(cursor + 42);
    const name = bytes.toString('utf8', cursor + 46, cursor + 46 + nameLength);
    assert.equal(bytes.readUInt32LE(local), 0x04034b50);
    const data = local + 30 + bytes.readUInt16LE(local + 26) + bytes.readUInt16LE(local + 28);
    entries.push([name, inflateRawSync(bytes.subarray(data, data + size))]);
    cursor += 46 + nameLength + bytes.readUInt16LE(cursor + 30) + bytes.readUInt16LE(cursor + 32);
  }
  return entries;
}

if (phase === 'verify') {
  const { download } = JSON.parse(readFileSync(statePath, 'utf8'));
  await assertInvalidated(download);
  console.log('The persisted confirmed list still refuses delivery after the restart');
} else {
  const event = await (await request('/api/events', {
    method: 'POST', body: { name: 'Reviewed export smoke' }, expected: 201,
  })).json();
  const root = '/api/events/' + event.id;
  await request(root + '/members', { method: 'POST', body: { userId: 'participant' }, expected: 204 });
  const synthetic = [
    Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAEklEQVR4nGP4z8DAAMIM/4EAAB/uBfsL2WiLAAAAAElFTkSuQmCC', 'base64'),
    Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAMAAAACCAIAAAASFvFNAAAAEElEQVR4XmNgQAYH642AJAAH3QFzrQxB5wAAAABJRU5ErkJggg==', 'base64'),
  ];
  async function upload(bytes) {
    const form = new FormData();
    form.set('file', new Blob([bytes], { type: 'image/png' }), 'synthetic.png');
    return (await request(root + '/photos', { method: 'POST', body: form, expected: 201 })).json();
  }
  const photos = [];
  const decisionPath = (photo) => `${root}/photos/${photo.photoId}/consents/me`;
  async function decide(photo, purpose, decision) {
    await request(decisionPath(photo), {
      method: 'PUT', token: participant, expected: 204,
      body: { photoVersionId: photo.photoVersionId, subjectRevision: 1, purpose, decision },
    });
  }
  for (const bytes of synthetic) {
    const photo = await upload(bytes);
    await request(`${root}/photos/${photo.photoId}/versions/${photo.photoVersionId}/subjects`, {
      method: 'PUT', body: { subjectRevision: 0, userIds: ['participant'] },
    });
    await decide(photo, 'INTERNAL_SHARE', 'ALLOW');
    photos.push(photo);
  }
  await upload(synthetic[0]); // The downloaded ZIP must contain only the two reviewed originals.
  await decide(photos[1], 'PUBLIC_POST', 'ALLOW');
  const selection = await (await request(root + '/confirmed-selections', {
    method: 'POST', expected: 201,
    body: { purpose: 'INTERNAL_SHARE', items: photos.map(({ photoId, photoVersionId }) => ({
      photoId, photoVersionId, subjectRevision: 1,
    })) },
  })).json();
  const download = `${root}/confirmed-selections/${selection.selectionId}/export`;
  const zip = await request(download);
  assert.equal(zip.headers.get('content-type'), 'application/zip');
  assert.equal(zip.headers.get('cache-control'), 'no-store');
  assert.match(zip.headers.get('content-disposition') || '', /^attachment;/);
  const bytes = Buffer.from(await zip.arrayBuffer());
  assert.deepEqual(readSmallTestZip(bytes), photos.map((photo, index) => [photo.photoVersionId + '.png', synthetic[index]]));
  await decide(photos[1], 'INTERNAL_SHARE', 'DENY');
  await assertInvalidated(download);
  writeFileSync(statePath, JSON.stringify({ download }), { mode: 0o600 });
  writeFileSync(statePath + '.zip', bytes, { mode: 0o600 });
  console.log('Export contained exactly the reviewed originals. Revoking internal permission blocked the entire ZIP.');
}
