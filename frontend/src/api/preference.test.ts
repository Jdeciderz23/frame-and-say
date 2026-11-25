import { afterEach, expect, it, vi } from 'vitest'
import { readPreference, savePreference } from './preference'

const photo = { photoId: 'photo', photoVersionId: 'version', thumbnailAvailable: true }
const token = 'participant-token'
const signal = new AbortController().signal

afterEach(() => vi.unstubAllGlobals())

it('distinguishes unrated from zero and saves the latest choice for the same version', async () => {
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(Response.json({ value: null }))
    .mockResolvedValueOnce(new Response(null, { status: 204 }))
    .mockResolvedValueOnce(Response.json({ value: 0 }))
    .mockResolvedValueOnce(new Response(null, { status: 204 }))
    .mockResolvedValueOnce(Response.json({ value: 1 }))
  vi.stubGlobal('fetch', fetchMock)

  await expect(readPreference('event', photo, token, signal)).resolves.toBeNull()
  await savePreference('event', photo, token, 0, signal)
  await expect(readPreference('event', photo, token, signal)).resolves.toBe(0)
  await savePreference('event', photo, token, 1, signal)
  await expect(readPreference('event', photo, token, signal)).resolves.toBe(1)
  expect(fetchMock.mock.calls[0][0]).toBe('/api/events/event/photos/photo/preferences/me?photoVersionId=version')
  expect(fetchMock.mock.calls[1][1].body).toBe(JSON.stringify({ photoVersionId: 'version', value: 0 }))
  expect(fetchMock.mock.calls[3][1].body).toBe(JSON.stringify({ photoVersionId: 'version', value: 1 }))
  expect(fetchMock.mock.calls.every(([, options]) => options.headers.get('Authorization') === `Bearer ${token}`)).toBe(true)
})

it('fails a rejected save and accepts the same selection when retried', async () => {
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(new Response(null, { status: 503 }))
    .mockResolvedValueOnce(new Response(null, { status: 204 }))
  vi.stubGlobal('fetch', fetchMock)

  await expect(savePreference('event', photo, token, 0, signal)).rejects.toMatchObject({ status: 503 })
  await expect(savePreference('event', photo, token, 0, signal)).resolves.toBeUndefined()
  expect(fetchMock.mock.calls[0][1].body).toBe(fetchMock.mock.calls[1][1].body)
})
