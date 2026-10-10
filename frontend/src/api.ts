export type HealthResponse = {
  status: string
  service: string
  timestamp: string
}

export async function fetchHealth(): Promise<HealthResponse> {
  const response = await fetch('/api/health')
  if (!response.ok) {
    throw new Error('后端服务不可用')
  }
  return response.json() as Promise<HealthResponse>
}
