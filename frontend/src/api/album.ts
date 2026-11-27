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

export async function uploadPhoto(eventId: string, file: File, token: string, signal: AbortSignal) {
  const body = new FormData()
  body.set('file', file)
  await albumRequest(`${eventPath(eventId)}/photos`, token, { method: 'POST', body, signal })
}

export function photoUploadOutcomeUnknown(error: unknown) {
  return !(error instanceof AlbumRequestError) || error.status === 502 || error.status === 504
}

export function photoUploadError(error: unknown) {
  if (!(error instanceof AlbumRequestError) || photoUploadOutcomeUnknown(error)) {
    return '无法确认上传结果。请先刷新照片列表，再决定是否重试。'
  }
  switch (error.status) {
    case 400: return '无法读取所选文件，请重新选择。'
    case 409: return '此活动已达到 100 张照片的上限。'
    case 413: return '照片超出文件大小或像素限制，请缩小后再上传。'
    case 415: return '所选文件不是可解码的 JPEG 或 PNG。'
    case 503: return '图片存储暂不可用，请稍后重试。'
    default: return error.message
  }
}

export async function readThumbnail(eventId: string, photo: AlbumPhoto, token: string, signal: AbortSignal) {
  const path = `${photoPath(eventId, photo.photoId)}/versions/${encodeURIComponent(photo.photoVersionId)}/thumbnail`
  const response = await albumRequest(path, token, { signal })
  return response.blob()
}
