import { useEffect, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Breadcrumb, Button, Card, Form, Input, Layout, Menu, Space, Tag, Typography } from 'antd'
import { AccountBookOutlined, CalendarOutlined, CloudUploadOutlined, FileSearchOutlined, LockOutlined, LogoutOutlined, TeamOutlined, UserOutlined } from '@ant-design/icons'
import { AuthUser, fetchCurrentUser, fetchHealth, signIn, signOut } from './api'
import { AdminCenter } from './components/AdminCenter'
import { AssignmentExceptionCenter } from './components/AssignmentExceptionCenter'
import { ArrearsLedgerPage } from './components/ArrearsLedgerPage'
import { LineExpiryLedgerPage } from './components/LineExpiryLedgerPage'
import { LedgerImportBatchPage } from './components/LedgerImportBatchPage'

const { Header, Sider, Content } = Layout

const menuItems = [
  { key: 'arrears-ledger', icon: <AccountBookOutlined />, label: '欠费明细' },
  { key: 'line-expiry-ledger', icon: <CalendarOutlined />, label: '专线到期明细' },
  { key: 'import', icon: <CloudUploadOutlined />, label: '导入批次' },
  { key: 'assignment-exceptions', icon: <FileSearchOutlined />, label: '归属匹配异常' },
  { key: 'settings', icon: <TeamOutlined />, label: '账号管理' },
]

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
      <Card className="login-panel" bordered={false}>
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
            <Input autoComplete="username" prefix={<UserOutlined />} placeholder="请输入账号" />
          </Form.Item>
          <Form.Item label="密码" name="password" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password autoComplete="current-password" prefix={<LockOutlined />} placeholder="请输入密码" />
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={loading} block>登录系统</Button>
        </Form>
      </Card>
    </main>
  )
}

function App() {
  const [selectedKey, setSelectedKey] = useState('arrears-ledger')
  const [authBusy, setAuthBusy] = useState(false)
  const [authError, setAuthError] = useState('')
  const queryClient = useQueryClient()

  useEffect(() => {
    const expired = () => {
      void queryClient.cancelQueries()
      queryClient.setQueryData(['auth'], null)
      queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== 'auth' })
      setSelectedKey('arrears-ledger')
    }
    window.addEventListener('session-expired', expired)
    return () => window.removeEventListener('session-expired', expired)
  }, [queryClient])

  const health = useQuery({ queryKey: ['health'], queryFn: fetchHealth, retry: false })
  const auth = useQuery<AuthUser | null>({ queryKey: ['auth'], queryFn: fetchCurrentUser, retry: false })
  const isAdmin = auth.data?.role === 'ADMIN'
  const current = menuItems.find((item) => item.key === selectedKey) ?? menuItems[0]
  const visibleMenuItems = isAdmin
    ? menuItems
    : menuItems.filter((item) => ['arrears-ledger', 'line-expiry-ledger'].includes(item.key))

  const handleLogin = async (values: { username: string; password: string }) => {
    setAuthBusy(true)
    setAuthError('')
    try {
      await signIn(values.username, values.password)
      await queryClient.invalidateQueries({ queryKey: ['auth'] })
      setSelectedKey('arrears-ledger')
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
      setSelectedKey('arrears-ledger')
    } catch (error) {
      setAuthError(error instanceof Error ? error.message : '退出登录失败')
    }
  }

  const renderPage = () => {
    if (selectedKey === 'arrears-ledger') return <ArrearsLedgerPage isAdmin={isAdmin} />
    if (selectedKey === 'line-expiry-ledger') return <LineExpiryLedgerPage isAdmin={isAdmin} />
    if (selectedKey === 'import' && isAdmin) return <LedgerImportBatchPage />
    if (selectedKey === 'assignment-exceptions' && isAdmin) return <AssignmentExceptionCenter />
    if (selectedKey === 'settings' && isAdmin) return <AdminCenter />
    return <Card title={current.label} />
  }

  if (auth.isLoading) {
    return <main className="login-shell"><Typography.Text>{'正在检查登录状态…'}</Typography.Text></main>
  }

  if (!auth.data) {
    return <LoginScreen loading={authBusy} error={authError || (auth.isError ? '无法连接后端服务，请确认服务已启动。' : '')} onSubmit={handleLogin} />
  }

  return <Layout className="app-shell">
    <Sider width={252} breakpoint="lg" collapsedWidth="0" className="app-sider">
      <div className="brand"><div className="brand-mark">{'企'}</div><div><strong>{'政企客户管理'}</strong><span>{'集团账务与专线运营'}</span></div></div>
      <div className="sider-caption">工作空间</div>
      <Menu theme="light" mode="inline" selectedKeys={[selectedKey]} items={visibleMenuItems} onClick={({ key }) => setSelectedKey(key)} />
      <div className="sider-footer"><span className="sider-footer-dot" />业务数据工作台</div>
    </Sider>
    <Layout>
      <Header className="app-header">
        <div className="header-copy">
          <Breadcrumb className="header-breadcrumb" items={[{ title: '工作台' }, { title: current.label }]} />
        </div>
        <Space className="header-actions" size={14} wrap>
          <Tag className={`connection-tag ${health.isSuccess ? 'connection-online' : 'connection-waiting'}`}><span className="connection-dot" />{health.isSuccess ? '服务正常' : '等待连接'}</Tag>
          <div className="user-chip">
            <span className="user-avatar"><UserOutlined /></span>
            <span className="user-copy"><strong>{auth.data.realName}</strong><small>{auth.data.username}</small></span>
            <span className={`user-role${isAdmin ? ' user-role-admin' : ''}`}>{isAdmin ? '管理员' : '客户经理'}</span>
          </div>
          <Button className="logout-button" icon={<LogoutOutlined />} onClick={handleLogout}>{'退出登录'}</Button>
        </Space>
      </Header>
      <Content className="app-content">
        {!health.isSuccess && <Alert showIcon type="warning" message="后端服务不可用" description="当前页面无法加载业务数据，请启动后端服务后重试。" action={<Button size="small" onClick={() => health.refetch()}>{'重试'}</Button>} className="health-alert" />}
        {renderPage()}
      </Content>
    </Layout>
  </Layout>
}

export default App
