export type HealthResponse = {
  status: string
  service: string
  timestamp: string
}

export type AuthUser = {
  username: string
  realName: string
  role: 'ADMIN' | 'CUSTOMER_MANAGER'
}

export type AdminUser = AuthUser & { id: number; enabled: boolean; createdAt: string; updatedAt: string }
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

export const fetchUsers = () => fetchApi<AdminUser[]>('/api/users')
export const createUser = (payload: AuthUser & { password: string }) => writeApi('/api/users', payload)
export type UsernameSuggestion = { username: string; available: boolean }
export const fetchUsernameSuggestion = (realName: string) => {
  const params = new URLSearchParams({ realName })
  return fetchApi<UsernameSuggestion>(`/api/users/username-suggestion?${params}`)
}
export type AssignmentExceptionFilters = {
  sourceType?: string; statisticsKey?: string; groupCode?: string; groupName?: string;
  excelManagerName?: string; batchId?: number; status?: string; page?: number; pageSize?: number
}
export type AssignmentExceptionItem = {
  id: number; snapshotRecordId: number; sourceType: 'ARREARS' | 'LINE_EXPIRY'; statisticsKey: string; groupCode: string;
  groupName: string; excelManagerName: string; reason: string; batchId: number; originalFilename: string;
  importRowId: number; sheetName: string; sourceRowNumber: number; identityKey: string;
  exceptionType: string; status: 'OPEN' | 'RESOLVED'; resolution: string | null;
  effectiveManagerUserId: number | null; effectiveManagerName: string | null;
  handledByUserId: number | null; handledAt: string | null; createdAt: string
}
export type AssignmentExceptionPage = { items: AssignmentExceptionItem[]; total: number; page: number; pageSize: number }
export type AssignmentExceptionDetail = {
  item: AssignmentExceptionItem; rawFields: Array<{ columnIndex: number; header: string; value: string | null }>;
  normalizedFields: Record<string, unknown>; errors: unknown[]; warnings: unknown[];
  assignmentHistory: Array<{ previousManagerName: string | null; newManagerName: string; changedByUsername: string; changeType: string; reason: string; changedAt: string }>
}
export type ArrearsLedgerFilters = {
  statisticsKey?: string; statisticsDateFrom?: string; statisticsDateTo?: string;
  groupQuery?: string; accountId?: string; carryingProductName?: string;
  effectiveManagerUserId?: number; workOrderStatus?: ArrearsWorkOrderStatus; page?: number; pageSize?: number
}
export type ArrearsWorkOrderStatus = '待处理' | '跟进中' | '已结清'
export const arrearsWorkOrderStatuses: ArrearsWorkOrderStatus[] = ['待处理', '跟进中', '已结清']
export type ArrearsLedgerItem = {
  snapshotRecordId: number; statisticsKey: string; groupCode: string; groupName: string;
  excelManagerName: string | null; effectiveManagerUserId: number | null; effectiveManagerName: string | null;
  accountId: string; carryingProductName: string | null; endingArrearsAmount: number; agingValue: string | null;
  workOrderId: number | null; workOrderStatus: ArrearsWorkOrderStatus;
  lastHandledByUserId: number | null; lastHandledByName: string | null; lastHandledAt: string | null
}
export type ArrearsLedgerPage = {
  items: ArrearsLedgerItem[]; total: number; page: number; pageSize: number;
  statisticsKey: string | null; availableStatisticsKeys: string[]
}
export type ArrearsLedgerDetail = {
  item: ArrearsLedgerItem; batchId: number; originalFilename: string; sheetName: string;
  sourceRowNumber: number; identityKey: string; rawFields: Array<{ columnIndex: number; header: string; cellType?: string; value: string | null; formula?: string | null }>;
  normalizedFields: Record<string, unknown>; assignmentStatus: string;
  handlingTimeline: Array<HandlingTimelineEvent<ArrearsWorkOrderStatus>>
}
export type HandlingTimelineEvent<TStatus extends string> = {
  sourceId: number; type: 'STATUS' | 'FOLLOW_UP'; previousStatus: TStatus | null;
  newStatus: TStatus | null; note: string | null; actorUserId: number;
  actorName: string | null; happenedAt: string
}
export const fetchArrearsLedger = (filters: ArrearsLedgerFilters = {}) => {
  const params = new URLSearchParams()
  Object.entries(filters).forEach(([key, value]) => {
    if (value !== undefined && value !== '') params.set(key, String(value))
  })
  return fetchApi<ArrearsLedgerPage>(`/api/arrears?${params}`)
}
export const fetchArrearsLedgerDetail = (snapshotRecordId: number) => fetchApi<ArrearsLedgerDetail>(`/api/arrears/${snapshotRecordId}`)
export const updateArrearsWorkOrder = (workOrderId: number, status?: ArrearsWorkOrderStatus, followUpNote?: string) =>
  writeApi<{ workOrderId: number; status: ArrearsWorkOrderStatus; lastHandledByUserId: number; lastHandledByName: string; lastHandledAt: string; changed: boolean; statusChanged: boolean; followUpAdded: boolean }>(
    `/api/arrears-work-orders/${workOrderId}/status`, { status, followUpNote }, 'PATCH')
