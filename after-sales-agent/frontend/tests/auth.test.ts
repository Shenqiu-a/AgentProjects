import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as api from '../src/api/auth'
import { ApiError, request } from '../src/lib/http'
import { validateCredentials } from '../src/features/auth/validation'

const user = { id: 'a081f153-0000-4000-8000-000000000001', username: 'test-user', role: 'CUSTOMER' }
const fetchMock = vi.fn<typeof fetch>()
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', data }), { status: 200 })
const failure = (status: number, code: string) => new Response(JSON.stringify({ code, detials: { requestId: 'test-request' } }), { status })
const csrf = (token: string) => ok({ headerName: 'X-CSRF-TOKEN', token })

beforeEach(() => {
  api.clearCsrf()
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})
afterEach(() => vi.unstubAllGlobals())

describe('HTTP 响应与错误', () => {
  it('请求携带 Cookie 且不缓存用户信息', async () => {
    fetchMock.mockResolvedValueOnce(ok(user))
    expect(await api.getCurrentUser()).toEqual(user)
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/auth/me', expect.objectContaining({ credentials: 'include', cache: 'no-store' }))
  })

  it('读取 Backend 当前拼写的请求编号，给出密码错误提示', async () => {
    fetchMock.mockResolvedValueOnce(failure(401, 'INVALID_CREDENTIALS'))
    await expect(request('/login')).rejects.toMatchObject({ code: 'INVALID_CREDENTIALS', requestId: 'test-request', message: '账号或密码不正确，请重新输入。' })
  })

  it('代理返回 HTML 时仍能提供可理解的错误', async () => {
    fetchMock.mockResolvedValueOnce(new Response('<h1>Bad Gateway</h1>', { status: 502 }))
    await expect(request('/me')).rejects.toMatchObject({ status: 502, code: 'HTTP_ERROR' })
  })

  it('拒绝不符合协议的 200 响应', async () => {
    fetchMock.mockResolvedValueOnce(new Response('{}', { status: 200 }))
    await expect(request('/me')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it('拒绝不完整的用户信息', async () => {
    fetchMock.mockResolvedValueOnce(ok({ username: 'test-user' }))
    await expect(api.getCurrentUser()).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it('网络错误不泄露底层异常', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('private internal detail'))
    await expect(request('/me')).rejects.toMatchObject({ status: 0, code: 'NETWORK_ERROR' })
  })
})

describe('Session 与 CSRF 流程', () => {
  it('先取 token 再登录，登录后刷新 token 并以 /me 确认身份', async () => {
    fetchMock.mockResolvedValueOnce(csrf('before')).mockResolvedValueOnce(ok(user)).mockResolvedValueOnce(csrf('after')).mockResolvedValueOnce(ok(user))
    expect(await api.login({ username: 'test-user', password: 'test-only-password' })).toEqual(user)
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual(['/api/v1/auth/csrf', '/api/v1/auth/login', '/api/v1/auth/csrf', '/api/v1/auth/me'])
    expect(fetchMock.mock.calls[1]?.[1]).toMatchObject({ method: 'POST', headers: { 'X-CSRF-TOKEN': 'before', 'Content-Type': 'application/json' } })
  })

  it('CSRF 过期时重新获取并只重试一次', async () => {
    fetchMock.mockResolvedValueOnce(csrf('old')).mockResolvedValueOnce(failure(403, 'CSRF_INVALID')).mockResolvedValueOnce(csrf('new')).mockResolvedValueOnce(ok(user)).mockResolvedValueOnce(csrf('logged-in')).mockResolvedValueOnce(ok(user))
    await api.login({ username: 'test-user', password: 'test-only-password' })
    expect(fetchMock.mock.calls[3]?.[1]).toMatchObject({ headers: { 'X-CSRF-TOKEN': 'new' } })
    expect(fetchMock).toHaveBeenCalledTimes(6)
  })

  it('连续 CSRF 拒绝不会无限重试', async () => {
    fetchMock.mockResolvedValueOnce(csrf('first')).mockResolvedValueOnce(failure(403, 'CSRF_INVALID')).mockResolvedValueOnce(csrf('second')).mockResolvedValueOnce(failure(403, 'CSRF_INVALID'))
    await expect(api.logout()).rejects.toBeInstanceOf(ApiError)
    expect(fetchMock).toHaveBeenCalledTimes(4)
  })

  it('密码错误和普通权限错误不自动重放登录', async () => {
    fetchMock.mockResolvedValueOnce(csrf('token')).mockResolvedValueOnce(failure(401, 'INVALID_CREDENTIALS'))
    await expect(api.login({ username: 'test-user', password: 'test-only-password' })).rejects.toMatchObject({ status: 401 })
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('写请求断网不自动重放', async () => {
    fetchMock.mockResolvedValueOnce(csrf('token')).mockRejectedValueOnce(new Error('network'))
    await expect(api.logout()).rejects.toMatchObject({ code: 'NETWORK_ERROR' })
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('退出接受无 JSON 响应的 204，下次写操作重新获取 token', async () => {
    fetchMock.mockResolvedValueOnce(csrf('logout-token')).mockResolvedValueOnce(new Response(null, { status: 204 })).mockResolvedValueOnce(csrf('anonymous-token')).mockResolvedValueOnce(failure(401, 'INVALID_CREDENTIALS'))
    await expect(api.logout()).resolves.toBeUndefined()
    await expect(api.login({ username: 'test-user', password: 'test-only-password' })).rejects.toMatchObject({ status: 401 })
    expect(fetchMock.mock.calls[3]?.[1]).toMatchObject({ headers: { 'X-CSRF-TOKEN': 'anonymous-token' } })
  })

  it('已过期的会话退出视为完成', async () => {
    fetchMock.mockResolvedValueOnce(csrf('token')).mockResolvedValueOnce(failure(401, 'UNAUTHENTICATED'))
    await expect(api.logout()).resolves.toBeUndefined()
  })
})

describe('与后端一致的输入校验', () => {
  it.each(['', 'ab', 'x'.repeat(65)])('拒绝账号长度不合法：%s', (username) => {
    expect(validateCredentials({ username, password: 'test-only-password' }).username).toBeTruthy()
  })
  it('校验账号时清除首尾空格', () => {
    expect(validateCredentials({ username: ' test-user ', password: 'test-only-password' })).toEqual({})
  })
  it.each(['', '1234567', '        ', 'x'.repeat(129)])('拒绝密码长度或内容不合法', (password) => {
    expect(validateCredentials({ username: 'test-user', password }).password).toBeTruthy()
  })
  it('按 UTF-8 字节限制 BCrypt 输入，包括中文和 emoji', () => {
    expect(validateCredentials({ username: 'test-user', password: '密'.repeat(24) })).toEqual({})
    expect(validateCredentials({ username: 'test-user', password: '密'.repeat(25) }).password).toContain('72 字节')
    expect(validateCredentials({ username: 'test-user', password: '🔐'.repeat(19) }).password).toContain('72 字节')
  })
  it('保留密码首尾空格', () => {
    expect(validateCredentials({ username: 'test-user', password: '  pass  ' })).toEqual({})
  })
})
