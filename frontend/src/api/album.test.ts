import { afterEach, describe, expect, it, vi } from 'vitest'
import { listPhotos, readThumbnail } from './album'

const photo = { photoId: 'photo', photoVersionId: 'version', thumbnailAvailable: true }

afterEach(() => vi.unstubAllGlobals())

describe('event photo browsing', () => {
  const signal = new AbortController().signal

  it('returns an empty list for an accessible empty event', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json([])))
    await expect(listPhotos('empty', 'local-token', signal)).resolves.toEqual([])
  })

  it('requests a protected thumbnail with the member identity', async () => {
    const image = new Uint8Array([137, 80, 78, 71])
    const fetchMock = vi.fn().mockResolvedValue(new Response(image, { headers: { 'Content-Type': 'image/png' } }))
    vi.stubGlobal('fetch', fetchMock)
    const blob = await readThumbnail('event', photo, 'local-token', signal)
    expect(blob.type).toBe('image/png')
    expect(await blob.bytes()).toEqual(image)
    const [path, options] = fetchMock.mock.calls[0]
    expect(path).toBe('/api/events/event/photos/photo/versions/version/thumbnail')
    expect(options.headers.get('Authorization')).toBe('Bearer local-token')
    expect(options).toMatchObject({ cache: 'no-store', redirect: 'error', signal })
  })

  it('reports a forbidden request without yielding a displayable blob', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 403 })))
    await expect(readThumbnail('event', photo, 'other-token', signal)).rejects.toMatchObject({ status: 403 })
  })
})
