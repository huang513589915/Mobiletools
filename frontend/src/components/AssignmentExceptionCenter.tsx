import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Card, Descriptions, Drawer, Empty, Input, Modal, Select, Space, Table, Tag, Typography } from 'antd'
import type { TableProps } from 'antd'
import { FileSearchOutlined, ReloadOutlined } from '@ant-design/icons'
import {
  AdminUser, AssignmentExceptionDetail, AssignmentExceptionFilters, AssignmentExceptionItem,
  assignExceptions, fetchAssignmentException, fetchAssignmentExceptions, fetchUsers,
} from '../api'
import { QueryFeedback, formatTime } from './QueryFeedback'

type Selection = { ids: number[]; sourceType?: string; allFiltered: boolean }

export function AssignmentExceptionCenter() {
  const client = useQueryClient()
  const [filters, setFilters] = useState<AssignmentExceptionFilters>({ page: 1, pageSize: 20 })
  const [selection, setSelection] = useState<Selection>({ ids: [], allFiltered: false })
  const [targetManagerId, setTargetManagerId] = useState<number>()
  const [reason, setReason] = useState('')
  const [detailId, setDetailId] = useState<number>()
  const list = useQuery({ queryKey: ['assignment-exceptions', filters], queryFn: () => fetchAssignmentExceptions(filters), retry: false })
  const detail = useQuery({ queryKey: ['assignment-exception', detailId], queryFn: () => fetchAssignmentException(detailId!), enabled: detailId !== undefined, retry: false })
  const users = useQuery({ queryKey: ['users'], queryFn: fetchUsers, retry: false })
  const managers = useMemo(() => (users.data ?? []).filter(user => user.role === 'CUSTOMER_MANAGER' && user.enabled), [users.data])
  const rows = list.data?.items ?? []
  const selectionCount = selection.allFiltered ? list.data?.total ?? 0 : selection.ids.length
  const assignment = useMutation({
    mutationFn: assignExceptions,
    onSuccess: async result => {
      Modal.success({ title: '归属分配完成', content: `已处理 ${result.selectedCount} 条记录。` })
      setSelection({ ids: [], allFiltered: false })
      await Promise.all(['assignment-exceptions', 'arrears-ledger', 'line-expiry-ledger', 'audit-logs'].map(key => client.invalidateQueries({ queryKey: [key] })))
    },
  })

  const updateFilter = (patch: Partial<AssignmentExceptionFilters>) => {
    setFilters(current => ({ ...current, ...patch, page: 1 }))
    setSelection({ ids: [], allFiltered: false })
  }
  const clearFilters = () => {
    setFilters({ page: 1, pageSize: 20 })
    setSelection({ ids: [], allFiltered: false })
  }
  const confirmAssignment = () => {
    const manager = managers.find(item => item.id === targetManagerId)
    const sourceType = selection.sourceType
    if (!manager || selectionCount < 1 || !sourceType) return
    Modal.confirm({
      title: '确认批量分配',
      content: `将 ${selectionCount} 条${sourceType === 'ARREARS' ? '欠费' : '专线'}记录分配给 ${manager.realName}（${manager.username}）？`,
      okText: '确认分配', cancelText: '取消',
      onOk: async () => assignment.mutateAsync(selection.allFiltered ? {
        allFiltered: true, sourceType, targetManagerUserId: manager.id, reason,
        statisticsKey: filters.statisticsKey, groupCode: filters.groupCode, groupName: filters.groupName,
        excelManagerName: filters.excelManagerName, batchId: filters.batchId, status: filters.status,
      } : { sourceType, snapshotRecordIds: selection.ids, targetManagerUserId: manager.id, reason }),
    })
  }

  const rowSelection: TableProps<AssignmentExceptionItem>['rowSelection'] = {
    selectedRowKeys: selection.allFiltered ? [] : selection.ids,
    preserveSelectedRowKeys: true,
    getCheckboxProps: record => ({ disabled: Boolean(selection.sourceType && selection.sourceType !== record.sourceType) }),
    onChange: keys => {
      const ids = keys.map(Number)
      if (ids.length === 0) { setSelection({ ids: [], allFiltered: false }); return }
      const selectedRows = rows.filter(row => ids.includes(row.snapshotRecordId))
      const sourceType = selection.sourceType ?? selectedRows[0]?.sourceType
      if (sourceType && selectedRows.some(row => row.sourceType !== sourceType)) return
      setSelection({ ids, sourceType, allFiltered: false })
    },
  }

  const columns = [
    { title: '来源', dataIndex: 'sourceType', width: 100, render: (value: string) => value === 'ARREARS' ? '欠费' : '专线' },
    { title: '统计日期', dataIndex: 'statisticsKey', width: 115 },
    { title: '集团编码', dataIndex: 'groupCode', width: 140 },
    { title: '集团名称', dataIndex: 'groupName', width: 190 },
    { title: 'Excel 客户经理', dataIndex: 'excelManagerName', width: 140, render: (value: string) => value || '（空）' },
    { title: '异常原因', dataIndex: 'reason', width: 220 },
    { title: '批次 / 文件', width: 180, render: (_: unknown, row: AssignmentExceptionItem) => <>{`#${row.batchId}`}<br /><Typography.Text type="secondary">{row.originalFilename}</Typography.Text></> },
    { title: '工作表', dataIndex: 'sheetName', width: 130 },
    { title: '原始行号', dataIndex: 'sourceRowNumber', width: 95 },
    { title: '处理状态', dataIndex: 'status', width: 100, render: (value: string) => <Tag color={value === 'OPEN' ? 'warning' : 'success'}>{value === 'OPEN' ? '待分配' : '已处理'}</Tag> },
    { title: '当前有效客户经理', dataIndex: 'effectiveManagerName', width: 145, render: (value: string | null) => value || '未分配（归属匹配异常）' },
    { title: '操作', fixed: 'right' as const, width: 90, render: (_: unknown, row: AssignmentExceptionItem) => <Button type="link" onClick={() => setDetailId(row.id)}>详情</Button> },
  ]

  return <>
    <div className="management-page-intro exception-page-intro">
      <div className="management-page-icon"><FileSearchOutlined /></div>
      <div><Typography.Text className="management-page-kicker">客户归属维护</Typography.Text><Typography.Title level={3}>归属匹配异常</Typography.Title>
        <Typography.Text type="secondary">查看 Excel 客户经理匹配问题，并按记录批量维护当前有效归属。</Typography.Text></div>
    </div>
    <Card className="management-card" title="异常记录" extra={<Button icon={<ReloadOutlined />} loading={list.isFetching} onClick={() => list.refetch()}>刷新</Button>}>
      <Space wrap className="exception-filters">
        <Select aria-label="数据来源" allowClear placeholder="全部来源" style={{ width: 130 }} value={filters.sourceType} onChange={value => updateFilter({ sourceType: value })} options={[{ value: 'ARREARS', label: '欠费' }, { value: 'LINE_EXPIRY', label: '专线' }]} />
        <Input aria-label="统计日期" placeholder="统计日期" value={filters.statisticsKey ?? ''} onChange={event => updateFilter({ statisticsKey: event.target.value || undefined })} style={{ width: 140 }} />
        <Input aria-label="集团编码" placeholder="集团编码" value={filters.groupCode ?? ''} onChange={event => updateFilter({ groupCode: event.target.value || undefined })} style={{ width: 150 }} />
        <Input aria-label="集团名称" placeholder="集团名称" value={filters.groupName ?? ''} onChange={event => updateFilter({ groupName: event.target.value || undefined })} style={{ width: 170 }} />
        <Input aria-label="Excel 客户经理姓名" placeholder="Excel 客户经理姓名" value={filters.excelManagerName ?? ''} onChange={event => updateFilter({ excelManagerName: event.target.value || undefined })} style={{ width: 190 }} />
        <Input aria-label="批次编号" placeholder="批次编号" inputMode="numeric" value={filters.batchId ?? ''} onChange={event => updateFilter({ batchId: event.target.value ? Number(event.target.value) : undefined })} style={{ width: 125 }} />
        <Select aria-label="处理状态" allowClear placeholder="全部状态" style={{ width: 120 }} value={filters.status} onChange={value => updateFilter({ status: value })} options={[{ value: 'OPEN', label: '待分配' }, { value: 'RESOLVED', label: '已处理' }]} />
        <Button onClick={clearFilters}>重置</Button>
      </Space>
      <QueryFeedback error={list.error} retry={() => list.refetch()} />
      {assignment.error && <Alert style={{ marginBottom: 12 }} type="error" showIcon message={assignment.error.message} />}
      <Space wrap className="exception-assignment-toolbar">
        <Typography.Text>已选 {selectionCount} 条{selection.sourceType ? ` · ${selection.sourceType === 'ARREARS' ? '欠费' : '专线'}` : ''}</Typography.Text>
        <Button disabled={!filters.sourceType || !list.data?.total} onClick={() => setSelection({ ids: [], sourceType: filters.sourceType, allFiltered: true })}>选择全部筛选结果（{list.data?.total ?? 0}）</Button>
        {selection.allFiltered && <Button onClick={() => setSelection({ ids: [], allFiltered: false })}>取消全筛选选择</Button>}
        <Select aria-label="分配目标客户经理" placeholder="选择目标客户经理" loading={users.isFetching} value={targetManagerId} onChange={setTargetManagerId} style={{ width: 210 }} options={managers.map(manager => ({ value: manager.id, label: `${manager.realName}（${manager.username}）` }))} />
        <Input aria-label="分配原因" placeholder="分配说明（可选）" value={reason} onChange={event => setReason(event.target.value)} maxLength={1000} style={{ width: 220 }} />
        <Button type="primary" disabled={!selectionCount || !targetManagerId || !selection.sourceType} loading={assignment.isPending} onClick={confirmAssignment}>确认分配</Button>
      </Space>
      <Table<AssignmentExceptionItem> className="management-table" rowKey="snapshotRecordId" rowSelection={rowSelection} loading={list.isFetching} dataSource={rows} columns={columns} scroll={{ x: 1750 }} pagination={{ current: filters.page, pageSize: filters.pageSize, total: list.data?.total ?? 0, showSizeChanger: true, pageSizeOptions: [20, 50, 100], onChange: (page, pageSize) => setFilters(current => ({ ...current, page, pageSize })) }} locale={{ emptyText: <Empty description="没有符合条件的归属异常记录" /> }} />
    </Card>
    <ExceptionDetailDrawer id={detailId} detail={detail.data} loading={detail.isFetching} error={detail.error} onClose={() => setDetailId(undefined)} />
  </>
}

