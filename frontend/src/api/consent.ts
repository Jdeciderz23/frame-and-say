import { albumRequest, photoPath, type AlbumPhoto } from './album'

export const purposes = [
  { value: 'INTERNAL_SHARE', label: '活动群内分享' },
  { value: 'PUBLIC_POST', label: '公开发布' },
] as const

export type Purpose = typeof purposes[number]['value']
export type Decision = 'ALLOW' | 'DENY'
export type ConsentStatus = 'PENDING' | Decision

export interface MyConsent {
  photoVersionId: string
  subjectRevision: number
  purpose: Purpose
  decision: ConsentStatus
  canDecide: boolean
}

function consentPath(eventId: string, photo: AlbumPhoto) {
  return `${photoPath(eventId, photo.photoId)}/consents/me`
}

export async function readConsent(eventId: string, photo: AlbumPhoto, token: string, purpose: Purpose, signal: AbortSignal): Promise<MyConsent> {
  const query = new URLSearchParams({ photoVersionId: photo.photoVersionId, purpose })
  const response = await albumRequest(`${consentPath(eventId, photo)}?${query}`, token, { signal })
  return response.json()
}

export async function saveConsent(eventId: string, photo: AlbumPhoto, token: string, consent: MyConsent, decision: Decision, signal: AbortSignal) {
  const { photoVersionId, subjectRevision, purpose } = consent
  await albumRequest(consentPath(eventId, photo), token, {
    signal,
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ photoVersionId, subjectRevision, purpose, decision }),
  })
}
