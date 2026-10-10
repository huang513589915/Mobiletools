import { useQuery } from '@tanstack/react-query'
import { Alert, Button, Card, Layout, Menu, Space, Statistic, Tag, Typography } from 'antd'
import {
  AlertOutlined,
  BarChartOutlined,
  CheckCircleOutlined,
  DashboardOutlined,
  DatabaseOutlined,
  FileSearchOutlined,
  SettingOutlined,
  SolutionOutlined,
} from '@ant-design/icons'
import { fetchHealth } from './api'

const { Header, Sider, Content } = Layout

const menuItems = [
  { key: 'dashboard', icon: <DashboardOutlined />, label: '风险工作台' },
  { key: 'ledger', icon: <SolutionOutlined />, label: '客户统一台账' },
  { key: 'tasks', icon: <CheckCircleOutlined />, label: '任务中心' },
  { key: 'import', icon: <DatabaseOutlined />, label: '数据接入' },
  { key: 'issues', icon: <FileSearchOutlined />, label: '异常核查' },
  { key: 'rules', icon: <AlertOutlined />, label: '预警规则' },
  { key: 'reports', icon: <BarChartOutlined />, label: '统计报表' },
  { key: 'settings', icon: <SettingOutlined />, label: '系统设置' },
]

function App() {
  const health = useQuery({
    queryKey: ['health'],
    queryFn: fetchHealth,
    retry: false,
  })

  return (
    <Layout className="app-shell">
      <Sider breakpoint="lg" collapsedWidth="0" className="app-sider">
        <div className="brand">
          <div className="brand-mark">企</div>
          <div>
            <strong>政企客户管理</strong>
            <span>风险与到期工作台</span>
          </div>
        </div>
        <Menu theme="dark" mode="inline" defaultSelectedKeys={['dashboard']} items={menuItems} />
      </Sider>
      <Layout>
        <Header className="app-header">
          <div>
            <Typography.Text className="eyebrow">运营总览</Typography.Text>
            <Typography.Title level={2}>风险工作台</Typography.Title>
          </div>
          <Space>
            <Tag color={health.isSuccess ? 'success' : 'warning'}>
              {health.isSuccess ? '后端已连接' : '等待后端'}
            </Tag>
            <Button type="primary">今日待办 12</Button>
          </Space>
        </Header>
        <Content className="app-content">
          {!health.isSuccess && (
            <Alert
              showIcon
              type="info"
              message="当前为项目骨架"
              description="后端健康检查未连接时，页面仍可用于确认导航和信息布局。"
              action={<Button size="small" onClick={() => health.refetch()}>重试</Button>}
              className="health-alert"
            />
          )}
          <div className="page-intro">
            <div>
              <Typography.Text type="secondary">2026 年 10 月 9 日 · 数据快照待接入</Typography.Text>
              <Typography.Title level={3}>今天需要优先处理的事项</Typography.Title>
            </div>
            <Button>查看全部任务</Button>
          </div>
          <div className="stat-grid">
            <Card><Statistic title="高风险客户" value={18} suffix="户" valueStyle={{ color: '#b42318' }} /></Card>
            <Card><Statistic title="待处理任务" value={42} suffix="项" valueStyle={{ color: '#b54708' }} /></Card>
            <Card><Statistic title="本月到期专线" value={27} suffix="条" valueStyle={{ color: '#0f766e' }} /></Card>
            <Card><Statistic title="数据异常" value={6} suffix="项" valueStyle={{ color: '#475467' }} /></Card>
          </div>
          <div className="content-grid">
            <Card title="重点风险客户" extra={<Button type="link">查看台账</Button>}>
              <div className="risk-list">
                {[
                  ['华东智联科技有限公司', '欠费 38,600 元 · 已逾期 12 天', '高风险'],
                  ['城市建设集团', '专线将在 7 天后到期', '待跟进'],
                  ['新城物业服务有限公司', '余额低于预警阈值', '需核实'],
                ].map(([name, detail, status]) => (
                  <div className="risk-row" key={name}>
                    <div>
                      <strong>{name}</strong>
                      <span>{detail}</span>
                    </div>
                    <Tag color={status === '高风险' ? 'error' : status === '待跟进' ? 'warning' : 'processing'}>{status}</Tag>
                  </div>
                ))}
              </div>
            </Card>
            <Card title="任务处理概况" extra={<Button type="link">查看报表</Button>}>
              <div className="progress-summary">
                <div className="progress-number">68%</div>
                <div>
                  <strong>本周任务按时完成率</strong>
                  <p>较上周提升 8%，仍有 5 项任务临近截止。</p>
                </div>
              </div>
              <div className="progress-track"><span /></div>
            </Card>
          </div>
        </Content>
      </Layout>
    </Layout>
  )
}

export default App
