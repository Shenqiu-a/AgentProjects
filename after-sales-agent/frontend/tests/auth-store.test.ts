import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({ getCurrentUser: vi.fn(), login: vi.fn(), logout: vi.fn(), clearCsrf: vi.fn() }))
vi.mock('../src/api/auth', () => mocks)
const user = { id: 'test-id', username: 'test-user', role: 'CUSTOMER' }

beforeEach(() => {
  vi.resetModules()
  vi.resetAllMocks()
})

describe('登录状态管理', () => {
  it('刷新时从服务器恢复身份，初始化并发调用只发一次请求', async () => {
    mocks.getCurrentUser.mockResolvedValue(user)
    const { auth } = await import('../src/stores/auth')
    await Promise.all([auth.initialize(), auth.initialize()])
    expect(auth.state.user).toEqual(user)
    expect(auth.state.initialized).toBe(true)
    expect(mocks.getCurrentUser).toHaveBeenCalledTimes(1)
  })

  it('401 退出旧身份；503 保留已核验的身份并提示故障', async () => {
    const { ApiError } = await import('../src/lib/http')
    const { auth } = await import('../src/stores/auth')
    mocks.getCurrentUser.mockResolvedValueOnce(user)
    await auth.restore()
    mocks.getCurrentUser.mockRejectedValueOnce(new ApiError('服务不可用', 503, 'AUTH_SERVICE_UNAVAILABLE'))
    await auth.restore()
    expect(auth.state.user).toEqual(user)
    expect(auth.state.error).toBe('服务不可用')
    mocks.getCurrentUser.mockRejectedValueOnce(new ApiError('请登录', 401, 'UNAUTHENTICATED'))
    await auth.restore()
    expect(auth.state.user).toBeNull()
    expect(auth.state.notice).toContain('已失效')
    expect(auth.state.error).toBe('')
  })

  it('重复点击登录不会同时提交两个请求', async () => {
    const { auth } = await import('../src/stores/auth')
    let finish!: (value: typeof user) => void
    mocks.login.mockReturnValueOnce(new Promise((resolve) => { finish = resolve }))
    const first = auth.signIn({ username: 'test-user', password: 'test-only-password' })
    expect(await auth.signIn({ username: 'test-user', password: 'test-only-password' })).toBe(false)
    expect(mocks.login).toHaveBeenCalledTimes(1)
    finish(user)
    expect(await first).toBe(true)
    expect(auth.state.busy).toBe(false)
  })

  it('退出请求失败时保留身份，成功后再清空', async () => {
    const { auth } = await import('../src/stores/auth')
    mocks.getCurrentUser.mockResolvedValueOnce(user)
    await auth.restore()
    mocks.logout.mockRejectedValueOnce(new Error('连接失败'))
    expect(await auth.signOut()).toBe(false)
    expect(auth.state.user).toEqual(user)
    mocks.logout.mockResolvedValueOnce(undefined)
    expect(await auth.signOut()).toBe(true)
    expect(auth.state.user).toBeNull()
    expect(auth.state.notice).toContain('已安全退出')
  })
})
