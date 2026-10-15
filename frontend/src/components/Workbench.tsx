import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Button, Card, Input, Select, Space, Statistic, Table, Tag, Typography } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import { Customer, fetchWorkbench, WorkbenchFilters, WorkbenchItem } from '../api'
import { QueryFeedback, formatTime } from './QueryFeedback'
import { TaskDrawer } from './TaskCenter'

export function Workbench({ customers, onTasks, onNavigate }: { customers: Customer[]; onTasks: (overdue?: boolean) => void; onNavigate: (key: string, context?: Record<string, string | number>) => void }) {
  const [filters, setFilters] = useState<WorkbenchFilters>({})
  const [kind, setKind] = useState<string>()
  const [taskId, setTaskId] = useState<number>()
  const query = useQuery({ queryKey: ['workbench-summary', filters], queryFn: () => fetchWorkbench(filters), retry: false })
  const [summary, rows] = query.data ?? [undefined, []]
  const update = (key: keyof WorkbenchFilters, value?: string) => setFilters(current => ({ ...current, [key]: value }))
  return <>
    <div className="page-toolbar"><Space wrap>
      <Select aria-label="工作台风险级别" placeholder="全部风险" allowClear style={{ width: 140 }} value={filters.riskLevel} onChange={v => update('riskLevel', v)} options={[{ value: 'HIGH', label: '高风险' }, { value: 'MEDIUM', label: '中风险' }]} />
      <Select aria-label="工作台负责人" placeholder="全部负责人" allowClear style={{ width: 150 }} value={filters.owner} onChange={v => update('owner', v)} options={[...new Set(customers.map(c => c.manager))].map(value => ({ value, label: value }))} />
      <Select aria-label="工作台业务类型" placeholder="全部业务类型" allowClear style={{ width: 160 }} value={filters.businessType} onChange={v => update('businessType', v)} options={[...new Set(customers.map(c => c.type))].map(value => ({ value, label: value }))} />
      <Input type="date" aria-label="工作台开始日期" value={filters.from ?? ''} onChange={e => update('from', e.target.value)} style={{ width: 160 }} />
      <Input type="date" aria-label="工作台结束日期" value={filters.to ?? ''} onChange={e => update('to', e.target.value)} style={{ width: 160 }} />
      <Select aria-label="工作台异常状态" placeholder="全部事项" allowClear style={{ width: 150 }} value={filters.exceptionStatus} onChange={v => update('exceptionStatus', v)} options={['OPEN', 'RESOLVED', 'IGNORED'].map(value => ({ value, label: value }))} />
      <Button onClick={() => { setFilters({}); setKind(undefined) }}>重置</Button><Button icon={<ReloadOutlined />} onClick={() => query.refetch()}>刷新</Button>
    </Space></div>
    <QueryFeedback error={query.error} retry={() => query.refetch()} />
    <Typography.Paragraph type="secondary">更新时间：{summary ? formatTime(summary.calculatedAt) : '—'}</Typography.Paragraph>
    <div className="stat-grid"><Card><Statistic title="高风险客户" value={summary?.highRiskCustomers ?? 0} /><Button type="link" onClick={() => { update('riskLevel', 'HIGH'); setKind('RISK') }}>查看风险</Button></Card><Card><Statistic title="待处理任务" value={summary?.pendingTasks ?? 0} /><Button type="link" onClick={() => onTasks()}>查看待办</Button></Card><Card><Statistic title="30天内到期业务" value={summary?.expiringServices ?? 0} /><Button type="link" onClick={() => setKind('EXPIRY')}>查看到期</Button></Card><Card><Statistic title="待核查异常" value={summary?.openExceptions ?? 0} /><Button type="link" onClick={() => { update('exceptionStatus', 'OPEN'); setKind('EXCEPTION') }}>查看异常</Button></Card></div>
    <Button danger={Boolean(summary?.overdueTasks)} onClick={() => onTasks(true)}>逾期任务 {summary?.overdueTasks ?? 0} 项</Button>
      <Card title="工作台事项" className="table-spaced"><Table<WorkbenchItem> rowKey={r => `${r.kind}-${r.id}`} loading={query.isFetching} dataSource={query.isError ? [] : rows.filter(r => !kind || r.kind === kind)} scroll={{ x: 1000 }} pagination={{ pageSize: 10 }} columns={[{ title: '事项类型', dataIndex: 'kind' }, { title: '客户', dataIndex: 'customerName' }, { title: '事项', dataIndex: 'title' }, { title: '负责人', dataIndex: 'owner' }, { title: '日期', dataIndex: 'eventDate' }, { title: '状态', render: (_, r) => <Tag>{r.riskLevel ?? r.status}</Tag> }, { title: '来源追溯', render: (_, r) => <>{r.batchId ? `批次 #${r.batchId}` : '—'} {r.sheetName ? `${r.sheetName} ` : ''}{r.sourceRowNumber ? `原始行 ${r.sourceRowNumber}` : ''}</> }, { title: '操作', render: (_, r) => r.kind === 'TASK' ? <Button type="link" onClick={() => setTaskId(r.id)}>处理任务</Button> : r.kind === 'RISK' ? <Button type="link" onClick={() => onNavigate('ledger', { customerId: r.customerId ?? 0, riskLevel: r.riskLevel ?? '' })}>查看台账</Button> : r.kind === 'EXPIRY' ? <Button type="link" onClick={() => onNavigate('ledger', { expiryFrom: r.eventDate, expiryTo: r.eventDate })}>查看到期</Button> : <Button type="link" onClick={() => onNavigate('issues', { status: r.status, batchId: r.batchId ?? '', sourceRowNumber: r.sourceRowNumber ?? '', exceptionId: r.id, sheetName: r.sheetName ?? '' })}>查看异常</Button> }]} /></Card>
    {taskId !== undefined && <TaskDrawer id={taskId} onClose={() => setTaskId(undefined)} />}
  </>
}
