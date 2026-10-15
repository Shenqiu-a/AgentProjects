export interface User {
  id: string
  username: string
  role: string
}

export interface LoginCredentials {
  username: string
  password: string
}

export interface CsrfToken {
  headerName: string
  token: string
}
