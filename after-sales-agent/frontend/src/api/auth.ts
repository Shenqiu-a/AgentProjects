import { ApiError, request } from '../lib/http'
import type { CsrfToken, LoginCredentials, User } from '../types/auth'

const base = '/api/v1/auth'
// CSRF token 仅保留在内存中；JSESSIONID 由浏览器的 HttpOnly Cookie 管理。
let csrf: CsrfToken | undefined

export function clearCsrf(): void {
  csrf = undefined
}

export async function refreshCsrf(): Promise<void> {
  clearCsrf()
  const value = await request<CsrfToken>(`${base}/csrf`)
  if (!value.headerName || !value.token) {
    throw new ApiError('安全校验初始化失败，请重试。', 200, 'INVALID_RESPONSE')
  }
  csrf = value
}

async function write<T>(path: string, body?: LoginCredentials): Promise<T> {
  // 每次写请求前取当前 Session 的 token，覆盖登录失败、过期和多标签页的轮换。
  for (let attempt = 0; attempt < 2; attempt++) {
    await refreshCsrf()
    const token = csrf!
    try {
      return await request<T>(`${base}/${path}`, {
        method: 'POST',
        headers: {
          [token.headerName]: token.token,
          ...(body ? { 'Content-Type': 'application/json' } : {}),
        },
        ...(body ? { body: JSON.stringify(body) } : {}),
      })
    } catch (error) {
      clearCsrf()
      // 仅 CSRF 拒绝表示写操作尚未执行；网络故障或其他错误不自动重放。
      if (!(error instanceof ApiError && error.code === 'CSRF_INVALID' && attempt === 0)) throw error
    }
  }
  throw new Error('CSRF retry exhausted')
}

export async function getCurrentUser(): Promise<User> {
  const user = await request<User>(`${base}/me`)
  if (!user.id || !user.username || !user.role) {
    throw new ApiError('用户信息不完整，请检查后端响应。', 200, 'INVALID_RESPONSE')
  }
  return user
}

export async function login(credentials: LoginCredentials): Promise<User> {
  await write<User>('login', credentials)
  // 登录后 Spring 会更换 Session 并清除旧 token；刷新后再确认身份。
  await refreshCsrf()
  return getCurrentUser()
}

export async function logout(): Promise<void> {
  try {
    await write<void>('logout')
  } catch (error) {
    if (!(error instanceof ApiError && error.status === 401)) throw error
  } finally {
    clearCsrf()
  }
}
