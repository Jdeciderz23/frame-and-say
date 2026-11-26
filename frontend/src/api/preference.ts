import { albumRequest, photoPath, type AlbumPhoto } from './album'

export type Preference = -1 | 0 | 1

export async function readPreference(eventId: string, photo: AlbumPhoto, token: string, signal: AbortSignal) {
  const search = new URLSearchParams({ photoVersionId: photo.photoVersionId })
  const res = await albumRequest(`${photoPath(eventId, photo.photoId)}/preferences/me?${search}`, token, { signal })
  const result: { value: Preference | null } = await res.json()
  return result.value
}

export async function savePreference(eventId: string, photo: AlbumPhoto, token: string, value: Preference, signal: AbortSignal) {
  await albumRequest(`${photoPath(eventId, photo.photoId)}/preferences/me`, token, {
    signal,
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ photoVersionId: photo.photoVersionId, value }),
  })
}
