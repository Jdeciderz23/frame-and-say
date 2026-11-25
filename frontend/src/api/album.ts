export interface AlbumEvent {
  memberRole: 'ORGANIZER' | 'PARTICIPANT'
  id: string
  name: string
  desiredPhotoCount?: number | null
}

export interface AlbumPhoto {
  photoId: string
  photoVersionId: string
  thumbnailAvailable: boolean
}

export class AlbumRequestError extends Error {
  constructor(readonly status: number) {
    super(status === 401 ? '联调令牌无效，请重新连接。'
      : status === 403 ? '当前身份无法访问此活动或照片。'
      : `请求失败（HTTP ${status}），请重试。`)
  }
}

export async function albumRequest(path: string, token: string, options: RequestInit = {}) {
  const headers = new Headers(options.headers)
  headers.set('Authorization', `Bearer ${token}`)
  const response = await fetch(path, { ...options, headers, cache: 'no-store', redirect: 'error' })
  if (!response.ok) throw new AlbumRequestError(response.status)
  return response
}

export function albumError(error: unknown) {
  return error instanceof AlbumRequestError ? error.message : '无法连接服务，请重试。'
}

export function eventPath(eventId: string) {
  return `/api/events/${encodeURIComponent(eventId)}`
}

export async function setSelectionTarget(eventId: string, desiredPhotoCount: number, token: string, signal: AbortSignal) {
  await albumRequest(`${eventPath(eventId)}/selection-target`, token, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ desiredPhotoCount }),
    signal,
  })
}

export function photoPath(eventId: string, photoId: string) {
  return `${eventPath(eventId)}/photos/${encodeURIComponent(photoId)}`
}

export async function listEvents(token: string, signal: AbortSignal): Promise<AlbumEvent[]> {
  return (await albumRequest('/api/events', token, { signal })).json()
}

export async function listPhotos(eventId: string, token: string, signal: AbortSignal): Promise<AlbumPhoto[]> {
  return (await albumRequest(`${eventPath(eventId)}/photos`, token, { signal })).json()
}

export async function readThumbnail(eventId: string, photo: AlbumPhoto, token: string, signal: AbortSignal) {
  const path = `${photoPath(eventId, photo.photoId)}/versions/${encodeURIComponent(photo.photoVersionId)}/thumbnail`
  const response = await albumRequest(path, token, { signal })
  return response.blob()
}
