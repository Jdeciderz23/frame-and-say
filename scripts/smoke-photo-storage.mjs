import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const [phase, statePath, mode, ...extra] = process.argv.slice(2);
assert.ok(['create', 'verify'].includes(phase) && statePath && [undefined, '--compose', '--nginx'].includes(mode) && !extra.length,
  'Usage: node --env-file=.env scripts/smoke-photo-storage.mjs <create|verify> <temporary-state-file> [--compose|--nginx]');
const compose = mode === '--compose';
const nginx = compose || mode === '--nginx';
const photoDir = process.env.APP_PHOTO_DIR;
assert.ok(compose || photoDir, 'Local mode requires APP_PHOTO_DIR to match the running backend');
const frontend = `http://127.0.0.1:${process.env.FRONTEND_PORT || '5173'}`;
const backend = `http://127.0.0.1:${process.env.BACKEND_PORT || '8080'}`;
const organizer = process.env.APP_BOOTSTRAP_ORGANIZER_TOKEN;
const participant = process.env.APP_BOOTSTRAP_PARTICIPANT_TOKEN;
assert.ok(organizer && participant && organizer !== participant, 'Two distinct local demo tokens are required');

const MiB = 1024 * 1024;
const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/;
const boundary = 'frame-and-say-storage-smoke';
const mediaFields = [
  'eventId', 'photoId', 'photoVersionId', 'rosterConfirmed', 'subjectRevision', 'thumbnailAvailable', 'thumbnailUrl',
];
const pngSignature = Buffer.from('89504e470d0a1a0a', 'hex');
// Synthetic pixels only. The 3×2 JPEG was generated with Java ImageIO.
const jpeg = Buffer.from('/9j/4AAQSkZJRgABAgAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAACAAMDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDw7WI0ivY1jRUU2tuxCjHJhQk/Ukk/jRRRSjshy3P/2Q==', 'base64');
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAEklEQVR4nGP4z8DAAMIM/4EAAB/uBfsL2WiLAAAAAElFTkSuQmCC', 'base64');
const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex');
const photosPath = (eventId) => `/api/events/${eventId}/photos`;

async function request(path, { method = 'GET', token, body, status = 200, base = frontend } = {}) {
  const headers = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (Buffer.isBuffer(body)) {
    headers['Content-Type'] = `multipart/form-data; boundary=${boundary}`;
    headers['Content-Length'] = body.length;
  } else if (body) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(body);
  }
  const response = await fetch(base + path, { method, headers, body, signal: AbortSignal.timeout(30_000) });
  assert.equal(response.status, status, `${method} ${path} at ${base}`);
  return response;
}

