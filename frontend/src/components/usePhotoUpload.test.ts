import { afterEach, describe, expect, it, vi } from 'vitest'
import { listPhotos, uploadPhoto } from '../api/album'
import { usePhotoUpload } from './usePhotoUpload'

afterEach(() => vi.unstubAllGlobals())

const file = new File(['synthetic pixels'], 'synthetic.png', { type: 'image/png' })
const createdPhoto = { photoId: 'photo', photoVersionId: 'v1', thumbnailAvailable: true }

function uploadFlow(fetchMock: ReturnType<typeof vi.fn>) {
  vi.stubGlobal('fetch', fetchMock)
  let visiblePhotos: Awaited<ReturnType<typeof listPhotos>> = []
  const flow = usePhotoUpload((selected, signal) => uploadPhoto('event', selected, 'member', signal), async () => {
    visiblePhotos = await listPhotos('event', 'member', new AbortController().signal)
    return true
  })
  return {
    flow,
    photos: () => visiblePhotos,
    methods: () => fetchMock.mock.calls.map(([, options]) => options.method ?? 'GET'),
  }
}

describe('recovering an upload with an unknown result', () => {
  it.each([502, 504, 'disconnect'])('checks the album after %s without sending a second POST', async failure => {
    const fetchMock = vi.fn()
    if (typeof failure === 'number') fetchMock.mockResolvedValueOnce(new Response(null, { status: failure }))
    else fetchMock.mockRejectedValueOnce(new TypeError('connection interrupted'))
    fetchMock.mockResolvedValueOnce(Response.json([createdPhoto]))
    const { flow, photos, methods } = uploadFlow(fetchMock)
    flow.chooseFile(file)

    expect(await flow.submit()).toBe(false)
    expect(flow.state.value).toBe('needs-check')
    expect(flow.canSubmit.value).toBe(false)
    expect(flow.canSelect.value).toBe(false)
    expect(flow.finishCheck()).toBe(false)
    flow.chooseFile(file)
    await flow.submit()
    expect(methods()).toEqual(['POST'])

    const pending = flow.checkPhotos()
    expect(flow.state.value).toBe('checking')
    await flow.submit()
    await pending
    expect(flow.state.value).toBe('checked')
    expect(photos()).toEqual([createdPhoto])
    await flow.submit()
    expect(flow.finishCheck()).toBe(true)
    expect(flow.canSubmit.value).toBe(false)
    await flow.submit()
    expect(methods()).toEqual(['POST', 'GET'])
  })

  it('keeps submission locked until a failed refresh is retried successfully', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(null, { status: 504 }))
      .mockResolvedValueOnce(new Response(null, { status: 503 }))
      .mockResolvedValueOnce(Response.json([]))
    const { flow, methods } = uploadFlow(fetchMock)
    flow.chooseFile(file)
    await flow.submit()
    await flow.checkPhotos()
    expect(flow.state.value).toBe('needs-check')
    expect(flow.finishCheck()).toBe(false)
    await flow.submit()
    await flow.checkPhotos()
    expect(flow.finishCheck()).toBe(true)
    expect(flow.canSubmit.value).toBe(false)
    flow.chooseFile(file)
    expect(flow.canSubmit.value).toBe(true)
    expect(methods()).toEqual(['POST', 'GET', 'GET'])
  })
})
