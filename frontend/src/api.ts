export type HealthResponse = {
  status: string
  service: string
  timestamp: string
}

export type Customer = {
  id: number
  customerCode: string | null
  name: string
  manager: string
  type: string
  balance: number
  status: string
  due: string
}
export type CustomerPage = { items: Customer[]; total: number; page: number; pageSize: number }
export type CustomerFilters = { q?: string; status?: string; manager?: string; businessType?: string; riskLevel?: string; expiryFrom?: string; expiryTo?: string; exceptionStatus?: string; customerId?: number; page?: number; pageSize?: number; sort?: string; direction?: string }
export type CustomerDetail = { customer: Record<string, unknown>; services: Array<Record<string, unknown>>; bills: Array<Record<string, unknown>>; expiries: Array<Record<string, unknown>>; risks: Array<Record<string, unknown>>; tasks: Array<Record<string, unknown>>; followUps: Array<Record<string, unknown>>; batches: Array<Record<string, unknown>> }
export const fetchCustomerPage = (filters: CustomerFilters = {}) => {
  const params = new URLSearchParams()
  Object.entries(filters).forEach(([key,value]) => { if (value !== undefined && value !== '') params.set(key,String(value)) })
  return fetchApi<CustomerPage>(`/api/customers/page?${params}`)
}
export const fetchCustomerDetail = (id: number) => fetchApi<CustomerDetail>(`/api/customers/${id}`)
export type ReadOnlyPrecheck = { fileName:string;fileSha256:string;totalRows:number;validRows:number;excludedRows:number;invalidRows:number;workbookErrors:string[];rows:ImportPreview['rows'] }
export async function precheckReadOnly(file: File): Promise<ReadOnlyPrecheck> {
  const data=new FormData();data.append('file',file)
  const response=await fetch('/api/imports/precheck-readonly',{method:'POST',body:data})
  const body=await response.json() as {data:ReadOnlyPrecheck;message?:string}
  if(!response.ok) throw new Error(body.message??`只读预检失败: ${response.status}`)
  return body.data
}

export type Task = {
  id: number
  title: string
  owner: string
  deadline: string
  status: string
  priority: string
  customerCode: string | null
  customerName: string | null
  overdue: boolean
  overdueDays: number
}

export type WorkbenchSummary = {
  highRiskCustomers: number
  pendingTasks: number
  overdueTasks: number
  expiringServices: number
  openExceptions: number
  calculatedAt: string
}
export type WorkbenchFilters = { riskLevel?: string; owner?: string; businessType?: string; from?: string; to?: string; exceptionStatus?: string }
export type WorkbenchItem = { kind: string; id: number; customerId?: number; customerCode?: string; customerName?: string; title: string; riskLevel?: string; status: string; eventDate: string; owner?: string; businessType?: string; batchId?: number; sourceRowNumber?: number; sheetName?: string; sourceCustomerCode?: string; sourceRowId?: number; overdueDays: number }
export function fetchWorkbench(filters: WorkbenchFilters) {
  const params = new URLSearchParams()
  Object.entries(filters).forEach(([key, value]) => { if (value) params.set(key, value) })
  return Promise.all([fetchApi<WorkbenchSummary>(`/api/workbench/summary?${params}`), fetchApi<WorkbenchItem[]>(`/api/workbench/items?${params}`)])
}

export type ImportBatch = {
  id: number
  fileName: string
  sourceType: string
  status: string
  totalRows: number
  validRows: number
  invalidRows: number
  excludedRows?: number
  insertedRows: number
  updatedRows: number
  duplicateRows: number
  failedRows: number
  createdAt: string
}

export type Service = { id: number; serviceCode: string; customerCode: string; customerName: string; serviceType: string; bandwidth: string | null; expiryDate: string | null; renewalStatus: string }
export type ServicePage = { items: Service[]; total: number; page: number; pageSize: number }
export type ServiceDetail = { service: Record<string,unknown>; bills:Array<Record<string,unknown>>; expiries:Array<Record<string,unknown>>; risks:Array<Record<string,unknown>>; tasks:Array<Record<string,unknown>> }
export const fetchServiceDetail = (id:number) => fetchApi<ServiceDetail>(`/api/ledger/services/${id}`)
export function fetchServicePage(filters: {from?:string;to?:string;q?:string;page?:number;pageSize?:number}) {
  const params=new URLSearchParams();Object.entries(filters).forEach(([key,value])=>{if(value!==undefined&&value!=='')params.set(key,String(value))})
  return fetchApi<ServicePage>(`/api/ledger/services/page?${params}`)
}
export type Bill = { id: number; billCode: string; customerCode: string; customerName: string; billingPeriod: string; dueAmount: number; paidAmount: number; arrearsAmount: number; status: string | null; dueDate: string | null }
export type Expiry = { id: number; serviceCode: string | null; customerCode: string; customerName: string; expiryDate: string; renewalStatus: string }
export type RiskRule = { id: number; code: string; name: string; type: string; threshold: number | null; operator: string; version: number; enabled: boolean }
export type ExceptionRecord = { id: number; sourceRowId?: number | null; batchId?: number | null; sheetName?: string | null; sourceRowNumber?: number | null; customerCode?: string | null; type: string; businessKey: string | null; reason: string; status: string; resolution: string | null; createdAt: string }
export type ImportRow = { id: number; batchId: number; sheetName: string; sourceRowNumber: number; businessKey: string | null; customerCode: string | null; status: string; category: string; errorMessage: string | null; errorCode: string | null; values: Record<string,string>; createdAt: string }

