import { afterEach, expect, it, vi } from 'vitest'
import { selectionTargetEditor } from './selectionTargetEditor'
import type { AlbumEvent } from '../api/album'

afterEach(() => vi.unstubAllGlobals())

function pendingResponse() {
  let resolve!: (value: Response) => void
  const promise = new Promise<Response>(done => { resolve = done })
  return { promise, resolve }
}

it('reads the committed target on re-entry before the original PUT response arrives', async () => {
  const trip: AlbumEvent = { id: 'trip', name: '秋游', memberRole: 'ORGANIZER', desiredPhotoCount: 3 }
  const book = { ...trip, id: 'book', name: '读书会', desiredPhotoCount: 2 }
  const session = new AbortController()
  const tripEditor = selectionTargetEditor(trip, 'organizer', session.signal)
  const bookEditor = selectionTargetEditor(book, 'organizer', session.signal)
  const reply = pendingResponse()
  const fetchMock = vi.fn<typeof fetch>().mockReturnValueOnce(reply.promise)
    .mockResolvedValueOnce(Response.json([{ ...trip, desiredPhotoCount: 4 }, book]))
  vi.stubGlobal('fetch', fetchMock)
  const save = tripEditor.save(4)
  await bookEditor.refreshIfUncertain()
  await tripEditor.refreshIfUncertain()
  expect(tripEditor.saving).toBe(true)
  expect(trip.desiredPhotoCount).toBe(4)
  expect(book.desiredPhotoCount).toBe(2)
  expect(fetchMock.mock.calls.map(([url]) => url)).toEqual(['/api/events/trip/selection-target', '/api/events'])
  reply.resolve(new Response(null, { status: 204 }))
  await save
  expect(tripEditor.uncertain).toBe(false)
})

it('discards a stale read that finishes after the PUT has been acknowledged', async () => {
  const trip: AlbumEvent = { id: 'trip', name: '秋游', memberRole: 'ORGANIZER', desiredPhotoCount: 3 }
  const editor = selectionTargetEditor(trip, 'organizer', new AbortController().signal)
  const put = pendingResponse()
  const get = pendingResponse()
  vi.stubGlobal('fetch', vi.fn().mockReturnValueOnce(put.promise).mockReturnValueOnce(get.promise))
  const saving = editor.save(4)
  const reading = editor.refreshIfUncertain()
  put.resolve(new Response(null, { status: 204 }))
  await saving
  get.resolve(Response.json([{ ...trip, desiredPhotoCount: 3 }]))
  await reading
  expect(trip.desiredPhotoCount).toBe(4)
})