export const exportArrearsWorkOrders = async (filters: ArrearsLedgerFilters = {}) => {
  const params = new URLSearchParams()
  Object.entries(filters).forEach(([key, value]) => {
    if (value !== undefined && value !== '' && key !== 'page' && key !== 'pageSize' && key !== 'statisticsKey') params.set(key, String(value))
  })
  const response = await fetch(`/api/arrears/work-orders/export?${params}`)
  if (response.status === 401) window.dispatchEvent(new Event('session-expired'))
  if (!response.ok) {
    const body = await response.json().catch(() => null) as { message?: string } | null
    throw new Error(body?.message ?? `欠费工单报表导出失败: ${response.status}`)
  }
  return response.blob()
}
export const exportArrearsLedger = async (filters: ArrearsLedgerFilters = {}) => {
  const params = new URLSearchParams()
  Object.entries(filters).forEach(([key, value]) => {
    if (value !== undefined && value !== '' && key !== 'page' && key !== 'pageSize') params.set(key, String(value))
  })
  const response = await fetch(`/api/arrears/export?${params}`)
  if (response.status === 401) window.dispatchEvent(new Event('session-expired'))
  if (!response.ok) {
    const body = await response.json().catch(() => null) as { message?: string } | null
    throw new Error(body?.message ?? `欠费导出失败: ${response.status}`)
  }
  return response.blob()
}
export type LineExpiryLedgerFilters = {
  statisticsKey?: string; statisticsDateFrom?: string; statisticsDateTo?: string;
  groupQuery?: string; productQuery?: string; effectiveManagerUserId?: number;
  remainingDaysMin?: number; remainingDaysMax?: number; renewalStatus?: RenewalStatus; page?: number; pageSize?: number
}
export type LineExpiryLedgerItem = {
  snapshotRecordId: number; statisticsKey: string; groupCode: string; groupName: string;
  productCode: string | null; productName: string | null; memberServiceNumber: string;
  excelManagerName: string | null; effectiveManagerUserId: number | null; effectiveManagerName: string | null;
  billingCutoffDate: string; remainingDays: number; workOrderId: number | null; renewalStatus: RenewalStatus;
  lastHandledByUserId: number | null; lastHandledByName: string | null; lastHandledAt: string | null
}
export type RenewalStatus = '待处理' | '跟进中' | '已续签' | '不续签'
export const renewalStatuses: RenewalStatus[] = ['待处理', '跟进中', '已续签', '不续签']
export type LineExpiryLedgerPage = {
  items: LineExpiryLedgerItem[]; total: number; page: number; pageSize: number;
  statisticsKey: string | null; availableStatisticsKeys: string[];
  availableRenewalStatuses: RenewalStatus[]; calculatedAt: string
}
export type LineExpiryLedgerDetail = {
  item: LineExpiryLedgerItem; batchId: number; originalFilename: string; sheetName: string;
  sourceRowNumber: number; identityKey: string; rawFields: Array<{ columnIndex: number; header: string; cellType?: string; value: string | null; formula?: string | null }>;
  normalizedFields: Record<string, unknown>; assignmentStatus: string; calculatedAt: string;
  availableRenewalStatuses: RenewalStatus[];
  statusHistory: Array<{ previousStatus: RenewalStatus; newStatus: RenewalStatus; changedByUserId: number; changedByName: string | null; changedAt: string }>
  handlingTimeline: Array<HandlingTimelineEvent<RenewalStatus>>
}
export const fetchLineExpiryLedger = (filters: LineExpiryLedgerFilters = {}) => {
  const params = new URLSearchParams()
  Object.entries(filters).forEach(([key, value]) => {
    if (value !== undefined && value !== '') params.set(key, String(value))
  })
  return fetchApi<LineExpiryLedgerPage>(`/api/line-expiry?${params}`)
}
export const fetchLineExpiryLedgerDetail = (snapshotRecordId: number) => fetchApi<LineExpiryLedgerDetail>(`/api/line-expiry/${snapshotRecordId}`)
export const exportLineExpiryLedger = async (filters: LineExpiryLedgerFilters = {}) => {
  const params = new URLSearchParams()
  Object.entries(filters).forEach(([key, value]) => {
    if (value !== undefined && value !== '' && key !== 'page' && key !== 'pageSize') params.set(key, String(value))
  })
  const response = await fetch(`/api/line-expiry/export?${params}`)
  if (response.status === 401) window.dispatchEvent(new Event('session-expired'))
  if (!response.ok) {
    const body = await response.json().catch(() => null) as { message?: string } | null
    throw new Error(body?.message ?? `专线导出失败: ${response.status}`)
  }
  return response.blob()
}
export const updateLineRenewalStatus = (workOrderId: number, status?: RenewalStatus, followUpNote?: string) =>
  writeApi<{ workOrderId: number; status: RenewalStatus; lastHandledByUserId: number; lastHandledByName: string; lastHandledAt: string; changed: boolean; statusChanged: boolean; followUpAdded: boolean }>(
    `/api/line-renewal-work-orders/${workOrderId}/status`, { status, followUpNote }, 'PATCH')
