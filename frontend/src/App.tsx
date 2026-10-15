import { useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Alert, Button, Card, DatePicker, Empty, Form, Input, Layout, Menu, Modal,
  Select, Space, Statistic, Table, Tag, Typography, message,
} from 'antd'
import {
  AlertOutlined, BarChartOutlined, CheckCircleOutlined, DashboardOutlined,
  DatabaseOutlined, FileSearchOutlined, LogoutOutlined, PlusOutlined, ReloadOutlined,
  SettingOutlined, SolutionOutlined,
} from '@ant-design/icons'
import { AuthUser, Customer, fetchCurrentUser, fetchCustomers, fetchHealth, fetchImportBatches, fetchTasks, fetchWorkbenchSummary, fetchServices, fetchBills, fetchExpiries, fetchExceptions, fetchBatchExceptions, fetchImportRows, fetchRiskResults, previewImport, confirmImport, createTask, signIn, signOut } from './api'
import { AdminCenter } from './components/AdminCenter'
import { RiskCenter, RuleAdmin } from './components/RiskCenter'
import { TaskCenter } from './components/TaskCenter'
import { ImportCenter } from './components/ImportCenter'
import { IssueCenter } from './components/IssueCenter'
import { Workbench } from './components/Workbench'
import { CustomerLedger } from './components/CustomerLedger'

const { Header, Sider, Content } = Layout

const menuItems = [
  { key: 'dashboard', icon: <DashboardOutlined />, label: '风险工作台' },
  { key: 'ledger', icon: <SolutionOutlined />, label: '客户统一台账' },
  { key: 'tasks', icon: <CheckCircleOutlined />, label: '任务中心' },
  { key: 'import', icon: <DatabaseOutlined />, label: '数据接入' },
  { key: 'issues', icon: <FileSearchOutlined />, label: '异常核查' },
  { key: 'risk', icon: <AlertOutlined />, label: '风险结果' },
  { key: 'rules', icon: <SettingOutlined />, label: '预警规则' },
  { key: 'reports', icon: <BarChartOutlined />, label: '统计报表' },
  { key: 'settings', icon: <SettingOutlined />, label: '系统设置' },
]

const statusColor: Record<string, string> = {
  高风险: 'error', 待跟进: 'warning', 需核实: 'processing', 正常: 'success',
  待处理: 'error', 进行中: 'processing', 已完成: 'success',
}

function PageToolbar({ action, onAction = () => undefined }: { action?: string; onAction?: () => void }) {
  return <div className="page-toolbar">
    <Space wrap>
      <Input placeholder="搜索客户、任务或负责人" allowClear style={{ width: 250 }} />
      <Select defaultValue="全部状态" options={['全部状态', '高风险', '待跟进', '需核实', '正常'].map((value) => ({ value, label: value }))} style={{ width: 120 }} />
      <DatePicker placeholder="选择日期" />
    </Space>
    {action && <Button type="primary" icon={<PlusOutlined />} onClick={onAction}>{action}</Button>}
  </div>
}

function Dashboard({ customers, summary, onNavigate }: { customers: Customer[]; summary?: { highRiskCustomers: number; pendingTasks: number; overdueTasks: number; expiringServices: number; openExceptions: number }; onNavigate: (key: string) => void }) {
  return <>
    <div className="page-intro">
      <div><Typography.Text type="secondary">真实业务数据 · 服务端实时统计</Typography.Text><Typography.Title level={3}>今天需要优先处理的事项</Typography.Title></div>
      <Button onClick={() => onNavigate('tasks')}>查看全部任务</Button>
    </div>
    <div className="stat-grid">
      <Card><Statistic title="高风险客户" value={summary?.highRiskCustomers ?? 0} suffix="户" valueStyle={{ color: '#b42318' }} /></Card>
      <Card><Statistic title="待处理任务" value={summary?.pendingTasks ?? 0} suffix="项" valueStyle={{ color: '#b54708' }} /></Card>
      <Card><Statistic title="30天内到期专线" value={summary?.expiringServices ?? 0} suffix="条" valueStyle={{ color: '#0f766e' }} /></Card>
      <Card><Statistic title="待核查异常" value={summary?.openExceptions ?? 0} suffix="项" valueStyle={{ color: '#475467' }} /></Card>
    </div>
    <div className="content-grid">
      <Card title="重点风险客户" extra={<Button type="link" onClick={() => onNavigate('ledger')}>查看台账</Button>}>
        <div className="risk-list">{customers.slice(0, 3).map((item) => <div className="risk-row" key={item.id}><div><strong>{item.name}</strong><span>¥{item.balance.toLocaleString()} · {item.due}</span></div><Tag color={statusColor[item.status]}>{item.status}</Tag></div>)}</div>
      </Card>
      <Card title="任务处理概况" extra={<Button type="link" onClick={() => onNavigate('tasks')}>打开任务中心</Button>}>
        <Tag color={summary?.overdueTasks ? 'error' : 'default'}>逾期任务 {summary?.overdueTasks ?? 0} 项</Tag>
        <div className="progress-summary"><div className="progress-number">{summary?.pendingTasks ?? 0}</div><div><strong>当前待处理任务</strong><p>统计范围按当前账号授权客户计算。</p></div></div>
      </Card>
    </div>
  </>
}

