import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Card, Form, InputNumber, Modal, Select, Space, Switch, Table, Tag, Typography } from 'antd'
import { EditOutlined, ReloadOutlined } from '@ant-design/icons'
import { Customer, fetchRiskResults, fetchRiskRules, fetchRiskRuns, recalculateRisk, RiskResult, RiskRule, RiskRun, updateRiskRule } from '../api'
import { formatTime, QueryFeedback } from './QueryFeedback'
import { TaskDrawer } from './TaskCenter'
import { fetchRiskTask } from '../api'

function runCustomerIds(run: RiskRun): number[] {
  try {
    const ids: unknown = JSON.parse(run.scopeValue ?? '[]')
    return Array.isArray(ids) && ids.every(id => typeof id === 'number' && id > 0) ? ids : []
  } catch { return [] }
}

export function RiskCenter({ customers, admin }: { customers: Customer[]; admin: boolean }) {
  const client = useQueryClient()
  const [customerId, setCustomerId] = useState<number>()
  const [taskId, setTaskId] = useState<number>()
  const taskLink = useMutation({ mutationFn: fetchRiskTask, onSuccess: result => setTaskId(result.taskId) })
  const [recalcOpen, setRecalcOpen] = useState(false)
  const [customerIds, setCustomerIds] = useState<number[]>([])
  const [scope, setScope] = useState('ALL')
  const results = useQuery({ queryKey: ['risk-results', customerId], queryFn: () => fetchRiskResults(customerId), retry: false })
  const runs = useQuery({ queryKey: ['risk-runs'], queryFn: fetchRiskRuns, retry: false, enabled: admin, refetchInterval: query => query.state.data?.some(run => run.status === 'RUNNING') ? 3000 : false })
  const mutation = useMutation({
    mutationFn: () => recalculateRisk(scope === 'ALL' ? undefined : customerIds),
    onSuccess: async () => {
      setRecalcOpen(false)
      await Promise.all(['risk-results', 'risk-runs', 'customers', 'workbench-summary', 'tasks', 'audit-logs'].map(key => client.invalidateQueries({ queryKey: [key] })))
    },
  })
  const options = customers.map(item => ({ value: item.id, label: `${item.customerCode ?? ''} ${item.name}` }))
  return <>
    <div className="page-toolbar"><Space wrap>
      <Select aria-label="风险客户" placeholder="全部授权客户" allowClear showSearch optionFilterProp="label" style={{ width: 260 }} value={customerId} options={options} onChange={setCustomerId} />
      <Button icon={<ReloadOutlined />} loading={results.isFetching} onClick={() => { void results.refetch(); if (admin) void runs.refetch() }}>刷新</Button>
    </Space>{admin && <Button type="primary" icon={<ReloadOutlined />} loading={mutation.isPending} onClick={() => { mutation.reset(); setRecalcOpen(true) }}>风险重算</Button>}</div>
    <QueryFeedback error={results.error} retry={() => results.refetch()} />
    <QueryFeedback error={taskLink.error} retry={() => taskLink.reset()} />
    {taskLink.data?.message && <Alert type="info" message={taskLink.data.message} />}
    {taskId !== undefined && <TaskDrawer id={taskId} onClose={() => setTaskId(undefined)} />}
    {mutation.data && <Alert style={{ marginBottom: 16 }} showIcon type={mutation.data.status === 'FAILED' ? 'error' : mutation.data.status === 'RUNNING' ? 'info' : 'success'} message={`运行 #${mutation.data.runId} · ${mutation.data.status}`} description={mutation.data.message ?? `风险结果 ${mutation.data.resultCount ?? 0} 条`} />}
    <Card title="风险结果"><Table<RiskResult> rowKey="id" loading={results.isFetching} dataSource={results.isError ? [] : results.data ?? []} scroll={{ x: 1200 }} pagination={{ pageSize: 10 }} expandable={{ expandedRowRender: item => <Space wrap><span>来源：{item.sourceType} #{item.sourceId}</span><span>来源批次：{item.sourceBatchId ?? '—'}</span><span>运行：#{item.runId}</span><span>计算时间：{formatTime(item.calculatedAt)}</span></Space> }} columns={[
      { title: '客户编号', dataIndex: 'customerCode' }, { title: '客户名称', dataIndex: 'customerName' },
      { title: '规则', render: (_, item) => `${item.ruleCode} ${item.ruleName ?? ''}` },
      { title: '规则版本', dataIndex: 'ruleVersion' }, { title: '触发值', dataIndex: 'observedValue' },
      { title: '阈值', dataIndex: 'threshold' },
      { title: '风险级别', dataIndex: 'riskLevel', render: value => <Tag color={value === 'HIGH' ? 'error' : 'warning'}>{value}</Tag> },
      { title: '核查状态', dataIndex: 'status' }, { title: '计算时间', dataIndex: 'calculatedAt', render: formatTime },
      { title: '督办任务', render: (_, item) => <Button type="link" loading={taskLink.isPending} onClick={() => taskLink.mutate(item.id)}>查看任务</Button> },
    ]} /></Card>
    {admin && <div className="table-spaced"><QueryFeedback error={runs.error} retry={() => runs.refetch()} /><Card title="重算运行记录"><Table<RiskRun> rowKey="id" loading={runs.isFetching} dataSource={runs.isError ? [] : runs.data ?? []} scroll={{ x: 1100 }} pagination={{ pageSize: 10 }} expandable={{ expandedRowRender: run => <Space wrap><span>客户范围：{run.scopeValue ?? run.scopeType}</span><span>规则版本：{run.ruleVersions}</span><Typography.Text type="danger">{run.errorMessage}</Typography.Text>{run.status === 'FAILED' && <Button disabled={mutation.isPending} onClick={() => { mutation.reset(); setScope(run.scopeType === 'ALL' ? 'ALL' : 'SELECTED'); setCustomerIds(runCustomerIds(run)); setRecalcOpen(true) }}>重试此范围</Button>}</Space> }} columns={[
      { title: '运行编号', dataIndex: 'id' }, { title: '范围', dataIndex: 'scopeType' }, { title: '状态', dataIndex: 'status', render: value => <Tag color={value === 'FAILED' ? 'error' : value === 'COMPLETED' ? 'success' : 'processing'}>{value}</Tag> },
      { title: '结果数', dataIndex: 'successCount' }, { title: '失败数', dataIndex: 'failureCount' }, { title: '开始时间', dataIndex: 'startedAt', render: formatTime }, { title: '结束时间', dataIndex: 'finishedAt', render: formatTime },
    ]} /></Card></div>}
    <Modal title="确认风险重算" open={recalcOpen} onCancel={() => setRecalcOpen(false)} onOk={() => mutation.mutate()} confirmLoading={mutation.isPending} okButtonProps={{ disabled: scope !== 'ALL' && (customerIds.length === 0 || customerIds.length > 20) }}>
      {mutation.error && <Alert type="error" showIcon message={mutation.error.message} />}
      <Form layout="vertical" requiredMark={false}>
        <Form.Item label="计算范围"><Select value={scope} onChange={setScope} options={[{ value: 'ALL', label: '全部客户' }, { value: 'SELECTED', label: '指定客户' }]} /></Form.Item>
        {scope !== 'ALL' && <Form.Item label="客户（最多 20 个）" validateStatus={customerIds.length > 20 ? 'error' : undefined} help={customerIds.length > 20 ? '请选择不超过 20 个客户' : undefined}><Select mode="multiple" showSearch optionFilterProp="label" value={customerIds} options={options} onChange={setCustomerIds} /></Form.Item>}
      </Form>
    </Modal>
  </>
}

