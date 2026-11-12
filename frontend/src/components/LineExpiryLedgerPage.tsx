import { useEffect, useMemo, useState } from 'react'
import type { Key } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Alert, Button, Card, DatePicker, Descriptions, Drawer, Empty, Input, InputNumber, Select, Space, Table, Tag, Typography, message } from 'antd'
import type { TableProps } from 'antd'
import { CalendarOutlined, DownloadOutlined, FilterOutlined, ReloadOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { AdminUser, exportLineExpiryLedger, exportLineRenewalWorkOrders, fetchLineExpiryLedger, fetchLineExpiryLedgerDetail, fetchUsers, LineExpiryLedgerFilters, LineExpiryLedgerItem, renewalStatuses, RenewalStatus, updateLineRenewalStatus } from '../api'
import { QueryFeedback } from './QueryFeedback'
import { LedgerErasureDialog } from './LedgerErasureDialog'
import { currentBusinessMonthDateRange } from '../utils/businessCalendar'

const asText = (value: unknown) => value == null || value === '' ? '—' : String(value)
const dateLabel = (value: string) => value.length === 8 ? `${value.slice(0, 4)}-${value.slice(4, 6)}-${value.slice(6)}` : value
const calendarDate = (value?: string) => value ? dayjs(`${value.slice(0, 4)}-${value.slice(4, 6)}-${value.slice(6, 8)}`) : null
const defaultFilters = (): LineExpiryLedgerFilters => ({ page: 1, pageSize: 20, ...currentBusinessMonthDateRange() })

export function LineExpiryLedgerPage({ isAdmin }: { isAdmin: boolean }) {
  const [filters, setFilters] = useState<LineExpiryLedgerFilters>(defaultFilters)
  const [detailId, setDetailId] = useState<number>()
  const [exporting, setExporting] = useState(false)
  const [renewalExporting, setRenewalExporting] = useState(false)
  const [selectedSnapshotIds, setSelectedSnapshotIds] = useState<Key[]>([])
  const [messageApi, messageContext] = message.useMessage()
  const query = useQuery({ queryKey: ['line-expiry-ledger', filters], queryFn: () => fetchLineExpiryLedger(filters), retry: false })
  const detail = useQuery({ queryKey: ['line-expiry-ledger-detail', detailId], queryFn: () => fetchLineExpiryLedgerDetail(detailId!), enabled: detailId !== undefined, retry: false })
  const users = useQuery({ queryKey: ['users'], queryFn: fetchUsers, retry: false, enabled: isAdmin })
  const managers = useMemo(() => (users.data ?? []).filter(user => user.role === 'CUSTOMER_MANAGER' && user.enabled), [users.data])
  const rows = query.data?.items ?? []
  const update = (patch: Partial<LineExpiryLedgerFilters>) => setFilters(current => ({ ...current, ...patch, page: 1 }))
  const clear = () => setFilters(defaultFilters())
  const exportFiltered = async () => {
    setExporting(true)
    try {
      const blob = await exportLineExpiryLedger(filters)
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      const dateSuffix = filters.statisticsDateFrom || filters.statisticsDateTo
        ? `${filters.statisticsDateFrom ?? 'start'}-${filters.statisticsDateTo ?? 'end'}`
        : query.data?.statisticsKey ?? 'latest'
      anchor.download = `专线到期明细-${dateSuffix}.xlsx`
      anchor.click()
      URL.revokeObjectURL(url)
    } catch (error) {
      messageApi.error(error instanceof Error ? error.message : '专线导出失败')
    } finally {
      setExporting(false)
    }
  }
  const exportRenewalReport = async () => {
    setRenewalExporting(true)
    try {
      const blob = await exportLineRenewalWorkOrders(filters)
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = '专线续签工单报表.xlsx'
      anchor.click()
      URL.revokeObjectURL(url)
    } catch (error) {
      messageApi.error(error instanceof Error ? error.message : '续签工单报表导出失败')
    } finally {
      setRenewalExporting(false)
    }
  }
  const columns: TableProps<LineExpiryLedgerItem>['columns'] = [
    { title: '统计日期', dataIndex: 'statisticsKey', width: 110, render: (value: string) => dateLabel(value) },
    { title: '集团编码', dataIndex: 'groupCode', width: 130 },
    { title: '集团名称', dataIndex: 'groupName', width: 180, render: (value: string) => <Typography.Text strong>{value}</Typography.Text> },
    { title: '产品编码', dataIndex: 'productCode', width: 130, render: asText },
    { title: '产品名称', dataIndex: 'productName', width: 170, render: (value: string | null) => value ? <Typography.Text strong>{value}</Typography.Text> : '—' },
    { title: '成员服务号码', dataIndex: 'memberServiceNumber', width: 155 },
    { title: 'Excel 原始客户经理', dataIndex: 'excelManagerName', width: 145, render: asText },
    { title: '当前有效客户经理', dataIndex: 'effectiveManagerName', width: 165, render: (value: string | null) => value
      ? <span className="manager-cell"><span className="manager-dot" />{value}</span>
      : <Tag className="unassigned-tag" color="warning">未分配（归属匹配异常）</Tag> },
    { title: '计费截止日期', dataIndex: 'billingCutoffDate', width: 125 },
    { title: '剩余天数', dataIndex: 'remainingDays', width: 105, render: (value: number) => <Tag className="days-chip" color={value < 0 ? 'error' : 'cyan'}>{value < 0 ? `逾期 ${Math.abs(value)} 天` : `${value} 天`}</Tag> },
    { title: '续签处理状态', dataIndex: 'renewalStatus', width: 120, render: (value: RenewalStatus) => <Tag color={value === '已续签' ? 'success' : value === '不续签' ? 'error' : value === '跟进中' ? 'processing' : 'default'}>{value}</Tag> },
    { title: '操作', fixed: 'right' as const, width: 80, render: (_: unknown, row: LineExpiryLedgerItem) => <Button type="link" onClick={() => setDetailId(row.snapshotRecordId)}>详情</Button> },
  ]

  return <>
    {messageContext}
    <div className="ledger-page-intro">
      <div className="ledger-page-icon expiry-page-icon"><CalendarOutlined /></div>
      <div><Typography.Text className="ledger-page-kicker">专线产品 · 到期快照</Typography.Text><Typography.Title level={4}>专线到期明细</Typography.Title>
        <Typography.Text type="secondary">跟进成员服务、计费截止日期和当前客户归属。</Typography.Text></div>
    </div>
    <Card className="ledger-card" title="专线记录" extra={<Button icon={<ReloadOutlined />} loading={query.isFetching} onClick={() => query.refetch()}>刷新</Button>}>
      <section className="ledger-filter-panel" aria-label="专线筛选条件">
        <div className="ledger-filter-heading"><FilterOutlined /><strong>筛选记录</strong><span>按集团、产品、归属或剩余天数查找</span></div>
        <Space wrap className="ledger-filter-controls">
        <DatePicker.RangePicker aria-label="专线统计日期范围" format="YYYY-MM-DD" allowClear
          placeholder={['开始日期', '结束日期']} style={{ width: 290 }}
          value={[calendarDate(filters.statisticsDateFrom), calendarDate(filters.statisticsDateTo)]}
          onChange={dates => update({ statisticsDateFrom: dates?.[0]?.format('YYYYMMDD'), statisticsDateTo: dates?.[1]?.format('YYYYMMDD') })} />
        <Input aria-label="集团筛选" placeholder="集团编码或名称" allowClear style={{ width: 190 }} value={filters.groupQuery ?? ''} onChange={event => update({ groupQuery: event.target.value || undefined })} />
        <Input aria-label="产品筛选" placeholder="产品编码或名称" allowClear style={{ width: 180 }} value={filters.productQuery ?? ''} onChange={event => update({ productQuery: event.target.value || undefined })} />
        {isAdmin && <Select aria-label="当前有效客户经理筛选" allowClear placeholder="全部客户经理" style={{ width: 180 }} value={filters.effectiveManagerUserId} onChange={value => update({ effectiveManagerUserId: value })} options={managers.map((manager: AdminUser) => ({ value: manager.id, label: `${manager.realName}（${manager.username}）` }))} />}
        <Select aria-label="续签处理状态筛选" allowClear placeholder="全部处理状态" style={{ width: 150 }} value={filters.renewalStatus} onChange={value => update({ renewalStatus: value })} options={(query.data?.availableRenewalStatuses ?? renewalStatuses).map(status => ({ value: status, label: status }))} />
        <InputNumber aria-label="剩余天数最小值" placeholder="最少天数" value={filters.remainingDaysMin} onChange={value => update({ remainingDaysMin: value ?? undefined })} style={{ width: 115 }} />
        <InputNumber aria-label="剩余天数最大值" placeholder="最多天数" value={filters.remainingDaysMax} onChange={value => update({ remainingDaysMax: value ?? undefined })} style={{ width: 115 }} />
        <Button onClick={clear}>重置</Button>
        <Button className="export-button" icon={<DownloadOutlined />} loading={exporting} onClick={exportFiltered}>导出筛选结果</Button>
        {isAdmin && <Button className="export-button" icon={<DownloadOutlined />} loading={renewalExporting} onClick={exportRenewalReport}>导出续签工单</Button>}
        </Space>
      </section>
      <div className="ledger-result-bar">
        <div className="ledger-result-copy"><span className="ledger-result-label">统计日期</span>
          <Tag color="cyan">{filters.statisticsDateFrom || filters.statisticsDateTo
            ? `${filters.statisticsDateFrom ? dateLabel(filters.statisticsDateFrom) : '不限'} 至 ${filters.statisticsDateTo ? dateLabel(filters.statisticsDateTo) : '不限'}`
            : query.data?.statisticsKey ? dateLabel(query.data.statisticsKey) : '暂无数据'}</Tag>
          <span className="ledger-result-count">共 <strong>{query.data?.total ?? 0}</strong> 条记录</span>
        </div>
        <Typography.Text type="secondary">剩余天数按系统日期 {query.data?.calculatedAt ?? '—'} 动态计算</Typography.Text>
      </div>
      <QueryFeedback error={query.error} retry={() => query.refetch()} />
      {isAdmin && <div style={{ marginBottom: 12 }}><LedgerErasureDialog sourceType="LINE_EXPIRY"
        selectedSnapshotIds={selectedSnapshotIds.map(Number)} onClear={() => setSelectedSnapshotIds([])}
        onCompleted={() => void query.refetch()} /></div>}
      <Table<LineExpiryLedgerItem> rowKey="snapshotRecordId" rowSelection={isAdmin ? { selectedRowKeys: selectedSnapshotIds,
        preserveSelectedRowKeys: true, onChange: keys => setSelectedSnapshotIds(keys) } : undefined}
        columns={columns} dataSource={rows} loading={query.isFetching} scroll={{ x: 1650 }} locale={{ emptyText: <Empty description="没有符合条件的专线记录" /> }} pagination={{ current: query.data?.page ?? filters.page, pageSize: query.data?.pageSize ?? filters.pageSize, total: query.data?.total ?? 0, showSizeChanger: true, pageSizeOptions: [20, 50, 100], onChange: (page, pageSize) => setFilters(current => ({ ...current, page, pageSize })) }} />
    </Card>
    <Drawer title={<span className="drawer-heading"><CalendarOutlined />专线到期明细详情</span>} width={860} open={detailId !== undefined} onClose={() => setDetailId(undefined)}>
      {detail.error instanceof Error && <Alert type="error" showIcon message={detail.error.message} />}
      {detail.data && <LineDetailDrawer detail={detail.data} canUpdate={isAdmin || Boolean(detail.data.item.effectiveManagerUserId)} onStatusUpdated={() => { void query.refetch(); void detail.refetch() }} />}
      {detail.isFetching && !detail.data && <Typography.Text type="secondary">正在加载…</Typography.Text>}
    </Drawer>
  </>
}

function LineDetailDrawer({ detail, canUpdate, onStatusUpdated }: { detail: Awaited<ReturnType<typeof fetchLineExpiryLedgerDetail>>; canUpdate: boolean; onStatusUpdated: () => void }) {
  const item = detail.item
  const [savingStatus, setSavingStatus] = useState(false)
  const [status, setStatus] = useState<RenewalStatus>(item.renewalStatus)
  const [followUpNote, setFollowUpNote] = useState('')
  const [messageApi, messageContext] = message.useMessage()
  const displayValue = (value: unknown) => value == null || value === '' ? '—' : String(value)
  useEffect(() => { setStatus(item.renewalStatus); setFollowUpNote('') }, [item.workOrderId, item.renewalStatus])
  const saveStatus = async () => {
    if (!item.workOrderId || (status === item.renewalStatus && !followUpNote.trim())) return
    setSavingStatus(true)
    try {
      await updateLineRenewalStatus(item.workOrderId, status, followUpNote)
      messageApi.success(status === item.renewalStatus ? '跟进说明已追加' : '续签处理状态已更新')
      setFollowUpNote('')
      onStatusUpdated()
    } catch (error) {
      messageApi.error(error instanceof Error ? error.message : '状态更新失败')
    } finally { setSavingStatus(false) }
  }
  return <>
    {messageContext}
    <div className="detail-context-bar"><span className="detail-context-icon expiry-detail-icon"><CalendarOutlined /></span><div><strong>{item.productName || '专线产品'}</strong><span>集团 {item.groupName} · 服务号码 {item.memberServiceNumber}</span></div><Tag color={item.effectiveManagerName ? 'success' : 'warning'}>{item.effectiveManagerName ? '归属已确认' : '待处理归属'}</Tag></div>
    <Descriptions bordered size="small" column={2} items={[
      { key: 'date', label: '统计日期', children: dateLabel(item.statisticsKey) },
      { key: 'group', label: '集团', children: `${item.groupCode} · ${item.groupName}` },
      { key: 'product-code', label: '产品编码', children: displayValue(item.productCode) },
      { key: 'product-name', label: '产品名称', children: displayValue(item.productName) },
      { key: 'service', label: '成员服务号码', children: item.memberServiceNumber },
      { key: 'cutoff', label: '计费截止日期', children: item.billingCutoffDate },
      { key: 'remaining', label: '剩余天数', children: <Tag className="days-chip" color={item.remainingDays < 0 ? 'error' : 'cyan'}>{item.remainingDays < 0 ? `逾期 ${Math.abs(item.remainingDays)} 天` : `${item.remainingDays} 天`}</Tag> },
      { key: 'excel-manager', label: 'Excel 原始客户经理', children: displayValue(item.excelManagerName) },
      { key: 'effective-manager', label: '当前有效客户经理', children: item.effectiveManagerName || '未分配（归属匹配异常）' },
      { key: 'assignment', label: '归属匹配状态', children: item.effectiveManagerName ? <Tag color="success">已分配</Tag> : <Tag color="warning">未分配（归属匹配异常）</Tag> },
      { key: 'renewal-status', label: '续签处理状态', children: canUpdate ? <Space><Select aria-label="修改续签处理状态" value={status} onChange={value => { setStatus(value); if (value !== '跟进中') setFollowUpNote('') }} options={(detail.availableRenewalStatuses ?? renewalStatuses).map(value => ({ value, label: value }))} /><Button size="small" type="primary" loading={savingStatus} disabled={status === item.renewalStatus && !followUpNote.trim()} onClick={saveStatus}>保存</Button></Space> : <Tag>{item.renewalStatus}</Tag> },
      { key: 'last-handler', label: '最后处理人', children: displayValue(item.lastHandledByName) },
      { key: 'last-handled-at', label: '最后处理时间', children: displayValue(item.lastHandledAt) },
      { key: 'batch', label: '本行最近来源批次', children: `#${detail.batchId} · ${detail.originalFilename}` },
      { key: 'location', label: '最近来源位置', children: `${detail.sheetName} · 第 ${detail.sourceRowNumber} 行` },
    ]} />
    {canUpdate && status === '跟进中' && <Input.TextArea aria-label="专线跟进情况说明" rows={3} maxLength={2000}
      showCount placeholder="可填写本次跟进情况；留空时仅更新状态" value={followUpNote}
      onChange={event => setFollowUpNote(event.target.value)} style={{ marginTop: 16 }} />}
    <div className="detail-section-heading"><strong>处理时间线</strong><span>状态变化与跟进说明按时间合并展示</span></div>
    {detail.handlingTimeline.length === 0 ? <Typography.Text type="secondary">暂无处理记录</Typography.Text> :
      <div className="handling-timeline">{detail.handlingTimeline.map(event => <div className="handling-timeline-item" key={`${event.type}-${event.sourceId}`}>
        <div className="handling-timeline-meta"><Tag color={event.type === 'STATUS' ? 'blue' : 'gold'}>{event.type === 'STATUS' ? '状态变化' : '跟进说明'}</Tag>
          <Typography.Text type="secondary">{event.actorName || '未知用户'} · {event.happenedAt}</Typography.Text></div>
        {event.type === 'STATUS' ? <Typography.Text>{event.previousStatus} → {event.newStatus}</Typography.Text> :
          <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>{event.note}</Typography.Paragraph>}
      </div>)}</div>}
    <div className="detail-section-heading"><strong>Excel 原始字段</strong><span>保留导入时的原始内容</span></div>
    <Table size="small" rowKey="columnIndex" pagination={false} dataSource={detail.rawFields} columns={[{ title: '列位置', dataIndex: 'columnIndex', width: 80 }, { title: '字段', dataIndex: 'header', width: 230 }, { title: '原始值', dataIndex: 'value', render: (value: unknown) => displayValue(value) }]} />
  </>
}
