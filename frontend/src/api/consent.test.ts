import { afterEach, expect, it, vi } from 'vitest'
import { readConsent, saveConsent, type MyConsent } from './consent'

const photo = { photoId: 'photo', photoVersionId: 'version', thumbnailAvailable: true }
const token = 'participant-token'
const signal = new AbortController().signal
const pending: MyConsent = {
  photoVersionId: 'version', subjectRevision: 1, purpose: 'INTERNAL_SHARE', decision: 'PENDING', canDecide: true,
}

afterEach(() => vi.unstubAllGlobals())

it('reads and saves a decision scoped to the current version, roster and purpose', async () => {
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(Response.json(pending))
    .mockResolvedValueOnce(new Response(null, { status: 204 }))
    .mockResolvedValueOnce(Response.json({ ...pending, purpose: 'PUBLIC_POST', decision: 'DENY' }))
  vi.stubGlobal('fetch', fetchMock)
  const current = await readConsent('event', photo, token, 'INTERNAL_SHARE', signal)
  expect(current).toEqual(pending)
  await saveConsent('event', photo, token, current, 'ALLOW', signal)
  expect(await readConsent('event', photo, token, 'PUBLIC_POST', signal)).toMatchObject({ purpose: 'PUBLIC_POST', decision: 'DENY' })
  expect(fetchMock.mock.calls[0][0]).toContain('photoVersionId=version&purpose=INTERNAL_SHARE')
  expect(fetchMock.mock.calls[1][1].body).toBe(JSON.stringify({
    photoVersionId: 'version', subjectRevision: 1, purpose: 'INTERNAL_SHARE', decision: 'ALLOW',
  }))
  expect(fetchMock.mock.calls[2][0]).toContain('photoVersionId=version&purpose=PUBLIC_POST')
})

it('leaves a stale revision for the caller to refresh without replaying the choice', async () => {
  const fetchMock = vi.fn().mockResolvedValueOnce(new Response(null, { status: 409 }))
  vi.stubGlobal('fetch', fetchMock)
  await expect(saveConsent('event', photo, token, pending, 'ALLOW', signal)).rejects.toMatchObject({ status: 409 })
  expect(fetchMock).toHaveBeenCalledTimes(1)
})
