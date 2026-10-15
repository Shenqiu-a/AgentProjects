export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly code: string,
    public readonly requestId?: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

interface Envelope<T> {
  code: string
  message: string
  data: T
  requestId?: string
  details?: { requestId?: string }
  // 兼容当前 Backend 错误响应的字段拼写，避免影响问题追踪。
  detials?: { requestId?: string }
}

function errorMessage(status: number, code?: string): string {
  if (code === 'INVALID_CREDENTIALS') return '账号或密码不正确，请重新输入。'
  if (code === 'CSRF_INVALID') return '安全校验已过期，请重新尝试。'
  if (status === 401) return '登录状态已失效，请重新登录。'
  if (status === 403) return '当前账号没有操作权限。'
  if (status === 429) return '操作过于频繁，请稍后再试。'
  if (status === 503) return '登录服务暂不可用，请稍后重试。'
  if (status >= 500) return '服务器暂时无法处理请求，请检查后端服务后重试。'
  if (status === 400) return '输入格式有误，请检查账号和密码。'
  return '请求未能完成，请重试。'
}

export async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  let response: Response
  try {
    response = await fetch(path, {
      ...options,
      credentials: 'include',
      cache: 'no-store',
      signal: options.signal ?? AbortSignal.timeout(12_000),
      headers: { Accept: 'application/json', ...options.headers },
    })
  } catch {
    throw new ApiError('连接超时或网络不可用，请确认后端已启动后重试。', 0, 'NETWORK_ERROR')
  }

  if (response.status === 204) return undefined as T
  let body: Envelope<T> | undefined
  try {
    body = await response.json() as Envelope<T>
  } catch {
    // 代理故障有时返回 HTML 或空响应，不能将其误报为密码错误。
  }
  if (!response.ok) {
    throw new ApiError(
      errorMessage(response.status, body?.code),
      response.status,
      body?.code ?? 'HTTP_ERROR',
      body?.requestId ?? body?.details?.requestId ?? body?.detials?.requestId,
    )
  }
  if (!body || body.code !== 'OK' || body.data == null) {
    throw new ApiError('服务响应格式异常，请检查接口配置。', response.status, 'INVALID_RESPONSE')
  }
  return body.data
}
