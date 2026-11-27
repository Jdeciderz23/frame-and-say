export interface CreationAttempt {
  id: string
  name: string
  target?: number
}

export async function pendingCreationKey(token: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(token))
  const member = Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('')
  return 'frame-and-say:pending-event:' + member
}

export function readPendingCreation(key: string): CreationAttempt | undefined {
  const raw = sessionStorage.getItem(key)
  if (raw === null) return
  const record = JSON.parse(raw)
  if (typeof record?.id !== 'string' || !record.id || typeof record.name !== 'string' ||
      (record.target !== undefined && !Number.isInteger(record.target))) {
    throw new Error('待确认的活动记录无效')
  }
  return record
}