// A separate text field makes the whole request exact without increasing the file size.
function multipart(file, filename, type, requestSize) {
  const head = Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="${filename}"\r\nContent-Type: ${type}\r\n\r\n`);
  const tail = Buffer.from(`\r\n--${boundary}--\r\n`);
  if (requestSize === undefined) return Buffer.concat([head, file, tail]);
  const field = Buffer.from(`\r\n--${boundary}\r\nContent-Disposition: form-data; name="padding"\r\n\r\n`);
  return Buffer.concat([head, file, field, Buffer.alloc(requestSize - head.length - file.length - field.length - tail.length, 32), tail]);
}

async function createEvent(name) {
  const response = await request('/api/events', { method: 'POST', token: organizer, body: { name }, status: 201 });
  const { id } = await response.json();
  assert.match(id, uuid);
  return id;
}

function storedFile(version, filename) {
  assert.match(version, uuid);
  if (!compose) return readFileSync(join(photoDir, version, filename));
  return execFileSync('docker', ['compose', '--profile', 'app', 'exec', '-T', 'backend',
    'cat', `/app/data/photos/${version}/${filename}`], { maxBuffer: 10 * MiB, timeout: 30_000 });
}

async function checkThumbnail(record) {
  const { metadata, width, height, originalHash } = record;
  const { eventId, photoId, photoVersionId, thumbnailUrl } = metadata;
  assert.deepEqual(Object.keys(metadata).sort(), mediaFields);
  assert.match(photoId, uuid);
  assert.match(photoVersionId, uuid);
  assert.equal(thumbnailUrl, `${photosPath(eventId)}/${photoId}/versions/${photoVersionId}/thumbnail`);
  assert.equal(metadata.thumbnailAvailable, true);
  assert.equal(metadata.subjectRevision, 0);
  assert.equal(metadata.rosterConfirmed, false);
  await request(thumbnailUrl, { status: 401 });
  const response = await request(thumbnailUrl, { token: participant });
  assert.equal(response.headers.get('content-type'), 'image/png');
  assert.match(response.headers.get('cache-control') || '', /\bno-store\b/);
  const bytes = Buffer.from(await response.arrayBuffer());
  assert.deepEqual(bytes.subarray(0, 8), pngSignature);
  assert.equal(bytes.toString('ascii', 12, 16), 'IHDR');
  assert.equal(bytes.readUInt32BE(16), width);
  assert.equal(bytes.readUInt32BE(20), height);
  assert.deepEqual(bytes, storedFile(photoVersionId, 'thumbnail.png'), 'Served thumbnail must match the private file');
  assert.equal(sha256(storedFile(photoVersionId, 'original')), originalHash, 'Original bytes must match the upload');
  return sha256(bytes);
}

async function checkCatalog(eventId, records) {
  const photos = await (await request(photosPath(eventId), { token: participant })).json();
  assert.equal(photos.length, records.length, 'Only the two valid uploads should be listed');
  for (const { metadata } of records) {
    assert.deepEqual(photos.find(photo => photo.photoId === metadata.photoId), metadata);
  }
}

const health = await request('/actuator/health/readiness', { base: backend });
assert.equal((await health.json()).status, 'UP');

if (phase === 'create') {
  const eventId = await createEvent('Photo persistence smoke');
  const otherEventId = await createEvent('Separate photo activity');
  const path = photosPath(eventId);
  const upload = (body, status, base = frontend, token = organizer) =>
    request(path, { method: 'POST', token, body, status, base });
  const paddedJpeg = Buffer.concat([jpeg, Buffer.alloc(8 * MiB - jpeg.length)]);
  const exactRequest = multipart(paddedJpeg, 'synthetic.jpg', 'image/jpeg', 9 * MiB);
  const aboveRequest = multipart(paddedJpeg, 'synthetic.jpg', 'image/jpeg', 9 * MiB + 1);
  const aboveFile = multipart(Buffer.concat([paddedJpeg, Buffer.alloc(1)]), 'synthetic.jpg', 'image/jpeg');

  await upload(multipart(Buffer.from('synthetic text'), 'pretender.png', 'image/png'), 415);
  await upload(multipart(png.subarray(0, 33), 'truncated.png', 'image/png'), 415);
  await upload(multipart(Buffer.alloc(0), 'empty.png', 'image/png'), 400);
  await upload(aboveFile, 413); // Total request is below 9 MiB: the file is over 8 MiB.
  await upload(aboveRequest, 413, backend); // Direct: the servlet limits the total request.
  if (nginx) {
    // Without credentials the backend answers 401. A proxy 413 therefore identifies the Nginx limit.
    for (const [body, status] of [[exactRequest, 401], [aboveRequest, 413]]) {
      await request(path, { method: 'POST', body, status });
    }
    await request(path, { method: 'POST', body: aboveRequest, status: 401, base: backend });
  }
  const jpgMeta = await (await upload(exactRequest, 201)).json();
  await request(jpgMeta.thumbnailUrl, { token: participant, status: 403 });
  await upload(multipart(png, 'synthetic.png', 'image/png'), 403, frontend, participant);
  for (const id of [eventId, otherEventId]) {
    await request(`/api/events/${id}/members`, {
      method: 'POST', token: organizer, body: { userId: 'participant' }, status: 204,
    });
  }
  const pngMeta = await (await upload(multipart(png, 'synthetic.png', 'image/png'), 201, frontend, participant)).json();
  assert.notEqual(jpgMeta.photoVersionId, pngMeta.photoVersionId);
  const records = [
    { metadata: jpgMeta, width: 3, height: 2, originalHash: sha256(paddedJpeg) },
    { metadata: pngMeta, width: 2, height: 2, originalHash: sha256(png) },
  ];
  for (const record of records) {
    assert.equal(record.metadata.eventId, eventId);
    record.thumbnailHash = await checkThumbnail(record);
    await request(record.metadata.thumbnailUrl.replace(eventId, otherEventId), { token: participant, status: 404 });
  }
  await checkCatalog(eventId, records);
  writeFileSync(statePath, JSON.stringify({ eventId, records }), { mode: 0o600 });
  console.log(`JPEG and PNG upload, multipart boundaries, protected thumbnails and private file hashes verified${nginx ? ' through Nginx' : ' (Servlet limits)'}`);
} else {
  const { eventId, records } = JSON.parse(readFileSync(statePath, 'utf8'));
  await checkCatalog(eventId, records);
  for (const record of records) {
    assert.equal(await checkThumbnail(record), record.thumbnailHash, 'Thumbnail bytes must survive the restart');
  }
  console.log('Photo/version metadata, original hashes and protected thumbnail hashes match the pre-restart snapshot');
}
