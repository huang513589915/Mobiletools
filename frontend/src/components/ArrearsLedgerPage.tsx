import { useEffect, useMemo, useState } from 'react'
import type { Key } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Alert, Button, Card, DatePicker, Descriptions, Drawer, Empty, Input, Select, Space, Table, Tag, Typography, message } from 'antd'
import type { TableProps } from 'antd'
import { AccountBookOutlined, DownloadOutlined, FilterOutlined, ReloadOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { AdminUser, ArrearsLedgerFilters, ArrearsLedgerItem, arrearsWorkOrderStatuses, exportArrearsLedger, exportArrearsWorkOrders, fetchArrearsLedger, fetchArrearsLedgerDetail, fetchUsers, updateArrearsWorkOrder } from '../api'
import { QueryFeedback } from './QueryFeedback'
import { LedgerErasureDialog } from './LedgerErasureDialog'
import { currentBusinessMonth } from '../utils/businessCalendar'

const asText = (value: unknown) => value == null || value === '' ? '—' : String(value)
const money = (value: unknown) => value == null ? '—' : `¥${Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
const monthLabel = (value: string) => value.length === 6 ? `${value.slice(0, 4)}-${value.slice(4)}` : value
const monthDate = (value?: string) => value ? dayjs(`${value.slice(0, 4)}-${value.slice(4, 6)}-01`) : null
const defaultFilters = (): ArrearsLedgerFilters => {
  const month = currentBusinessMonth()
  return { page: 1, pageSize: 20, statisticsDateFrom: month, statisticsDateTo: month }
}

export function ArrearsLedgerPage({ isAdmin }: { isAdmin: boolean }) {
  const [filters, setFilters] = useState<ArrearsLedgerFilters>(defaultFilters)
  const [detailId, setDetailId] = useState<number>()
  const [exporting, setExporting] = useState(false)
  const [workOrderExporting, setWorkOrderExporting] = useState(false)
  const [selectedSnapshotIds, setSelectedSnapshotIds] = useState<Key[]>([])
  const [messageApi, messageContext] = message.useMessage()
  const query = useQuery({
    queryKey: ['arrears-ledger', filters],
    queryFn: () => fetchArrearsLedger(filters),
    retry: false,
  })
  const users = useQuery({
    queryKey: ['users'],
    queryFn: fetchUsers,
    retry: false,
    enabled: isAdmin,
  })
  const detail = useQuery({
    queryKey: ['arrears-ledger-detail', detailId],
    queryFn: () => fetchArrearsLedgerDetail(detailId!),
    enabled: detailId !== undefined,
    retry: false,
  })
  const managers = useMemo(() => (users.data ?? []).filter(user => user.role === 'CUSTOMER_MANAGER' && user.enabled), [users.data])
  const rows = query.data?.items ?? []

  const update = (patch: Partial<ArrearsLedgerFilters>) => {
    setFilters(current => ({ ...current, ...patch, page: 1 }))
  }
  const clear = () => setFilters(defaultFilters())
  const exportFiltered = async () => {
    setExporting(true)
    try {
      const blob = await exportArrearsLedger(filters)
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      const dateSuffix = filters.statisticsDateFrom || filters.statisticsDateTo
        ? `${filters.statisticsDateFrom ?? 'start'}-${filters.statisticsDateTo ?? 'end'}`
        : query.data?.statisticsKey ?? 'latest'
      anchor.download = `欠费明细-${dateSuffix}.xlsx`
      anchor.click()
      URL.revokeObjectURL(url)
    } catch (error) {
      messageApi.error(error instanceof Error ? error.message : '欠费导出失败')
    } finally {
      setExporting(false)
    }
  }
  const exportWorkOrders = async () => {
    setWorkOrderExporting(true)
    try {
      const blob = await exportArrearsWorkOrders(filters)
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = '欠费工单报表.xlsx'
      anchor.click()
      URL.revokeObjectURL(url)
    } catch (error) {
      messageApi.error(error instanceof Error ? error.message : '欠费工单报表导出失败')
    } finally { setWorkOrderExporting(false) }
  }
  const columns: TableProps<ArrearsLedgerItem>['columns'] = [
    { title: '日期', dataIndex: 'statisticsKey', width: 100, render: (value: string) => monthLabel(value) },
    { title: '集团编码', dataIndex: 'groupCode', width: 130 },
    { title: '集团名称', dataIndex: 'groupName', width: 180, render: (value: string) => <Typography.Text strong>{value}</Typography.Text> },
    { title: 'Excel 原始客户经理', dataIndex: 'excelManagerName', width: 145, render: asText },
    { title: '当前有效客户经理', dataIndex: 'effectiveManagerName', width: 165, render: (value: string | null) => value
      ? <span className="manager-cell"><span className="manager-dot" />{value}</span>
      : <Tag className="unassigned-tag" color="warning">未分配（归属匹配异常）</Tag> },
    { title: '账户 ID', dataIndex: 'accountId', width: 150 },
    { title: '承载产品名称', dataIndex: 'carryingProductName', width: 170, render: asText },
    { title: '期末欠费金额', dataIndex: 'endingArrearsAmount', width: 140, render: (value: number) => <Typography.Text className="money-value">{money(value)}</Typography.Text> },
    { title: '账龄', dataIndex: 'agingValue', width: 100, render: asText },
    { title: '工单状态', dataIndex: 'workOrderStatus', width: 110, render: (value: string) => <Tag color={value === '已结清' ? 'success' : value === '跟进中' ? 'processing' : 'default'}>{value}</Tag> },
    { title: '操作', fixed: 'right' as const, width: 80, render: (_: unknown, row: ArrearsLedgerItem) => <Button type="link" onClick={() => setDetailId(row.snapshotRecordId)}>详情</Button> },
  ]

  return <>
  {messageContext}
  <div className="ledger-page-intro">
    <div className="ledger-page-icon arrears-page-icon"><AccountBookOutlined /></div>
    <div><Typography.Text className="ledger-page-kicker">集团账户 · 月度快照</Typography.Text><Typography.Title level={4}>欠费明细</Typography.Title>
      <Typography.Text type="secondary">查看集团账户欠费情况、账龄和当前客户归属。</Typography.Text></div>
  </div>
  <Card className="ledger-card" title="欠费记录" extra={<Button icon={<ReloadOutlined />} loading={query.isFetching} onClick={() => query.refetch()}>刷新</Button>}>
    <section className="ledger-filter-panel" aria-label="欠费筛选条件">
      <div className="ledger-filter-heading"><FilterOutlined /><strong>筛选记录</strong><span>选择条件以缩小查询范围</span></div>
      <Space wrap className="ledger-filter-controls">
      <DatePicker.RangePicker aria-label="欠费统计月份范围" picker="month" format="YYYY-MM" allowClear
        placeholder={['开始月份', '结束月份']} style={{ width: 270 }}
        value={[monthDate(filters.statisticsDateFrom), monthDate(filters.statisticsDateTo)]}
        onChange={dates => update({ statisticsDateFrom: dates?.[0]?.format('YYYYMM'), statisticsDateTo: dates?.[1]?.format('YYYYMM') })} />
      <Input aria-label="集团筛选" placeholder="集团编码或名称" allowClear style={{ width: 190 }} value={filters.groupQuery ?? ''}
        onChange={event => update({ groupQuery: event.target.value || undefined })} />
      <Input aria-label="账户 ID 筛选" placeholder="账户 ID" allowClear style={{ width: 150 }} value={filters.accountId ?? ''}
        onChange={event => update({ accountId: event.target.value || undefined })} />
      <Input aria-label="承载产品筛选" placeholder="承载产品名称" allowClear style={{ width: 170 }} value={filters.carryingProductName ?? ''}
        onChange={event => update({ carryingProductName: event.target.value || undefined })} />
      {isAdmin && <Select aria-label="当前有效客户经理筛选" allowClear placeholder="全部客户经理" style={{ width: 180 }}
        value={filters.effectiveManagerUserId} onChange={value => update({ effectiveManagerUserId: value })}
        options={managers.map((manager: AdminUser) => ({ value: manager.id, label: `${manager.realName}（${manager.username}）` }))} />}
      <Select aria-label="欠费工单状态筛选" allowClear placeholder="全部工单状态" style={{ width: 150 }}
        value={filters.workOrderStatus} onChange={value => update({ workOrderStatus: value })}
        options={arrearsWorkOrderStatuses.map(status => ({ value: status, label: status }))} />
      <Button onClick={clear}>重置</Button>
      <Button className="export-button" icon={<DownloadOutlined />} loading={exporting} onClick={exportFiltered}>导出筛选结果</Button>
      {isAdmin && <Button className="export-button" icon={<DownloadOutlined />} loading={workOrderExporting} onClick={exportWorkOrders}>导出欠费工单</Button>}
      </Space>
    </section>
    <div className="ledger-result-bar">
      <div className="ledger-result-copy"><span className="ledger-result-label">统计月份</span>
        <Tag color="cyan">{filters.statisticsDateFrom || filters.statisticsDateTo
          ? `${filters.statisticsDateFrom ? monthLabel(filters.statisticsDateFrom) : '不限'} 至 ${filters.statisticsDateTo ? monthLabel(filters.statisticsDateTo) : '不限'}`
          : query.data?.statisticsKey ? monthLabel(query.data.statisticsKey) : '暂无数据'}</Tag>
        <span className="ledger-result-count">共 <strong>{query.data?.total ?? 0}</strong> 条记录</span>
      </div>
      <Typography.Text type="secondary">展示 Excel 原始客户经理与当前有效归属</Typography.Text>
    </div>
    <QueryFeedback error={query.error} retry={() => query.refetch()} />
    {isAdmin && <div style={{ marginBottom: 12 }}><LedgerErasureDialog sourceType="ARREARS"
      selectedSnapshotIds={selectedSnapshotIds.map(Number)} onClear={() => setSelectedSnapshotIds([])}
      onCompleted={() => void query.refetch()} /></div>}
    <Table<ArrearsLedgerItem>
      rowKey="snapshotRecordId" rowSelection={isAdmin ? { selectedRowKeys: selectedSnapshotIds,
        preserveSelectedRowKeys: true, onChange: keys => setSelectedSnapshotIds(keys) } : undefined}
      columns={columns} dataSource={rows} loading={query.isFetching}
      scroll={{ x: 1460 }} locale={{ emptyText: <Empty description="没有符合条件的欠费记录" /> }}
      pagination={{ current: query.data?.page ?? filters.page, pageSize: query.data?.pageSize ?? filters.pageSize,
        total: query.data?.total ?? 0, showSizeChanger: true, pageSizeOptions: [20, 50, 100],
        onChange: (page, pageSize) => setFilters(current => ({ ...current, page, pageSize })) }} />
  </Card>
  <Drawer title={<span className="drawer-heading"><AccountBookOutlined />欠费明细详情</span>} width={860} open={detailId !== undefined} onClose={() => setDetailId(undefined)}>
    {detail.error instanceof Error && <Alert type="error" showIcon message={detail.error.message} />}
    {detail.data && <ArrearsDetailDrawer detail={detail.data}
      canUpdate={isAdmin || Boolean(detail.data.item.effectiveManagerUserId)}
      onUpdated={() => { void query.refetch(); void detail.refetch() }} />}
    {detail.isFetching && !detail.data && <Typography.Text type="secondary">正在加载…</Typography.Text>}
  </Drawer>
  </>
}

function ArrearsDetailDrawer({ detail, canUpdate, onUpdated }: {
  detail: Awaited<ReturnType<typeof fetchArrearsLedgerDetail>>; canUpdate: boolean; onUpdated: () => void
}) {
  const item = detail.item
  const [status, setStatus] = useState(item.workOrderStatus)
  const [followUpNote, setFollowUpNote] = useState('')
  const [saving, setSaving] = useState(false)
  const [messageApi, messageContext] = message.useMessage()
  useEffect(() => { setStatus(item.workOrderStatus); setFollowUpNote('') }, [item.workOrderId, item.workOrderStatus])
  const displayValue = (value: unknown) => value == null || value === '' ? '—' : String(value)
  const hasNote = followUpNote.trim().length > 0
  const hasChanges = status !== item.workOrderStatus || hasNote
  const saveHandling = async () => {
    if (!item.workOrderId || !hasChanges) return
    setSaving(true)
    try {
      await updateArrearsWorkOrder(item.workOrderId, status, followUpNote)
      messageApi.success(status === item.workOrderStatus ? '跟进说明已追加' : '工单处理已更新')
      setFollowUpNote('')
      onUpdated()
    } catch (error) {
      messageApi.error(error instanceof Error ? error.message : '工单处理保存失败')
    } finally { setSaving(false) }
  }
  return <>
    {messageContext}
    <div className="detail-context-bar"><span className="detail-context-icon"><AccountBookOutlined /></span><div><strong>{item.groupName}</strong><span>集团编码 {item.groupCode} · 账户 {item.accountId}</span></div><Tag color={item.effectiveManagerName ? 'success' : 'warning'}>{item.effectiveManagerName ? '归属已确认' : '待处理归属'}</Tag></div>
    <Descriptions bordered size="small" column={2} items={[
      { key: 'date', label: '日期', children: monthLabel(item.statisticsKey) },
      { key: 'group', label: '集团', children: `${item.groupCode} · ${item.groupName}` },
      { key: 'account', label: '账户 ID', children: item.accountId },
      { key: 'product', label: '承载产品名称', children: displayValue(item.carryingProductName) },
      { key: 'amount', label: '期末欠费金额', children: money(item.endingArrearsAmount) },
      { key: 'aging', label: '账龄', children: displayValue(item.agingValue) },
      { key: 'work-order-status', label: '工单状态', children: <Tag color={item.workOrderStatus === '已结清' ? 'success' : item.workOrderStatus === '跟进中' ? 'processing' : 'default'}>{item.workOrderStatus}</Tag> },
      { key: 'last-handler', label: '最后处理人', children: displayValue(item.lastHandledByName) },
      { key: 'last-handled-at', label: '最后处理时间', children: displayValue(item.lastHandledAt) },
      { key: 'excel-manager', label: 'Excel 原始客户经理', children: displayValue(item.excelManagerName) },
      { key: 'effective-manager', label: '当前有效客户经理', children: item.effectiveManagerName || '未分配（归属匹配异常）' },
      { key: 'assignment', label: '归属匹配状态', children: item.effectiveManagerName ? <Tag color="success">已分配</Tag> : <Tag color="warning">未分配（归属匹配异常）</Tag> },
      { key: 'batch', label: '本行最近来源批次', children: `#${detail.batchId} · ${detail.originalFilename}` },
      { key: 'location', label: '最近来源位置', children: `${detail.sheetName} · 第 ${detail.sourceRowNumber} 行` },
    ]} />
    <div className="detail-section-heading"><strong>工单处理</strong><span>状态修改和跟进说明分别追加记录</span></div>
    {canUpdate ? <Space direction="vertical" style={{ width: '100%' }}>
      <Space wrap>
        <Select aria-label="欠费工单状态" value={status} onChange={value => { setStatus(value); if (value !== '跟进中') setFollowUpNote('') }}
          options={arrearsWorkOrderStatuses.map(value => ({ value, label: value }))} />
        <Button type="primary" loading={saving} disabled={!hasChanges} onClick={saveHandling}>保存处理</Button>
      </Space>
      {status === '跟进中' && <Input.TextArea aria-label="欠费跟进情况说明" rows={3} maxLength={2000}
        showCount placeholder="可填写本次跟进情况；留空时仅更新状态" value={followUpNote}
        onChange={event => setFollowUpNote(event.target.value)} />}
    </Space> : <Typography.Text type="secondary">当前记录尚未分配有效客户经理，仅管理员可以处理。</Typography.Text>}
    <div className="detail-section-heading"><strong>处理时间线</strong><span>状态变化与跟进说明按时间合并展示</span></div>
    {detail.handlingTimeline.length === 0 ? <Typography.Text type="secondary">暂无处理记录</Typography.Text> :
      <div className="handling-timeline">{detail.handlingTimeline.map(event => <div className="handling-timeline-item" key={`${event.type}-${event.sourceId}`}>
        <div className="handling-timeline-meta"><Tag color={event.type === 'STATUS' ? 'blue' : 'gold'}>{event.type === 'STATUS' ? '状态变化' : '跟进说明'}</Tag>
          <Typography.Text type="secondary">{event.actorName || '未知用户'} · {event.happenedAt}</Typography.Text></div>
        {event.type === 'STATUS' ? <Typography.Text>{event.previousStatus} → {event.newStatus}</Typography.Text> :
          <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>{event.note}</Typography.Paragraph>}
      </div>)}</div>}
    <div className="detail-section-heading"><strong>Excel 原始字段</strong><span>保留导入时的原始内容</span></div>
    <Table size="small" rowKey="columnIndex" pagination={false} dataSource={detail.rawFields}
      columns={[{ title: '列位置', dataIndex: 'columnIndex', width: 80 }, { title: '字段', dataIndex: 'header', width: 230 }, { title: '原始值', dataIndex: 'value', render: (value: unknown) => displayValue(value) }]} />
  </>
}
