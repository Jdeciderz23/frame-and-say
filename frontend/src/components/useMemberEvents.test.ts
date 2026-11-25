import { afterEach, expect, it, vi } from 'vitest'
import { useMemberEvents } from './useMemberEvents'

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
    .mockRejectedValueOnce(new TypeError('connection lost'))
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
