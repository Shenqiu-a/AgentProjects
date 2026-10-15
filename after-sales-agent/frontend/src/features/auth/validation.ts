import type { LoginCredentials } from '../../types/auth'

export function validateCredentials(credentials: LoginCredentials): { username?: string; password?: string } {
  const errors: { username?: string; password?: string } = {}
  const username = credentials.username.trim()
  if (username.length < 3 || username.length > 64) errors.username = '请输入 3–64 个字符的账号。'
  // 不 trim 密码，空格可能是用户密码的一部分。
  if (!credentials.password.trim() || credentials.password.length < 8 || credentials.password.length > 128) {
    errors.password = '请输入 8–128 个字符的密码。'
  } else if (new TextEncoder().encode(credentials.password).length > 72) {
    errors.password = '密码编码后不能超过 72 字节，请检查输入。'
  }
  return errors
}