export const exportLineRenewalWorkOrders = async (filters: LineExpiryLedgerFilters = {}) => {
  const params = new URLSearchParams()
  Object.entries(filters).forEach(([key, value]) => {
    if (value !== undefined && value !== '' && key !== 'page' && key !== 'pageSize') params.set(key, String(value))
  })
  const response = await fetch(`/api/line-expiry/renewal-work-orders/export?${params}`)
  if (response.status === 401) window.dispatchEvent(new Event('session-expired'))
  if (!response.ok) {
    const body = await response.json().catch(() => null) as { message?: string } | null
    throw new Error(body?.message ?? `续签工单报表导出失败: ${response.status}`)
  }
  return response.blob()
}

export type LedgerSourceType = 'ARREARS' | 'LINE_EXPIRY'
export type LedgerValidationIssue = {
  code: string; severity: 'BLOCKING' | 'WARNING'; message: string;
  sheetName: string | null; rowNumber: number | null; columnIndex: number | null
}
export type LedgerRawField = { columnIndex: number; header: string; cellType: string; value: string | null; formula: string | null }
export type LedgerParsedRow = {
  sheetName: string; rowNumber: number; statisticsKey: string | null; identityKey: string | null;
  excelManagerName: string | null; normalizedFields: Record<string, unknown>; rawFields: LedgerRawField[];
  issues: LedgerValidationIssue[]
}
export type LedgerUploadPreview = {
  batchId: number; sourceType: LedgerSourceType; statisticsKey: string | null;
  originalFilename: string; fileSha256: string; byteSize: number; status: string;
  totalRows: number; validRows: number; warningRows: number; errorRows: number; duplicate: boolean;
  revision: { previousBatchId: number | null; addedRows: number; removedRows: number; changedRows: number; unchangedRows: number;
    retainedRows: number; differences: Array<{ identityKey: string; changeType: string; previousImportRowId: number | null;
      candidateImportRowId: number | null; previousFields: Record<string, unknown> | null;
      candidateFields: Record<string, unknown> | null; changedFields: Array<{ header: string; occurrence: number;
        before: string | null; after: string | null; cleared: boolean }> }> };
  issues: LedgerValidationIssue[]; rows: LedgerParsedRow[]
}
export type LedgerImportBatch = {
  batchId: number; sourceType: LedgerSourceType; statisticsKey: string | null; originalFilename: string;
  sha256: string; originalFileSize: number; uploaderUsername: string; uploadedAt: string; confirmedAt: string | null;
  status: string; contributingRows: number; contributionStatus: 'ALL' | 'PARTIAL' | 'NONE';
  totalRows: number; validRows: number; warningRows: number; errorRows: number;
  addedRows: number; removedRows: number; changedRows: number; unchangedRows: number; retainedRows: number;
  erasedRows: number; originalFileAvailable: boolean; originalFilePurgeStatus: 'AVAILABLE' | 'PENDING' | 'PURGED'
}
export type LedgerImportBatchPage = { items: LedgerImportBatch[]; page: number; pageSize: number; total: number }
export type LedgerImportBatchDetail = {
  batch: LedgerImportBatch; validationSummary: { issues?: LedgerValidationIssue[]; [key: string]: unknown };
  sheets: Array<{ sheetName: string; rowCount: number; firstRowNumber: number | null; lastRowNumber: number | null }>;
  rows: Array<{ importRowId: number; sheetName: string; sourceRowNumber: number; identityKey: string | null;
    validationStatus: string; rawFields: LedgerRawField[]; normalizedFields: Record<string, unknown>;
    errors: unknown[]; warnings: unknown[]; assignmentExceptionType: string | null;
    assignmentExceptionReason: string | null; assignmentExceptionStatus: string | null }>;
  rowPage: { page: number; pageSize: number; total: number };
  revisionDifferences: Array<{ identityKey: string; changeType: string; previousImportRowId: number | null; candidateImportRowId: number | null }>;
  revisionDifferenceTotal: number
}

