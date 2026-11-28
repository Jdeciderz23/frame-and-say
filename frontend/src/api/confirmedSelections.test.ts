import { afterEach, expect, it, vi } from 'vitest'
import { confirmSelection, listSelections } from './confirmedSelections'

afterEach(() => vi.unstubAllGlobals())

it('posts the reviewed versions and revisions in display order using the selected purpose', async () => {
  const photos = [
    { photoId: 'b', photoVersionId: 'v2', subjectRevision: 3, number: 1 },
    { photoId: 'a', photoVersionId: 'v1', subjectRevision: 1, number: 2 },
  ]
  const fetchMock = vi.fn().mockResolvedValue(Response.json({ selectionId: 'saved' }, { status: 201 }))
  vi.stubGlobal('fetch', fetchMock)
  const signal = new AbortController().signal
  await confirmSelection('one/event', 'organizer', 'PUBLIC_POST', photos, signal)
  const [path, request] = fetchMock.mock.calls[0]
  expect(path).toBe('/api/events/one%2Fevent/confirmed-selections')
  expect(request).toMatchObject({ method: 'POST', signal })
  expect(request.headers.get('Authorization')).toBe('Bearer organizer')
  expect(JSON.parse(request.body)).toStrictEqual({ purpose: 'PUBLIC_POST', items: [
    { photoId: 'b', photoVersionId: 'v2', subjectRevision: 3 },
    { photoId: 'a', photoVersionId: 'v1', subjectRevision: 1 },
  ] })
})

it('reloads the member view from the server with the current identity after permissions change', async () => {
  const visible = [{ selectionId: 'saved', eventId: 'event', purpose: 'INTERNAL_SHARE', items: [
    { photoId: 'a', photoVersionId: 'v1', subjectRevision: 1 },
  ] }]
  const fetchMock = vi.fn().mockResolvedValueOnce(Response.json(visible)).mockResolvedValueOnce(Response.json([]))
  vi.stubGlobal('fetch', fetchMock)
  const signal = new AbortController().signal
  expect(await listSelections('event', 'member', signal)).toEqual(visible)
  expect(await listSelections('event', 'member', signal)).toEqual([])
  for (const [path, request] of fetchMock.mock.calls) {
    expect(path).toBe('/api/events/event/confirmed-selections')
    expect(request.headers.get('Authorization')).toBe('Bearer member')
    expect(request).toMatchObject({ signal, cache: 'no-store' })
  }
})
