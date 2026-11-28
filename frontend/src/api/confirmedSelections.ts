import { albumRequest, eventPath } from './album'
import type { Purpose } from './consent'

export interface ReviewedPhoto {
  photoId: string
  photoVersionId: string
  subjectRevision: number
}

export interface ConfirmedSelection {
  selectionId: string
  eventId: string
  purpose: Purpose
  items: ReviewedPhoto[]
}

export async function listSelections(eventId: string, token: string, signal: AbortSignal): Promise<ConfirmedSelection[]> {
  return (await albumRequest(`${eventPath(eventId)}/confirmed-selections`, token, { signal })).json()
}

export async function confirmSelection(eventId: string, token: string, purpose: Purpose, photos: ReviewedPhoto[],
  signal: AbortSignal): Promise<ConfirmedSelection> {
  const items = photos.map(({ photoId, photoVersionId, subjectRevision }) => ({ photoId, photoVersionId, subjectRevision }))
  return (await albumRequest(`${eventPath(eventId)}/confirmed-selections`, token, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, signal, body: JSON.stringify({ purpose, items }),
  })).json()
}