export async function precheckLedgerWorkbook(sourceType: LedgerSourceType, file: File): Promise<LedgerUploadPreview> {
  const token = await fetchCsrfToken()
  const data = new FormData()
  data.append('file', file)
  const response = await fetch(`/api/ledger-imports/precheck?sourceType=${sourceType}`, {
    method: 'POST', headers: { 'X-XSRF-TOKEN': token }, body: data,
  })
  const body = await response.json().catch(() => null) as { data?: LedgerUploadPreview; message?: string } | null
  if (response.status === 401) window.dispatchEvent(new Event('session-expired'))
  if (!response.ok || !body?.data) throw new Error(body?.message ?? `Excel 预检失败: ${response.status}`)
  return body.data
}

export const confirmLedgerWorkbook = (batchId: number) => writeApi<{
  batchId: number; sourceType: LedgerSourceType; statisticsKey: string; confirmedRows: number;
  warningRows: number; assignmentExceptionRows: number; currentVersion: boolean
}>(`/api/ledger-imports/${batchId}/confirm`)

export const cancelLedgerImportBatch = (batchId: number) => writeApi<{
  batchId: number; status: 'CANCELLED'; sourceType: LedgerSourceType; statisticsKey: string | null
}>(`/api/ledger-imports/${batchId}/cancel`)

export function fetchLedgerImportBatches(filters: { sourceType?: LedgerSourceType; statisticsKey?: string; status?: string; page?: number; pageSize?: number } = {}) {
  const params = new URLSearchParams()
  Object.entries(filters).forEach(([key, value]) => { if (value !== undefined && value !== '') params.set(key, String(value)) })
  return fetchApi<LedgerImportBatchPage>(`/api/ledger-imports/batches?${params}`)
}