export function RuleAdmin() {
  const client = useQueryClient()
  const rules = useQuery({ queryKey: ['risk-rules'], queryFn: fetchRiskRules, retry: false })
  const [selected, setSelected] = useState<RiskRule>()
  const [form] = Form.useForm<{ threshold: number; operator: string; enabled: boolean }>()
  const [saved, setSaved] = useState(false)
  const mutation = useMutation({
    mutationFn: (values: { threshold: number; operator: string; enabled: boolean }) => updateRiskRule(selected!.code, values),
    onSuccess: async () => { setSelected(undefined); setSaved(true); await Promise.all(['risk-rules', 'audit-logs'].map(key => client.invalidateQueries({ queryKey: [key] }))) },
  })
  return <>
    <QueryFeedback error={rules.error} retry={() => rules.refetch()} />
    {saved && <Alert type="success" showIcon message="规则已保存，执行风险重算后更新风险结果" style={{ marginBottom: 16 }} />}
    <Card title="预警规则" extra={<Button icon={<ReloadOutlined />} loading={rules.isFetching} onClick={() => rules.refetch()}>刷新</Button>}><Table<RiskRule> rowKey="id" loading={rules.isFetching} dataSource={rules.isError ? [] : rules.data ?? []} pagination={false} scroll={{ x: 800 }} columns={[
      { title: '规则编码', dataIndex: 'code' }, { title: '规则名称', dataIndex: 'name' }, { title: '阈值', dataIndex: 'threshold' }, { title: '比较符', dataIndex: 'operator' }, { title: '版本', dataIndex: 'version' },
      { title: '状态', dataIndex: 'enabled', render: enabled => <Tag color={enabled ? 'success' : 'default'}>{enabled ? '启用' : '停用'}</Tag> },
      { title: '操作', render: (_, rule) => <Button type="link" icon={<EditOutlined />} onClick={() => { mutation.reset(); setSelected(rule); form.setFieldsValue({ threshold: rule.threshold ?? undefined, operator: rule.operator, enabled: rule.enabled }) }}>编辑</Button> },
    ]} /></Card>
    <Modal title={`编辑规则 ${selected?.code ?? ''}`} open={!!selected} onCancel={() => setSelected(undefined)} onOk={() => form.submit()} confirmLoading={mutation.isPending}>
      {mutation.error && <Alert type="error" showIcon message={mutation.error.message} />}
      <Form form={form} layout="vertical" requiredMark={false} onFinish={values => mutation.mutate(values)}>
        <Form.Item name="threshold" label="阈值" rules={[{ required: true, message: '请输入阈值' }]}><InputNumber min={0} precision={selected?.code === 'R003' || selected?.code === 'R004' ? 0 : 2} style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="operator" label="比较符" rules={[{ required: true, message: '请选择比较符' }]}><Select options={[{ value: 'LT', label: '<' }, { value: 'GT', label: '>' }, { value: 'LTE', label: '<=' }]} /></Form.Item>
        <Form.Item name="enabled" label="启用" valuePropName="checked"><Switch /></Form.Item>
      </Form>
    </Modal>
  </>
}
