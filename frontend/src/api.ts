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

export async function fetchHealth(): Promise<HealthResponse> {
  const response = await fetch('/api/health')
  if (!response.ok) {
    throw new Error('后端服务不可用')
  }
  const body = await response.json() as { data: HealthResponse }
  return body.data
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
