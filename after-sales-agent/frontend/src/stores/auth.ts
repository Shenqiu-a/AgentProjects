import { computed, reactive, readonly } from 'vue'
import * as api from '../api/auth'
import { ApiError } from '../lib/http'
import type { LoginCredentials, User } from '../types/auth'

const state = reactive({
  user: null as User | null,
  initialized: false,
  busy: false,
  error: '',
  requestId: '',
  notice: '',
})
let initialization: Promise<void> | undefined

function setError(error: unknown): void {
  state.error = error instanceof Error ? error.message : '操作失败，请稍后重试。'
  state.requestId = error instanceof ApiError ? error.requestId ?? '' : ''
}

function clearError(): void {
  state.error = ''
  state.requestId = ''
}

async function restore(): Promise<void> {
  if (state.busy) return
  state.busy = true
  clearError()
  try {
    state.user = await api.getCurrentUser()
    state.notice = ''
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      if (state.user) state.notice = '登录状态已失效，请重新登录。'
      state.user = null
      api.clearCsrf()
    } else {
      // 暂时断网时保留已有身份展示，不把网络故障当作已退出。
      setError(error)
    }
  } finally {
    state.initialized = true
    state.busy = false
  }
}

function initialize(): Promise<void> {
  initialization ??= restore()
  return initialization
}

async function signIn(credentials: LoginCredentials): Promise<boolean> {
  if (state.busy) return false
  state.busy = true
  state.notice = ''
  clearError()
  try {
    state.user = await api.login(credentials)
    return true
  } catch (error) {
    state.user = null
    setError(error)
    return false
  } finally {
    state.busy = false
  }
}

async function signOut(): Promise<boolean> {
  if (state.busy) return false
  state.busy = true
  clearError()
  try {
    await api.logout()
    state.user = null
    state.notice = '已安全退出登录。'
    return true
  } catch (error) {
    setError(error)
    return false
  } finally {
    state.busy = false
  }
}

export const auth = {
  state: readonly(state),
  isAuthenticated: computed(() => state.user !== null),
  initialize,
  restore,
  signIn,
  signOut,
  clearError,
}
