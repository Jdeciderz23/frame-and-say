import { afterEach, describe, expect, it, vi } from 'vitest'
import { listPhotos, photoUploadError, readThumbnail, setSelectionTarget, uploadPhoto } from './album'

const photo = { photoId: 'photo', photoVersionId: 'version', thumbnailAvailable: true }

afterEach(() => vi.unstubAllGlobals())

describe('event photo browsing', () => {
  const signal = new AbortController().signal

  it('updates only the selected event target using the caller token', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)
    await setSelectionTarget('event-a', 4, 'current-member', signal)
    const [path, options] = fetchMock.mock.calls[0]
    expect(path).toBe('/api/events/event-a/selection-target')
    expect(options.headers.get('Authorization')).toBe('Bearer current-member')
    expect(options.method).toBe('PUT')
    expect(JSON.parse(options.body)).toStrictEqual({ desiredPhotoCount: 4 })
  })

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

describe('event photo upload', () => {
  const signal = new AbortController().signal
  const image = new Uint8Array([137, 80, 78, 71])
  const file = new File([image], 'synthetic.png', { type: 'image/png' })

  it('sends one multipart file and authenticates as the selected member', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 201 }))
    vi.stubGlobal('fetch', fetchMock)

    await uploadPhoto('selected/event', file, 'participant-token', signal)

    expect(fetchMock).toHaveBeenCalledOnce()
    const [path, options] = fetchMock.mock.calls[0]
    expect(path).toBe('/api/events/selected%2Fevent/photos')
    expect(options).toMatchObject({ method: 'POST', cache: 'no-store', redirect: 'error', signal })
    const request = new Request(new URL(path, 'http://localhost'), options)
    expect(request.headers.get('Authorization')).toBe('Bearer participant-token')
    expect(request.headers.get('Content-Type')).toMatch(/^multipart\/form-data; boundary=/)
    const form = await request.formData()
    expect([...form.keys()]).toEqual(['file'])
    const uploaded = form.get('file')
    expect(uploaded).toBeInstanceOf(File)
    expect((uploaded as File).name).toBe(file.name)
    expect((uploaded as File).type).toBe(file.type)
    expect(await (uploaded as File).bytes()).toEqual(image)
  })

  it.each([
    [403, /无法访问此活动/],
    [413, /文件大小或像素限制/],
    [415, /不是可解码的 JPEG 或 PNG/],
    [503, /图片存储暂不可用/],
  ])('preserves the HTTP %i rejection without reporting a successful upload', async (status, feedback) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status })))

    await expect(uploadPhoto('event', file, 'participant-token', signal).catch(error => {
      expect(error.status).toBe(status)
      expect(photoUploadError(error)).toMatch(feedback)
      throw error
    })).rejects.toMatchObject({ status })
  })
})
