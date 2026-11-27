import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useMemberEvents } from './useMemberEvents'

beforeEach(() => {
  const entries = new Map<string, string>()
  vi.stubGlobal('sessionStorage', {
    getItem: (key: string) => entries.get(key) ?? null,
    setItem: (key: string, value: string) => entries.set(key, value),
    removeItem: (key: string) => entries.delete(key),
  })
})

afterEach(() => vi.unstubAllGlobals())

function filledForm() {
  const form = useMemberEvents('member-a', new AbortController().signal)
  form.name.value = '秋游'
  form.count.value = 4
  return form
}

it.each([502, 504])('reconciles a committed POST followed by HTTP %i using GET only', async status => {
  const saved: { id: string; name: string; desiredPhotoCount: number }[] = []
  const methods: string[] = []
  let listResponseValid = false
  vi.stubGlobal('fetch', async (_url: string, options: RequestInit) => {
    methods.push(options.method ?? 'GET')
    if (options.method === 'POST') {
      saved.push({ ...JSON.parse(String(options.body)), id: String(saved.length + 1) })
      return new Response(null, { status })
    }
    return Response.json(listResponseValid ? saved : { error: 'invalid list' })
  })

  const form = filledForm()
  await form.submit()
  expect(form.recovery.value).toBe('needs-list')
  form.finishCheck(false)
  await form.submit()
  await form.refresh()
  expect(form.listError.value).toBeTruthy()
  expect(form.recovery.value).toBe('needs-list')
  listResponseValid = true
  await form.refresh()
  expect(form.events.value).toEqual(saved)
  expect(form.recovery.value).toBe('needs-choice')
  await form.submit()
  expect(form.name.value).toBe('秋游')
  expect(form.count.value).toBe(4)
  form.finishCheck(true)
  expect(form.name.value).toBe('')
  expect(form.recovery.value).toBe('none')
  expect(saved).toHaveLength(1)
  expect(methods).toEqual(['POST', 'GET', 'GET'])
})

it('retains the draft and waits for an explicit retry after the member checks an empty list', async () => {
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(new Response(null, { status: 504 }))
    .mockResolvedValueOnce(Response.json([]))
    .mockResolvedValueOnce(Response.json({ id: '1', name: '秋游', desiredPhotoCount: 4 }, { status: 201 }))
    .mockResolvedValueOnce(Response.json([{ id: '1', name: '秋游', desiredPhotoCount: 4 }]))
  vi.stubGlobal('fetch', fetchMock)
  const form = filledForm()
  await form.submit()
  await form.refresh()
  await form.submit()
  form.finishCheck(false)
  expect(form.name.value).toBe('秋游')
  expect(form.count.value).toBe(4)
  expect(fetchMock).toHaveBeenCalledTimes(2)
  await form.submit()
  const keys = fetchMock.mock.calls.filter(([, options]) => options.method === 'POST')
    .map(([, options]) => new Headers(options.headers).get('Idempotency-Key'))
  expect(keys[0]).toBeTruthy()
  expect(keys[1]).toBe(keys[0])
  expect(form.events.value).toHaveLength(1)
  expect(fetchMock.mock.calls.map(([, options]) => options.method ?? 'GET')).toEqual(['POST', 'GET', 'POST', 'GET'])
})

it('keeps a definite validation rejection editable without requiring a list check', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 400 })))
  const form = filledForm()
  await form.submit()
  expect(form.recovery.value).toBe('none')
  expect(form.formError.value).toContain('检查活动名称')
  expect(form.name.value).toBe('秋游')
})

it('after a reload, a retry and the delayed original POST create a single activity', async () => {
  const saved = new Map<string, { id: string; name: string; desiredPhotoCount: number }>()
  const keys: string[] = []
  function commit(key: string) {
    if (!saved.has(key)) saved.set(key, { id: String(saved.size + 1), name: '秋游', desiredPhotoCount: 4 })
    return saved.get(key)!
  }
  vi.stubGlobal('fetch', async (_url: string, options: RequestInit) => {
    if (options.method !== 'POST') return Response.json([...saved.values()])
    const key = new Headers(options.headers).get('Idempotency-Key')!
    expect(key).toBeTruthy()
    keys.push(key)
    if (keys.length === 1) return new Response(null, { status: 504 })
    return Response.json(commit(key), { status: 201 })
  })
  const original = filledForm()
  original.name.value = ' 秋游 '
  await original.submit()
  const reloaded = useMemberEvents('member-a', new AbortController().signal)
  await reloaded.refresh()
  expect(reloaded.events.value).toEqual([])
  expect(reloaded.recovery.value).toBe('needs-choice')
  expect(reloaded.name.value).toBe(' 秋游 ')
  expect(reloaded.count.value).toBe(4)
  await reloaded.submit()
  expect(keys).toHaveLength(1)
  reloaded.finishCheck(false)
  await reloaded.submit()
  const lateResult = commit(keys[0])
  expect(keys).toEqual([keys[0], keys[0]])
  expect(saved.size).toBe(1)
  expect(reloaded.events.value[0].id).toBe(lateResult.id)
  const completed = useMemberEvents('member-a', new AbortController().signal)
  await completed.refresh()
  expect(completed.recovery.value).toBe('none')
  expect(completed.name.value).toBe('')
})

it('persists before dispatch, isolates identities and clears an acknowledged attempt', async () => {
  let sent!: () => void
  const inFlight = new Promise<void>(resolve => { sent = resolve })
  let finish!: (response: Response) => void
  const reply = new Promise<Response>(resolve => { finish = resolve })
  vi.stubGlobal('fetch', async (_url: string, options: RequestInit) => {
    if (options.method !== 'POST') return Response.json([])
    sent()
    return reply
  })
  const oldPage = new AbortController()
  const first = useMemberEvents('member-a', oldPage.signal)
  first.name.value = '无目标活动'
  const submitting = first.submit()
  await inFlight
  oldPage.abort()

  const otherMember = useMemberEvents('member-b', new AbortController().signal)
  await otherMember.refresh()
  expect(otherMember.name.value).toBe('')
  expect(otherMember.recovery.value).toBe('none')
  finish(Response.json({ id: '1', name: '无目标活动' }, { status: 201 }))
  await submitting
  const owner = useMemberEvents('member-a', new AbortController().signal)
  await owner.refresh()
  expect(owner.name.value).toBe('无目标活动')
  expect(owner.count.value).toBe('')
  expect(owner.recovery.value).toBe('needs-choice')
  owner.finishCheck(true)
  const next = useMemberEvents('member-a', new AbortController().signal)
  await next.refresh()
  expect(next.name.value).toBe('')
  expect(next.recovery.value).toBe('none')
})