export type AuthUser = {
  username: string
  realName: string
  role: 'ADMIN' | 'CUSTOMER_MANAGER'
}

export type TaskFilters = { status?: string; owner?: string; overdue?: boolean; priority?: string }
export type FollowUp = { id: number; note: string; nextAction: string | null; createdBy: string; createdAt: string }
export type TaskDetail = Task & { followUps: FollowUp[]; sourceType?: string; sourceId?: number; taskType?: string; closeReason?: string }
export type AdminUser = AuthUser & { id: number; enabled: boolean; createdAt: string; updatedAt: string }
export type RiskResult = { id: number; customerId: number; customerCode: string; customerName: string; ruleCode: string; ruleName: string; ruleVersion: number; observedValue: number; threshold: number; riskLevel: string; status: string; calculatedAt: string; sourceBatchId: number | null; sourceType: string; sourceId: number; runId: number }
export type RiskRun = { id: number; status: string; scopeType: string; scopeValue: string | null; ruleVersions: string; startedAt: string; finishedAt: string | null; successCount: number; failureCount: number; totalCount: number; errorMessage: string | null }
export type AuditFilters = { operatorUserId?: number; operationType?: string; targetType?: string; targetId?: number; result?: string; from?: string; to?: string }
export type AuditLog = { id: number; operatorUsername?: string; operatorName?: string; operationType: string; targetType: string; targetId: number | null; result: string; detail: string | null; createdAt: string }

async function writeApi<T = void>(path: string, payload?: unknown, method = 'POST'): Promise<T> {
  const token = await fetchCsrfToken()
  const response = await fetch(path, {
    method,
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token },
    body: payload === undefined ? undefined : JSON.stringify(payload),
  })
  const body = await response.json().catch(() => null) as { data: T; message?: string } | null
  if (response.status === 401) window.dispatchEvent(new Event('session-expired'))
  if (!response.ok) throw new Error(body?.message ?? `操作失败: ${response.status}`)
  return body?.data as T
}

export const fetchTaskDetail = (id: number) => fetchApi<TaskDetail>(`/api/tasks/${id}`)
export const fetchRiskTask = (id: number) => fetchApi<{ taskId?: number; message?: string }>(`/api/risk-recalculation/${id}/task`)
export type TaskActionResult = { id: number; status: string }
export const addFollowUp = (id: number, payload: { note: string; nextAction: string }) => writeApi<{ id: number; status: '跟进已保存' }>(`/api/tasks/${id}/follow-ups`, payload)
export const startTask = (id: number) => writeApi<TaskActionResult>(`/api/tasks/${id}/start`)
export const closeTask = (id: number, reason: string) => writeApi<TaskActionResult>(`/api/tasks/${id}/close`, { reason })
export const fetchUsers = () => fetchApi<AdminUser[]>('/api/users')
export const createUser = (payload: AuthUser & { password: string }) => writeApi('/api/users', payload)
export const setUserStatus = (id: number, enabled: boolean) => writeApi(`/api/users/${id}`, { enabled }, 'PATCH')
export const fetchUserScope = (id: number) => fetchApi<{ customerIds: number[] }>(`/api/users/${id}/scope`)
export const saveUserScope = (id: number, customerIds: number[]) => writeApi(`/api/users/${id}/scope`, { customerIds }, 'PUT')
export const fetchAuditLogs = (page = 1, pageSize = 15, filters: AuditFilters = {}) => {
  const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) })
  Object.entries(filters).forEach(([key, value]) => { if (value !== undefined && value !== '') params.set(key, String(value)) })
  return fetchApi<{ items: AuditLog[]; total: number; page: number; pageSize: number }>(`/api/audit-logs?${params}`)
}
export const updateRiskRule = (code: string, payload: { threshold: number; operator: string; enabled: boolean }) => writeApi('/api/risk-rules', { code, ...payload })
export const fetchRiskRuns = () => fetchApi<RiskRun[]>('/api/risk-recalculation/runs')
export async function fetchRiskResults(customerId?: number): Promise<RiskResult[]> {
  const rows = await fetchApi<RiskResult[]>('/api/risk-recalculation/results')
  return rows.filter(row => customerId === undefined || row.customerId === customerId)
}
export const recalculateRisk = (customerIds?: number[]) => writeApi<{ runId: number; status: string; resultCount?: number; message?: string }>('/api/risk-recalculation', customerIds ? { customerIds } : undefined)

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

