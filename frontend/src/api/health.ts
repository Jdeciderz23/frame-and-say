export interface HealthStatus {
  status: 'UP'
  application: 'group-photo-consensus'
}

export class HealthCheckError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'HealthCheckError'
  }
}

/** Only checks the HTTP application. It does not assert database/storage readiness. */
export async function getHealth(): Promise<HealthStatus> {
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 5000)
  try {
    const response = await fetch('/api/health', {
      headers: { Accept: 'application/json' },
      signal: controller.signal,
    })
    if (!response.ok) throw new HealthCheckError(`服务返回 HTTP ${response.status}，请检查后端运行状态。`)
    const payload: unknown = await response.json()
    if (
      typeof payload !== 'object' || payload === null ||
      !('status' in payload) || payload.status !== 'UP' ||
      !('application' in payload) || payload.application !== 'group-photo-consensus'
    ) {
      throw new HealthCheckError('健康检查响应不符合接口约定，请核对前后端配置。')
    }
    return { status: payload.status, application: payload.application }
  } catch (error) {
    if (error instanceof HealthCheckError) throw error
    if (error instanceof Error && error.name === 'AbortError') {
      throw new HealthCheckError('连接超时，请检查后端后重试。')
    }
    throw new HealthCheckError('无法读取健康检查结果，请确认后端已启动且代理配置正确。')
  } finally {
    clearTimeout(timeout)
  }
}