function LoginScreen({
  loading,
  error,
  onSubmit,
}: {
  loading: boolean
  error: string
  onSubmit: (values: { username: string; password: string }) => void
}) {
  return (
    <main className="login-shell">
      <Card className="login-panel">
        <div className="login-brand">
          <div className="brand-mark">企</div>
          <div>
            <Typography.Title level={3}>政企客户管理</Typography.Title>
            <Typography.Text type="secondary">风险与到期工作台</Typography.Text>
          </div>
        </div>
        <Typography.Title level={4}>登录</Typography.Title>
        {error && <Alert className="login-error" type="error" showIcon message={error} />}
        <Form layout="vertical" onFinish={onSubmit} requiredMark={false}>
          <Form.Item label="账号" name="username" rules={[{ required: true, message: '请输入账号' }]}>
            <Input autoComplete="username" placeholder="请输入账号" />
          </Form.Item>
          <Form.Item label="密码" name="password" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password autoComplete="current-password" placeholder="请输入密码" />
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={loading} block>登录系统</Button>
        </Form>
      </Card>
    </main>
  )
}

function App() {
  const [selectedKey, setSelectedKey] = useState('dashboard')
  const [taskInitialOverdue, setTaskInitialOverdue] = useState<boolean>()
  const [taskInitialUnfinished, setTaskInitialUnfinished] = useState(false)
  const [pageContext, setPageContext] = useState<Record<string, string | number>>({})
  const [modalOpen, setModalOpen] = useState(false)
  const [taskModalOpen, setTaskModalOpen] = useState(false)
  const [authBusy, setAuthBusy] = useState(false)
  const [authError, setAuthError] = useState('')
  const [taskBusy, setTaskBusy] = useState(false)
  const [taskForm] = Form.useForm()
  const taskCustomerId = Form.useWatch('customerId', taskForm)
  const taskSourceType = Form.useWatch('sourceType', taskForm)
  const [messageApi, contextHolder] = message.useMessage()
  const queryClient = useQueryClient()
  useEffect(() => {
    const expired = () => {
      void queryClient.cancelQueries()
      queryClient.setQueryData(['auth'], null)
      queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== 'auth' })
      setSelectedKey('dashboard')
      setModalOpen(false)
      setTaskModalOpen(false)
      Modal.destroyAll()
    }
    window.addEventListener('session-expired', expired)
    return () => window.removeEventListener('session-expired', expired)
  }, [queryClient])
  const health = useQuery({ queryKey: ['health'], queryFn: fetchHealth, retry: false })
  const auth = useQuery<AuthUser | null>({ queryKey: ['auth'], queryFn: fetchCurrentUser, retry: false })
  const customersQuery = useQuery({ queryKey: ['customers'], queryFn: fetchCustomers, retry: false, enabled: Boolean(auth.data) })
  const tasksQuery = useQuery({ queryKey: ['tasks'], queryFn: () => fetchTasks(), retry: false, enabled: Boolean(auth.data) })
  const summaryQuery = useQuery({ queryKey: ['workbench-summary'], queryFn: fetchWorkbenchSummary, retry: false, enabled: Boolean(auth.data) })
  const importsQuery = useQuery({ queryKey: ['imports'], queryFn: fetchImportBatches, retry: false, enabled: auth.data?.role === 'ADMIN' })
  const servicesQuery = useQuery({ queryKey: ['services'], queryFn: fetchServices, retry: false, enabled: Boolean(auth.data) })
  const billsQuery = useQuery({ queryKey: ['bills'], queryFn: fetchBills, retry: false, enabled: Boolean(auth.data) })
  const expiriesQuery = useQuery({ queryKey: ['expiries'], queryFn: fetchExpiries, retry: false, enabled: Boolean(auth.data) })
  const exceptionsQuery = useQuery({ queryKey: ['exceptions'], queryFn: () => fetchExceptions(), retry: false, enabled: Boolean(auth.data) })
  const riskResultsQuery = useQuery({ queryKey: ['risk-results-ledger'], queryFn: () => fetchRiskResults(), retry: false, enabled: Boolean(auth.data) })
  const issueDetailQuery = useQuery({ queryKey: ['exception-batch', pageContext.batchId], queryFn: () => fetchBatchExceptions(Number(pageContext.batchId)), retry: false, enabled: Boolean(auth.data && selectedKey === 'issues' && pageContext.batchId) })
  const batchRowsQuery = useQuery({ queryKey: ['import-rows', pageContext.batchId], queryFn: () => fetchImportRows(Number(pageContext.batchId)), retry: false, enabled: Boolean(auth.data && selectedKey === 'import' && pageContext.batchId) })
  const current = menuItems.find((item) => item.key === selectedKey) ?? menuItems[0]
  const visibleMenuItems = auth.data?.role === 'ADMIN'
    ? menuItems
    : menuItems.filter((item) => !['import', 'rules', 'settings'].includes(item.key))
  const showAction = (text: string) => messageApi.success(text)
  const customers = customersQuery.data ?? []
  const imports = importsQuery.data ?? []
  const services = servicesQuery.data ?? []
  const bills = billsQuery.data ?? []
  const expiries = expiriesQuery.data ?? []
  const exceptions = exceptionsQuery.data ?? []
  const fileInputRef = useRef<HTMLInputElement>(null)
  const isAdmin = auth.data?.role === 'ADMIN'

  const handleLogin = async (values: { username: string; password: string }) => {
    setAuthBusy(true)
    setAuthError('')
    try {
      await signIn(values.username, values.password)
      await queryClient.invalidateQueries({ queryKey: ['auth'] })
    } catch (error) {
      setAuthError(error instanceof Error ? error.message : '登录失败')
    } finally {
      setAuthBusy(false)
    }
  }

  const handleLogout = async () => {
    try {
      await signOut()
      await queryClient.cancelQueries()
      queryClient.setQueryData(['auth'], null)
      queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== 'auth' })
      setSelectedKey('dashboard')
      setModalOpen(false)
      setTaskModalOpen(false)
      taskForm.resetFields()
      Modal.destroyAll()
      messageApi.success('已退出登录')
    } catch (error) {
      messageApi.error(error instanceof Error ? error.message : '退出登录失败')
    }
  }

  const submitTask = async () => {
    try {
      const values = await taskForm.validateFields()
      setTaskBusy(true)
      const result = await createTask({
        title: values.title,
        deadline: values.deadline,
        customerId: Number(values.customerId),
        sourceType: values.sourceType ?? 'CUSTOMER',
        sourceId: values.sourceType && values.sourceType !== 'CUSTOMER' ? Number(values.sourceId) : Number(values.customerId),
      })
      await Promise.all(['tasks', 'workbench-summary'].map(key => queryClient.invalidateQueries({ queryKey: [key] })))
      setTaskModalOpen(false)
      taskForm.resetFields()
      messageApi.success(result.message)
    } catch (error) {
      if (error instanceof Error) messageApi.error(error.message)
    } finally {
      setTaskBusy(false)
    }
  }

  const renderPage = () => {
    if (selectedKey === 'dashboard') return <Workbench customers={customers} onTasks={overdue => { setTaskInitialOverdue(overdue); setTaskInitialUnfinished(true); setSelectedKey('tasks') }} onNavigate={(key, context = {}) => { setPageContext(context); setSelectedKey(key) }} />
    if (selectedKey === 'ledger') return <CustomerLedger context={pageContext} onClearContext={()=>setPageContext({})} />
    if (selectedKey === 'tasks') return <TaskCenter key={String(taskInitialOverdue) + taskInitialUnfinished} initialUnfinished={taskInitialUnfinished} initialOverdue={taskInitialOverdue} onCreate={() => setTaskModalOpen(true)} />
    if (selectedKey === 'import') return <ImportCenter initialBatchId={pageContext.batchId ? Number(pageContext.batchId) : undefined} initialContext={pageContext} admin={isAdmin} />
    if (selectedKey === 'issues') return <IssueCenter initialContext={pageContext} onNavigate={(key, context = {}) => { setPageContext(context); setSelectedKey(key) }} />
    if (selectedKey === 'risk') return <RiskCenter customers={customers} admin={isAdmin} />
    if (selectedKey === 'rules' && isAdmin) return <RuleAdmin />
    if (selectedKey === 'settings' && isAdmin) return <AdminCenter customers={customers} />
    if (selectedKey === 'reports') return <Card title="运营统计"><Empty description="当前版本暂未提供正式统计报表" /></Card>
    return <Card title={current.label}><Empty description="当前账号暂无可用的管理操作" /></Card>
  }

  if (auth.isLoading) {
    return <main className="login-shell"><Typography.Text>正在检查登录状态…</Typography.Text></main>
  }

  if (!auth.data) {
    return <>
      {contextHolder}
      <LoginScreen loading={authBusy} error={authError || (auth.isError ? '无法连接后端服务，请确认服务已启动。' : '')} onSubmit={handleLogin} />
    </>
  }

  return <Layout className="app-shell">
    {contextHolder}
    <Sider breakpoint="lg" collapsedWidth="0" className="app-sider">
      <div className="brand"><div className="brand-mark">企</div><div><strong>政企客户管理</strong><span>风险与到期工作台</span></div></div>
      <Menu theme="dark" mode="inline" selectedKeys={[selectedKey]} items={visibleMenuItems} onClick={({ key }) => setSelectedKey(key)} />
    </Sider>
    <Layout>
      <Header className="app-header"><div><Typography.Text className="eyebrow">运营总览</Typography.Text><Typography.Title level={2}>{current.label}</Typography.Title></div><Space><Tag color={health.isSuccess ? 'success' : 'warning'}>{health.isSuccess ? '后端已连接' : '等待后端'}</Tag><Typography.Text>{auth.data.realName} · {auth.data.role === 'ADMIN' ? '管理员' : '客户经理'}</Typography.Text><Button icon={<LogoutOutlined />} onClick={handleLogout}>退出</Button><Button type="primary" onClick={() => setSelectedKey('tasks')}>任务中心</Button></Space></Header>
      <Content className="app-content">
        {!health.isSuccess && <Alert showIcon type="warning" message="后端服务不可用" description="当前页面不会使用演示数据，请启动后端服务后重试。" action={<Button size="small" onClick={() => health.refetch()}>重试</Button>} className="health-alert" />}
        {renderPage()}
      </Content>
    </Layout>
    <Modal title="新建跟进任务" open={taskModalOpen} onCancel={() => setTaskModalOpen(false)} onOk={submitTask} confirmLoading={taskBusy} okText="创建任务">
      <Form form={taskForm} layout="vertical" requiredMark={false}>
        <Form.Item name="title" label="任务标题" rules={[{ required: true, message: '请输入任务标题' }]}><Input maxLength={300} /></Form.Item>
        <Form.Item name="deadline" label="计划完成日" rules={[{ required: true, message: '请选择计划完成日' }]}><Input type="date" /></Form.Item>
        <Form.Item name="customerId" label="客户" rules={[{ required: true, message: '请选择客户' }]}>
          <Select onChange={() => taskForm.setFieldsValue({ sourceId: undefined })} options={customers.map((customer) => ({ value: customer.id, label: `${customer.customerCode} ${customer.name}` }))} />
        </Form.Item>
        <Form.Item name="sourceType" label="任务来源" initialValue="CUSTOMER"><Select onChange={() => taskForm.setFieldsValue({ sourceId: undefined })} options={[{ value: 'CUSTOMER', label: '客户事项' }, { value: 'BUSINESS_SERVICE', label: '业务专线' }, { value: 'ARREARS_BILL', label: '欠费账单' }, { value: 'CONTRACT_EXPIRY', label: '业务到期' }]} /></Form.Item>
        {taskSourceType && taskSourceType !== 'CUSTOMER' && <Form.Item name="sourceId" label="来源记录" rules={[{ required: true, message: '请选择来源记录' }]}><Select disabled={!taskCustomerId} options={(taskSourceType === 'BUSINESS_SERVICE' ? services : taskSourceType === 'ARREARS_BILL' ? bills : expiries).filter(item => item.customerCode === customers.find(c => c.id === taskCustomerId)?.customerCode).map(item => ({ value: item.id, label: 'billCode' in item ? item.billCode : `${item.serviceCode ?? ''}${'expiryDate' in item ? ' · ' + item.expiryDate : ''}` }))} /></Form.Item>}
      </Form>
    </Modal>
    <Modal title="客户详情" open={modalOpen} onCancel={() => setModalOpen(false)} footer={<Button type="primary" onClick={() => { setModalOpen(false); showAction('跟进任务创建') }}>创建跟进任务</Button>}><Typography.Paragraph>客户详情以当前客户台账、欠费账单、业务专线和到期记录为准。请从对应业务页面处理任务。</Typography.Paragraph></Modal>
  </Layout>
}

export default App
