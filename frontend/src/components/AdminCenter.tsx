import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Card, Form, Input, InputNumber, Modal, Popconfirm, Select, Space, Table, Tabs, Tag } from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { AdminUser, AuditFilters, AuditLog, AuthUser, createUser, Customer, fetchAuditLogs, fetchUsers, fetchUserScope, saveUserScope, setUserStatus } from '../api'
import { formatTime, QueryFeedback } from './QueryFeedback'

export function AdminCenter({ customers }: { customers: Customer[] }) {
  return <Tabs items={[{ key: 'users', label: '账号与客户范围', children: <UserAdmin customers={customers} /> }, { key: 'audit', label: '操作审计', children: <AuditAdmin /> }]} />
}

function UserAdmin({ customers }: { customers: Customer[] }) {
  const client = useQueryClient()
  const users = useQuery({ queryKey: ['users'], queryFn: fetchUsers, retry: false })
  const [createOpen, setCreateOpen] = useState(false)
  const [selected, setSelected] = useState<AdminUser>()
  const [form] = Form.useForm<AuthUser & { password: string }>()
  const [success, setSuccess] = useState('')
  const refresh = async () => { await Promise.all(['users', 'audit-logs'].map(key => client.invalidateQueries({ queryKey: [key] }))) }
  const create = useMutation({ mutationFn: createUser, onSuccess: async () => { setCreateOpen(false); form.resetFields(); setSuccess('账号已创建'); await refresh() } })
  const status = useMutation({ mutationFn: ({ id, enabled }: { id: number; enabled: boolean }) => setUserStatus(id, enabled), onSuccess: async () => { setSuccess('账号状态已更新'); await refresh() } })
  return <>
    <QueryFeedback error={users.error} retry={() => users.refetch()} />
    {status.error && <Alert style={{ marginBottom: 16 }} type="error" showIcon message={status.error.message} />}
    {success && <Alert style={{ marginBottom: 16 }} type="success" showIcon message={success} />}
    <Card title="账号管理" extra={<Space><Button icon={<ReloadOutlined />} loading={users.isFetching} onClick={() => users.refetch()}>刷新</Button><Button type="primary" icon={<PlusOutlined />} onClick={() => { create.reset(); setCreateOpen(true) }}>创建账号</Button></Space>}>
      <Table<AdminUser> rowKey="id" loading={users.isFetching} dataSource={users.isError ? [] : users.data ?? []} scroll={{ x: 800 }} pagination={{ pageSize: 10 }} columns={[
        { title: '账号', dataIndex: 'username' }, { title: '姓名', dataIndex: 'realName' }, { title: '角色', dataIndex: 'role', render: role => role === 'ADMIN' ? '管理员' : '客户经理' },
        { title: '状态', dataIndex: 'enabled', render: enabled => <Tag color={enabled ? 'success' : 'default'}>{enabled ? '启用' : '停用'}</Tag> },
        { title: '操作', render: (_, user) => <Space>{user.role === 'CUSTOMER_MANAGER' && <Button type="link" onClick={() => setSelected(user)}>客户范围</Button>}<Popconfirm title={`确认${user.enabled ? '停用' : '启用'} ${user.username}？`} onConfirm={() => status.mutateAsync({ id: user.id, enabled: !user.enabled })}><Button type="link" danger={user.enabled} disabled={status.isPending}>{user.enabled ? '停用' : '启用'}</Button></Popconfirm></Space> },
      ]} />
    </Card>
    <Modal title="创建账号" open={createOpen} onCancel={() => { setCreateOpen(false); form.resetFields() }} onOk={() => form.submit()} confirmLoading={create.isPending}>
      {create.error && <Alert type="error" showIcon message={create.error.message} />}
      <Form form={form} layout="vertical" requiredMark={false} initialValues={{ role: 'CUSTOMER_MANAGER' }} onFinish={values => create.mutate({ ...values, username: values.username.trim(), realName: values.realName.trim() })}>
        <Form.Item name="username" label="账号" rules={[{ required: true, whitespace: true, message: '请输入账号' }]}><Input maxLength={100} autoComplete="off" /></Form.Item>
        <Form.Item name="realName" label="姓名" rules={[{ required: true, whitespace: true, message: '请输入姓名' }]}><Input maxLength={100} /></Form.Item>
        <Form.Item name="password" label="初始密码" rules={[{ required: true, whitespace: true, message: '请输入初始密码' }, { min: 6, message: '密码至少 6 位' }]}><Input.Password autoComplete="new-password" /></Form.Item>
        <Form.Item name="role" label="角色" rules={[{ required: true }]}><Select options={[{ value: 'CUSTOMER_MANAGER', label: '客户经理' }, { value: 'ADMIN', label: '管理员' }]} /></Form.Item>
      </Form>
    </Modal>
    {selected && <ScopeModal user={selected} customers={customers} onClose={() => setSelected(undefined)} />}
  </>
}

