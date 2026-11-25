export const localTokenKey = 'frame-and-say:local-token'

export interface AlbumEvent {
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

export function photoPath(eventId: string, photoId: string) {
  return `${eventPath(eventId)}/photos/${encodeURIComponent(photoId)}`
}

function isAlbumEvent(value: unknown): value is AlbumEvent {
  return typeof value === 'object' && value !== null
    && 'id' in value && typeof value.id === 'string' && value.id.length > 0
    && 'name' in value && typeof value.name === 'string'
}

export async function listEvents(token: string, signal: AbortSignal): Promise<AlbumEvent[]> {
  const events: unknown = await (await albumRequest('/api/events', token, { signal })).json()
  if (!Array.isArray(events) || !events.every(isAlbumEvent)) throw new Error('活动列表响应无效')
  return events
}

export async function createEvent(
  input: { name: string; desiredPhotoCount?: number },
  token: string,
  signal: AbortSignal,
  requestId: string = crypto.randomUUID(),
): Promise<AlbumEvent> {
  const response = await albumRequest('/api/events', token, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': requestId },
    body: JSON.stringify({ name: input.name, desiredPhotoCount: input.desiredPhotoCount }),
    signal,
  })
  const event: unknown = await response.json()
  if (!isAlbumEvent(event)) throw new Error('创建响应缺少活动信息')
  return event
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
