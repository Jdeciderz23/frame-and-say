import { afterEach, describe, expect, it, vi } from 'vitest'
import { createEvent, listEvents } from './album'

afterEach(() => vi.unstubAllGlobals())

describe('member events', () => {
  const signal = new AbortController().signal

  it('lists memberships using only the active bearer token', async () => {
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(Response.json([]))
    vi.stubGlobal('fetch', fetchMock)
    await expect(listEvents('member-b', signal)).resolves.toEqual([])
    const [path, options] = fetchMock.mock.calls[0]
    expect(path).toBe('/api/events')
    expect(new Headers(options?.headers).get('Authorization')).toBe('Bearer member-b')
    expect(options).toMatchObject({ signal, cache: 'no-store' })
  })

  it.each([undefined, 1, 100])('creates with target %s and leaves identity to the server', async (target) => {
    const input = { name: '秋游  合照', desiredPhotoCount: target }
    const created = { ...input, id: 'event-1', desiredPhotoCount: target ?? null }
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(Response.json(created, { status: 201 }))
    vi.stubGlobal('fetch', fetchMock)
    await expect(createEvent(input, 'member-a', signal)).resolves.toEqual(created)
    const [path, options] = fetchMock.mock.calls[0]
    expect(path).toBe('/api/events')
    expect(options).toMatchObject({ method: 'POST', signal })
    const headers = new Headers(options?.headers)
    expect(headers.get('Authorization')).toBe('Bearer member-a')
    expect(headers.get('Content-Type')).toBe('application/json')
    expect(JSON.parse(String(options?.body))).toStrictEqual(target === undefined
      ? { name: input.name } : { name: input.name, desiredPhotoCount: target })
  })

  it.each([400, 401])('surfaces HTTP %i without replaying the creation request', async (status) => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status }))
    vi.stubGlobal('fetch', fetchMock)
    await expect(createEvent({ name: '秋游' }, 'member-a', signal)).rejects.toMatchObject({ status })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})
