export type HealthResponse = {
  status: string
  service: string
  timestamp: string
}

export type Customer = {
  id: number
  name: string
  manager: string
  type: string
  balance: number
  status: string
  due: string
}

export type Task = {
  id: number
  title: string
  owner: string
  deadline: string
  status: string
  priority: string
}

export type AuthUser = {
  username: string
}

export async function fetchHealth(): Promise<HealthResponse> {
  const response = await fetch('/api/health')
  if (!response.ok) {
    throw new Error('后端服务不可用')
  }
  const body = await response.json() as { data: HealthResponse }
  return body.data
}

export async function fetchCurrentUser(): Promise<AuthUser | null> {
  const response = await fetch('/api/auth/me')
  if (response.status === 401) {
    return null
  }
  if (!response.ok) {
    throw new Error(`登录状态检查失败: ${response.status}`)
  }
  const body = await response.json() as { data: AuthUser }
  return body.data
}

async function fetchCsrfToken(): Promise<string> {
  const response = await fetch('/api/auth/csrf')
  if (!response.ok) {
    throw new Error('无法获取安全令牌')
  }
  const body = await response.json() as { data: { token: string } }
  return body.data.token
}

export async function signIn(username: string, password: string): Promise<void> {
  const token = await fetchCsrfToken()
  const response = await fetch('/api/auth/login', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-XSRF-TOKEN': token,
    },
    body: JSON.stringify({ username, password }),
  })
  if (!response.ok) {
    if (response.status === 401) {
      throw new Error('账号或密码错误')
    }
    throw new Error(`登录失败: ${response.status}`)
  }
}

export async function signOut(): Promise<void> {
  const token = await fetchCsrfToken()
  const response = await fetch('/api/auth/logout', {
    method: 'POST',
    headers: { 'X-XSRF-TOKEN': token },
  })
  if (!response.ok) {
    throw new Error(`退出登录失败: ${response.status}`)
  }
}

async function fetchApi<T>(path: string): Promise<T> {
  const response = await fetch(path)
  if (!response.ok) {
    throw new Error(`接口请求失败: ${response.status}`)
  }
  const body = await response.json() as { data: T }
  return body.data
}

export function fetchCustomers(): Promise<Customer[]> {
  return fetchApi<Customer[]>('/api/customers')
}

export function fetchTasks(): Promise<Task[]> {
  return fetchApi<Task[]>('/api/tasks')
}