function ExceptionDetailDrawer({ id, detail, loading, error, onClose }: { id?: number; detail?: AssignmentExceptionDetail; loading: boolean; error: unknown; onClose: () => void }) {
  return <Drawer title="归属匹配异常详情" open={id !== undefined} onClose={onClose} width={760}>
    {error instanceof Error && <Alert type="error" showIcon message={error.message} />}
    {detail && <>
      <Descriptions bordered size="small" column={2} items={[
        { key: 'source', label: '数据来源', children: detail.item.sourceType === 'ARREARS' ? '欠费' : '专线' },
        { key: 'date', label: '统计日期', children: detail.item.statisticsKey },
        { key: 'group', label: '集团', children: `${detail.item.groupCode} · ${detail.item.groupName}` },
        { key: 'manager', label: 'Excel 原始客户经理', children: detail.item.excelManagerName || '（空）' },
        { key: 'effective', label: '当前有效客户经理', children: detail.item.effectiveManagerName || '未分配（归属匹配异常）' },
        { key: 'reason', label: '匹配失败原因', span: 2, children: detail.item.reason },
        { key: 'batch', label: '文件批次', children: `#${detail.item.batchId} · ${detail.item.originalFilename}` },
        { key: 'location', label: '原始位置', children: `${detail.item.sheetName} · 第 ${detail.item.sourceRowNumber} 行` },
        { key: 'state', label: '处理状态', children: detail.item.status === 'OPEN' ? '待分配' : '已处理' },
        { key: 'resolution', label: '处理说明', children: detail.item.resolution || '—' },
      ]} />
      <Typography.Title level={5} style={{ marginTop: 20 }}>Excel 原始字段</Typography.Title>
      <Table size="small" rowKey="columnIndex" pagination={false} dataSource={detail.rawFields} columns={[{ title: '列位置', dataIndex: 'columnIndex', width: 80 }, { title: '字段', dataIndex: 'header', width: 220 }, { title: '原始值', dataIndex: 'value', render: (value: unknown) => value == null || value === '' ? '—' : String(value) }]} />
      <Typography.Title level={5} style={{ marginTop: 20 }}>归属变更历史</Typography.Title>
      {detail.assignmentHistory.length ? <Table size="small" rowKey={(_, index) => String(index)} pagination={false} dataSource={detail.assignmentHistory} columns={[
        { title: '时间', dataIndex: 'changedAt', render: formatTime }, { title: '操作', dataIndex: 'changeType' },
        { title: '原归属', dataIndex: 'previousManagerName', render: value => value || '未分配' },
        { title: '新归属', dataIndex: 'newManagerName' }, { title: '操作人', dataIndex: 'changedByUsername' },
        { title: '原因', dataIndex: 'reason' },
      ]} /> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无归属变更历史" />}
    </>}
    {loading && !detail && <Typography.Text type="secondary">正在加载…</Typography.Text>}
  </Drawer>
}
