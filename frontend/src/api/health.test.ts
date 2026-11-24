import { afterEach, describe, expect, it, vi } from 'vitest'
import { getHealth } from './health'

afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('health API contract', () => {
  it('returns the backend application health response', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ status: 'UP', application: 'group-photo-consensus' })))
    vi.stubGlobal('fetch', fetchMock)
    await expect(getHealth()).resolves.toEqual({ status: 'UP', application: 'group-photo-consensus' })
    expect(fetchMock).toHaveBeenCalledWith('/api/health', expect.objectContaining({
      headers: { Accept: 'application/json' },
      cache: 'no-store',
      signal: expect.any(AbortSignal),
    }))
  })

  it('rejects HTTP failures rather than showing success', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 503 })))
    await expect(getHealth()).rejects.toThrow('HTTP 503')
  })

  it('turns network failures into a recoverable user message', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    await expect(getHealth()).rejects.toThrow('请确认后端已启动')
  })

  it('rejects an unrelated or incomplete JSON response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ status: 'UP' }))))
    await expect(getHealth()).rejects.toThrow('不符合接口约定')
  })

  it('rejects invalid JSON instead of crashing the page', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>not JSON</html>')))
    await expect(getHealth()).rejects.toThrow('不符合接口约定')
  })

  it('aborts an unresponsive request after five seconds', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('fetch', vi.fn((_url: string, options: RequestInit) => new Promise((_resolve, reject) => {
      options.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
    })))
    const request = expect(getHealth()).rejects.toThrow('连接超时')
    await vi.advanceTimersByTimeAsync(5000)
    await request
  })
})
