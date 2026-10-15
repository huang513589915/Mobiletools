import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Card, Descriptions, Drawer, Form, Input, Modal, Popconfirm, Select, Space, Table, Tag, Timeline, Typography } from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { addFollowUp, closeTask, completeTask, fetchTaskDetail, fetchTasks, startTask, Task, TaskFilters } from '../api'
import { formatTime, QueryFeedback } from './QueryFeedback'

const statuses = ['UNFINISHED', '待处理', '处理中', '待核实', '已完成', '已关闭']
const closed = (task: Task) => ['已完成', '已关闭'].includes(task.status)
function overdueDays(task: Task) {
  return task.overdue && !closed(task) ? task.overdueDays : 0
}

export function TaskCenter({ onCreate, initialOverdue, initialUnfinished }: { onCreate: () => void; initialOverdue?: boolean; initialUnfinished?: boolean }) {
  const [filters, setFilters] = useState<TaskFilters>({ overdue: initialOverdue, status: initialUnfinished ? 'UNFINISHED' : undefined })
  const [owner, setOwner] = useState('')
  const [selectedId, setSelectedId] = useState<number>()
  const list = useQuery({ queryKey: ['tasks', filters], queryFn: () => fetchTasks(filters), retry: false })
  return <>
    <div className="page-toolbar"><Space wrap>
      <Select aria-label="任务状态" placeholder="全部状态" allowClear style={{ width: 140 }} value={filters.status} options={statuses.map(value => ({ value, label: value === 'UNFINISHED' ? '全部未结束' : value }))} onChange={status => setFilters({ ...filters, status })} />
      <Select aria-label="优先级" placeholder="全部优先级" allowClear style={{ width: 140 }} value={filters.priority} options={['高', '中', '低'].map(value => ({ value, label: value }))} onChange={priority => setFilters({ ...filters, priority })} />
      <Input.Search aria-label="负责人" placeholder="负责人" value={owner} allowClear style={{ width: 200 }} onChange={e => { setOwner(e.target.value); if (!e.target.value) setFilters({ ...filters, owner: undefined }) }} onSearch={value => setFilters({ ...filters, owner: value.trim() || undefined })} />
      <Select aria-label="逾期状态" placeholder="全部截止状态" allowClear style={{ width: 160 }} value={filters.overdue} options={[{ value: true, label: '仅逾期' }, { value: false, label: '未逾期' }]} onChange={overdue => setFilters({ ...filters, overdue })} />
      <Button onClick={() => { setFilters({}); setOwner('') }}>重置</Button>
      <Button icon={<ReloadOutlined />} loading={list.isFetching} onClick={() => list.refetch()}>刷新</Button>
    </Space><Button type="primary" icon={<PlusOutlined />} onClick={onCreate}>新建任务</Button></div>
    <QueryFeedback error={list.error} retry={() => list.refetch()} />
    <Card title="任务清单"><Table<Task> rowKey="id" loading={list.isFetching} dataSource={list.isError ? [] : list.data ?? []} scroll={{ x: 1000 }} pagination={{ pageSize: 10 }} columns={[
      { title: '任务事项', dataIndex: 'title' },
      { title: '客户', render: (_, task) => <>{task.customerName}<br /><Typography.Text type="secondary">{task.customerCode}</Typography.Text></> },
      { title: '负责人', dataIndex: 'owner' },
      { title: '截止日期', render: (_, task) => <>{task.deadline} {overdueDays(task) > 0 && <Tag color="error">逾期 {overdueDays(task)} 天</Tag>}</> },
      { title: '优先级', dataIndex: 'priority', render: value => <Tag color={value === '高' ? 'error' : value === '中' ? 'warning' : 'default'}>{value}</Tag> },
      { title: '状态', dataIndex: 'status', render: value => <Tag color={value === '已完成' ? 'success' : ['处理中', '进行中'].includes(value) ? 'processing' : 'default'}>{value}</Tag> },
      { title: '操作', render: (_, task) => <Button type="link" onClick={() => setSelectedId(task.id)}>查看详情</Button> },
    ]} /></Card>
    {selectedId !== undefined && <TaskDrawer id={selectedId} onClose={() => setSelectedId(undefined)} />}
  </>
}