export function fetchLedgerImportBatch(batchId: number, rowPage = 1, pageSize = 20) {
  const params = new URLSearchParams({ rowPage: String(rowPage), pageSize: String(pageSize) })
  return fetchApi<LedgerImportBatchDetail>(`/api/ledger-imports/batches/${batchId}?${params}`)
}

export type LedgerErasurePreview = {
  previewToken: string; expiresAt: string; sourceType: LedgerSourceType;
  selectedRecords: Array<{ snapshotRecordId: number; statisticsKey: string; stableIdentity: string; groupCode: string; groupName: string }>;
  periods: Array<{ statisticsKey: string; selectedRecords: number; historicalImportRows: number; snapshots: number;
    affectedBatches: number; versions: number; revisionDiffs: number; assignmentExceptions: number; assignmentHistory: number;
    workOrders: number; workOrderStatusChanges: number; workOrderFollowUps: number }>;
  originalFiles: Array<{ filenames: string[]; referenceBatches: number; affectedPeriods: number; parsedBusinessKeys: number; otherBusinessKeys: number }>;
  maxSelectedRecords: number
}
export type LedgerErasureResult = {
  operationId: string; sourceType: LedgerSourceType; deletedRecords: number; deletedImportRows: number;
  affectedBatches: number; filesQueued: number; originalFileStatus: 'RETAINED' | 'PENDING' | 'PURGED';
  lockedVersions: Record<string, number>
}
export const previewLedgerErasure = (sourceType: LedgerSourceType, snapshotRecordIds: number[]) =>
  writeApi<LedgerErasurePreview>('/api/ledger-erasure/preview', { sourceType, snapshotRecordIds })
export const confirmLedgerErasure = (previewToken: string, deleteOriginalWorkbooks: boolean, confirmationText: string) =>
  writeApi<LedgerErasureResult>('/api/ledger-erasure/confirm', {
    previewToken, deleteOriginalWorkbooks, confirmationText,
  })

export async function downloadLedgerOriginalWorkbook(batchId: number) {
  const response = await fetch(`/api/ledger-imports/${batchId}/original`)
  if (response.status === 401) window.dispatchEvent(new Event('session-expired'))
  if (!response.ok) {
    const body = await response.json().catch(() => null) as { message?: string } | null
    throw new Error(body?.message ?? `原始工作簿下载失败: ${response.status}`)
  }
  return { blob: await response.blob(), sha256: response.headers.get('X-Content-SHA256') }
}

export const fetchAssignmentExceptions = (filters: AssignmentExceptionFilters = {}) => {
  const params = new URLSearchParams()
  Object.entries(filters).forEach(([key, value]) => { if (value !== undefined && value !== '') params.set(key, String(value)) })
  return fetchApi<AssignmentExceptionPage>(`/api/assignment-exceptions?${params}`)
}
export const fetchAssignmentException = (id: number) => fetchApi<AssignmentExceptionDetail>(`/api/assignment-exceptions/${id}`)
export const assignExceptions = (payload: {
  sourceType: string; snapshotRecordIds?: number[]; allFiltered?: boolean; statisticsKey?: string;
  groupCode?: string; groupName?: string; excelManagerName?: string; batchId?: number; status?: string;
  targetManagerUserId: number; reason?: string
}) => writeApi<{ sourceType: string; selectedCount: number; targetManagerUserId: number }>('/api/assignment-exceptions/assign', payload)
export const setUserStatus = (id: number, enabled: boolean) => writeApi(`/api/users/${id}`, { enabled }, 'PATCH')
export const fetchAuditLogs = (page = 1, pageSize = 15, filters: AuditFilters = {}) => {
  const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) })
  Object.entries(filters).forEach(([key, value]) => { if (value !== undefined && value !== '') params.set(key, String(value)) })
  return fetchApi<{ items: AuditLog[]; total: number; page: number; pageSize: number }>(`/api/audit-logs?${params}`)
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