function ScopeModal({ user, customers, onClose }: { user: AdminUser; customers: Customer[]; onClose: () => void }) {
  const client = useQueryClient()
  const scope = useQuery({ queryKey: ['user-scope', user.id], queryFn: () => fetchUserScope(user.id), retry: false })
  const [selection, setSelection] = useState<number[]>()
  const mutation = useMutation({
    mutationFn: () => saveUserScope(user.id, selection ?? scope.data!.customerIds),
    onSuccess: async () => { await Promise.all(['user-scope', 'audit-logs', 'users'].map(key => client.invalidateQueries({ queryKey: [key] }))); onClose() },
  })
  const options = customers.map(customer => ({ value: customer.id, label: `${customer.customerCode ?? ''} ${customer.name}` }))
  // Retain existing assignments even if the current customer list omits them.
  for (const id of scope.data?.customerIds ?? []) if (!options.some(option => option.value === id)) options.push({ value: id, label: `客户 #${id}` })
  return <Modal title={`${user.realName} · 客户范围`} open onCancel={onClose} onOk={() => mutation.mutate()} confirmLoading={mutation.isPending} okButtonProps={{ disabled: !scope.data || scope.isError || scope.isFetching }}>
    <QueryFeedback error={scope.error} retry={() => scope.refetch()} />
    {mutation.error && <Alert type="error" showIcon message={mutation.error.message} />}
    <Form layout="vertical" requiredMark={false}><Form.Item label="授权客户"><Select aria-label="授权客户" mode="multiple" showSearch optionFilterProp="label" loading={scope.isFetching} disabled={!scope.data || scope.isError} value={selection ?? scope.data?.customerIds ?? []} options={options} onChange={setSelection} /></Form.Item></Form>
  </Modal>
}

function AuditAdmin() {
  const [page, setPage] = useState(1)
  const [filters, setFilters] = useState<AuditFilters>({})
  const [form] = Form.useForm<AuditFilters>()
  const logs = useQuery({ queryKey: ['audit-logs', page, filters], queryFn: () => fetchAuditLogs(page, 15, filters), retry: false })
  return <><QueryFeedback error={logs.error} retry={() => logs.refetch()} /><Card title="操作审计" extra={<Button icon={<ReloadOutlined />} loading={logs.isFetching} onClick={() => logs.refetch()}>刷新</Button>}>
    <Form form={form} layout="inline" requiredMark={false} style={{ gap: 12, marginBottom: 16 }} onFinish={values => { setPage(1); setFilters(values) }}>
      <Form.Item name="operatorUserId" label="操作者编号"><InputNumber min={1} precision={0} /></Form.Item>
      <Form.Item name="operationType" label="操作"><Input allowClear /></Form.Item>
      <Form.Item name="targetType" label="目标类型"><Input allowClear /></Form.Item>
      <Form.Item name="targetId" label="目标编号"><InputNumber min={1} precision={0} /></Form.Item>
      <Form.Item name="result" label="结果"><Select allowClear style={{ width: 120 }} options={['SUCCESS', 'FAILED', 'DENIED'].map(value => ({ value, label: value }))} /></Form.Item>
      <Form.Item name="from" label="开始时间"><Input type="datetime-local" /></Form.Item>
      <Form.Item name="to" label="结束时间" dependencies={['from']} rules={[({ getFieldValue }) => ({ validator(_, value) { return value && getFieldValue('from') && value < getFieldValue('from') ? Promise.reject(new Error('结束时间不能早于开始时间')) : Promise.resolve() } })]}><Input type="datetime-local" /></Form.Item>
      <Button type="primary" htmlType="submit">查询</Button><Button onClick={() => { form.resetFields(); setFilters({}); setPage(1) }}>重置</Button>
    </Form>
    <Table<AuditLog> rowKey="id" loading={logs.isFetching} dataSource={logs.isError ? [] : logs.data?.items ?? []} scroll={{ x: 1100 }} pagination={{ pageSize: 15, current: page, total: logs.data?.total ?? 0, showSizeChanger: false, onChange: setPage }} columns={[
      { title: '时间', dataIndex: 'createdAt', render: formatTime }, { title: '操作者', render: (_, log) => log.operatorName ?? log.operatorUsername ?? '—' },
      { title: '操作', dataIndex: 'operationType' }, { title: '目标类型', dataIndex: 'targetType' }, { title: '目标编号', dataIndex: 'targetId' },
      { title: '结果', dataIndex: 'result', render: value => <Tag color={value === 'SUCCESS' ? 'success' : 'error'}>{value}</Tag> }, { title: '详情', dataIndex: 'detail', render: value => <span style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{value}</span> },
    ]} />
  </Card></>
}