export function TaskDrawer({ id, onClose }: { id: number; onClose: () => void }) {
  const client = useQueryClient()
  const detail = useQuery({ queryKey: ['task-detail', id], queryFn: () => fetchTaskDetail(id), retry: false })
  const [followForm] = Form.useForm<{ note: string; nextAction: string }>()
  const [closeForm] = Form.useForm<{ reason: string }>()
  const [closeOpen, setCloseOpen] = useState(false)
  const [success, setSuccess] = useState('')
  const mutation = useMutation({
    mutationFn: async ({ action, values }: { action: string; values?: { note?: string; nextAction?: string; reason?: string } }) => {
      if (action === 'follow') await addFollowUp(id, { note: values?.note?.trim() ?? '', nextAction: values?.nextAction?.trim() ?? '' })
      if (action === 'start') await startTask(id)
      if (action === 'complete') await completeTask(id)
      if (action === 'close') await closeTask(id, values?.reason?.trim() ?? '')
      return action
    },
    onMutate: () => setSuccess(''),
    onSuccess: async action => {
      setSuccess(action === 'follow' ? '跟进记录已保存' : '任务状态已更新')
      if (action === 'follow') followForm.resetFields()
      setCloseOpen(false)
      closeForm.resetFields()
      await Promise.all([client.invalidateQueries({ queryKey: ['tasks'] }), client.invalidateQueries({ queryKey: ['task-detail', id] }), client.invalidateQueries({ queryKey: ['workbench-summary'] }), client.invalidateQueries({ queryKey: ['risk-results'] })])
    },
  })
  const task = detail.isError ? undefined : detail.data
  return <Drawer title="任务详情" open onClose={onClose} width={640}>
    <QueryFeedback error={detail.error} retry={() => detail.refetch()} />
    {detail.isFetching && <Typography.Paragraph>正在加载任务…</Typography.Paragraph>}
    {mutation.error && <Alert type="error" showIcon message={mutation.error.message} style={{ marginBottom: 16 }} />}
    {success && <Alert type="success" showIcon message={success} style={{ marginBottom: 16 }} />}
    {task && <>
      <Descriptions bordered column={1} items={[
        { key: 'title', label: '任务事项', children: task.title }, { key: 'customer', label: '客户', children: `${task.customerCode ?? ''} ${task.customerName ?? ''}` },
        { key: 'owner', label: '负责人', children: task.owner }, { key: 'deadline', label: '截止日期', children: <>{task.deadline} {overdueDays(task) > 0 && <Tag color="error">逾期 {overdueDays(task)} 天</Tag>}</> },
        { key: 'status', label: '状态 / 优先级', children: `${task.status} / ${task.priority}` },
        { key: 'source', label: '任务来源', children: `${task.sourceType ?? '历史任务'} ${task.sourceId ? '#' + task.sourceId : ''}` },
        { key: 'type', label: '任务类型', children: task.taskType ?? '—' },
        ...(task.closeReason ? [{ key: 'reason', label: '关闭结论', children: task.closeReason }] : []),
      ]} />
      {!closed(task) && <Space wrap style={{ marginTop: 16 }}>
        {['待处理', '待核实'].includes(task.status) && <Button type="primary" loading={mutation.isPending} onClick={() => mutation.mutate({ action: 'start' })}>开始处理</Button>}
        <Popconfirm title="确认完成此任务？" onConfirm={() => mutation.mutateAsync({ action: 'complete' })}><Button disabled={mutation.isPending}>完成任务</Button></Popconfirm>
        <Button danger disabled={mutation.isPending} onClick={() => { mutation.reset(); setCloseOpen(true) }}>关闭任务</Button>
      </Space>}
      <Typography.Title level={5}>跟进记录</Typography.Title>
      {task.followUps.length ? <Timeline items={task.followUps.map(item => ({ children: <><Typography.Text type="secondary">{formatTime(item.createdAt)} · {item.createdBy}</Typography.Text><Typography.Paragraph style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{item.note}</Typography.Paragraph>{item.nextAction && <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>下一步：{item.nextAction}</Typography.Paragraph>}</> }))} /> : <Typography.Paragraph type="secondary">暂无跟进记录</Typography.Paragraph>}
      {!closed(task) && <Form form={followForm} layout="vertical" requiredMark={false} onFinish={values => mutation.mutate({ action: 'follow', values })}>
        <Form.Item name="note" label="跟进内容" dependencies={['nextAction']} rules={[({ getFieldValue }) => ({ validator(_, value) { return value?.trim() || getFieldValue('nextAction')?.trim() ? Promise.resolve() : Promise.reject(new Error('请填写跟进内容或下一步')) } })]}><Input.TextArea rows={3} maxLength={1000} /></Form.Item>
        <Form.Item name="nextAction" label="下一步"><Input.TextArea rows={2} maxLength={1000} /></Form.Item>
        <Button type="primary" htmlType="submit" loading={mutation.isPending} disabled={detail.isFetching}>追加跟进</Button>
      </Form>}
    </>}
    <Modal title="关闭任务" open={closeOpen} onCancel={() => setCloseOpen(false)} onOk={() => closeForm.submit()} confirmLoading={mutation.isPending}>
      {mutation.error && <Alert type="error" message={mutation.error.message} />}
      <Form form={closeForm} layout="vertical" requiredMark={false} onFinish={values => mutation.mutate({ action: 'close', values })}>
        <Form.Item name="reason" label="关闭原因 / 结论" rules={[{ required: true, whitespace: true, message: '请输入关闭原因或结论' }]}><Input.TextArea rows={4} maxLength={1000} /></Form.Item>
      </Form>
    </Modal>
  </Drawer>
}