export async function fetchApi<T>(path: string): Promise<T> {
  const response = await fetch(path)
  if (response.status === 401) window.dispatchEvent(new Event('session-expired'))
  if (!response.ok) {
    const body = await response.json().catch(() => null) as { message?: string } | null
    throw new Error(body?.message ?? `接口请求失败: ${response.status}`)
  }
  const body = await response.json() as { data: T }
  return body.data
}

export function fetchCustomers(): Promise<Customer[]> {
  return fetchApi<Customer[]>('/api/customers')
}

export function fetchTasks(filters: TaskFilters = {}): Promise<Task[]> {
  const params = new URLSearchParams()
  Object.entries(filters).forEach(([key, value]) => { if (value !== undefined && value !== '') params.set(key, String(value)) })
  return fetchApi<Task[]>(`/api/tasks?${params}`)
}

export function fetchWorkbenchSummary(): Promise<WorkbenchSummary> {
  return fetchApi<WorkbenchSummary>('/api/workbench/summary')
}

export function fetchServices(): Promise<Service[]> { return fetchApi<Service[]>('/api/ledger/services') }
export function fetchBills(): Promise<Bill[]> { return fetchApi<Bill[]>('/api/ledger/bills') }
export function fetchExpiries(): Promise<Expiry[]> { return fetchApi<Expiry[]>('/api/ledger/expiries') }
export function fetchRiskRules(): Promise<RiskRule[]> { return fetchApi<RiskRule[]>('/api/risk-rules') }
export type ExceptionFilters = { status?: string | number; batchId?: string | number; sheetName?: string; sourceRowNumber?: string | number; exceptionId?: string | number; q?: string }
export function fetchExceptions(filters: ExceptionFilters = {}): Promise<ExceptionRecord[]> {
  const params = new URLSearchParams()
  Object.entries(filters).forEach(([key,value]) => { if (value !== undefined && value !== '') params.set(key,String(value)) })
  return fetchApi<ExceptionRecord[]>(`/api/imports/exceptions?${params}`)
}
export function fetchBatchExceptions(batchId: number): Promise<ExceptionRecord[]> { return fetchExceptions({ batchId }) }
export function fetchImportRows(batchId: number, filters: {sheetName?:string;sourceRowNumber?:number;businessKey?:string} = {}): Promise<ImportRow[]> {
  const params=new URLSearchParams();Object.entries(filters).forEach(([key,value])=>{if(value!==undefined&&value!=='')params.set(key,String(value))})
  return fetchApi<ImportRow[]>(`/api/imports/${batchId}/rows?${params}`)
}

export function fetchImportBatches(): Promise<ImportBatch[]> {
  return fetchApi<ImportBatch[]>('/api/imports')
}

export function completeTask(id: number): Promise<TaskActionResult> {
  return writeApi<TaskActionResult>(`/api/tasks/${id}/complete`)
}

export async function createTask(request: { title: string; deadline: string; priority?: string; customerId: number; serviceId?: number; sourceType?: string; sourceId?: number; taskType?: string }): Promise<{ id: number; reused: boolean; message: string }> {
  const token = await fetchCsrfToken()
  const response = await fetch('/api/tasks', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token },
    body: JSON.stringify(request),
  })
  if (!response.ok) {
    const body = await response.json().catch(() => null) as { message?: string } | null
    throw new Error(body?.message ?? `任务创建失败: ${response.status}`)
  }
  return (await response.json()).data
}

export type ImportPreview = {
  batchId: number
  fileName: string
  fileSha256: string
  totalRows: number
  validRows: number
  excludedRows: number
  invalidRows: number
  workbookErrors: string[]
  rows: Array<{ rowNumber: number; sheetName: string; businessKey: string; customerCode: string; customerName: string; classification: string; reason?: string }>
}
export async function previewImport(file: File): Promise<ImportPreview> {
  const token = await fetchCsrfToken()
  const data = new FormData()
  data.append('file', file)
  const response = await fetch('/api/imports/preview', {
    method: 'POST',
    headers: { 'X-XSRF-TOKEN': token },
    body: data,
  })
  const body = await response.json() as { data: ImportPreview; message?: string }
  if (!response.ok) throw new Error(body.message ?? `导入预检失败: ${response.status}`)
  return body.data
}

export const cancelImport = (batchId: number) => writeApi(`/api/imports/${batchId}/cancel`)
export const fetchSavedPreview = (batchId: number) => fetchApi<ImportPreview>(`/api/imports/${batchId}/preview`)

export async function confirmImport(batchId: number): Promise<{ status: string; message: string; insertedRows: number; updatedRows: number; duplicateRows: number }> {
  const token = await fetchCsrfToken()
  const response = await fetch('/api/imports/confirm', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token },
    body: JSON.stringify({ batchId }),
  })
  if (!response.ok) {
    const body = await response.json().catch(() => null) as { message?: string } | null
    throw new Error(body?.message ?? `正式导入失败: ${response.status}`)
  }
  return (await response.json()).data
}
