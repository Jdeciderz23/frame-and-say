import { albumRequest, photoPath, type AlbumPhoto } from './album'

export interface VersionEntry {
  versionNumber: number
  photo: AlbumPhoto & { rosterConfirmed: boolean }
}

export interface PhotoVersionHistory {
  canCreateVersion: boolean
  versions: VersionEntry[]
}

function versionsPath(eventId: string, photoId: string) {
  return `${photoPath(eventId, photoId)}/versions`
}

export async function listVersions(eventId: string, photoId: string, token: string, signal: AbortSignal): Promise<PhotoVersionHistory> {
  return (await albumRequest(versionsPath(eventId, photoId), token, { signal })).json()
}

export async function saveVersion(
  eventId: string, photoId: string, baseVersionId: string, file: File, token: string, signal: AbortSignal,
): Promise<VersionEntry> {
  const body = new FormData()
  body.set('baseVersionId', baseVersionId)
  body.set('file', file)
  return (await albumRequest(versionsPath(eventId, photoId), token, { method: 'POST', body, signal })).json()
}
