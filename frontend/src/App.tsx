import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  Alert, Button, Card, DatePicker, Empty, Input, Layout, Menu, Modal,
  Progress, Select, Space, Statistic, Table, Tag, Typography, message,
} from 'antd'
import {
  AlertOutlined, BarChartOutlined, CheckCircleOutlined, DashboardOutlined,
  DatabaseOutlined, FileSearchOutlined, PlusOutlined, ReloadOutlined,
  SettingOutlined, SolutionOutlined,
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

const customers = [
  { key: '1', name: '华东智联科技有限公司', manager: '李晓敏', balance: '¥38,600', status: '高风险', due: '已逾期 12 天', type: '专线' },
  { key: '2', name: '城市建设集团', manager: '王芳', balance: '¥12,800', status: '待跟进', due: '7 天后到期', type: '集团专线' },
  { key: '3', name: '新城物业服务有限公司', manager: '陈琳', balance: '¥2,300', status: '需核实', due: '余额低于阈值', type: '云业务' },
  { key: '4', name: '远海物流发展有限公司', manager: '赵强', balance: '¥8,900', status: '正常', due: '30 天后到期', type: '专线' },
]

const tasks = [
  { key: '1', title: '联系华东智联科技有限公司催收欠费', owner: '李晓敏', deadline: '2026-10-10', status: '待处理', priority: '高' },
  { key: '2', title: '确认城市建设集团专线续签意向', owner: '王芳', deadline: '2026-10-12', status: '进行中', priority: '中' },
  { key: '3', title: '核实新城物业余额预警准确性', owner: '陈琳', deadline: '2026-10-11', status: '待处理', priority: '高' },
  { key: '4', title: '处理 9 月欠费明细异常记录', owner: '后台台席', deadline: '2026-10-15', status: '已完成', priority: '低' },
]

const statusColor: Record<string, string> = {
  高风险: 'error', 待跟进: 'warning', 需核实: 'processing', 正常: 'success',
  待处理: 'error', 进行中: 'processing', 已完成: 'success',
}

function PageToolbar({ action, onAction }: { action?: string; onAction: () => void }) {
  return <div className="page-toolbar">
    <Space wrap>
      <Input placeholder="搜索客户、任务或负责人" allowClear style={{ width: 250 }} />
      <Select defaultValue="全部状态" options={['全部状态', '高风险', '待跟进', '需核实', '正常'].map((value) => ({ value, label: value }))} style={{ width: 120 }} />
      <DatePicker placeholder="选择日期" />
    </Space>
    {action && <Button type="primary" icon={<PlusOutlined />} onClick={onAction}>{action}</Button>}
  </div>
}

function Dashboard({ onNavigate }: { onNavigate: (key: string) => void }) {
  return <>
    <div className="page-intro">
      <div><Typography.Text type="secondary">2026 年 10 月 10 日 · 数据快照待接入</Typography.Text><Typography.Title level={3}>今天需要优先处理的事项</Typography.Title></div>
      <Button onClick={() => onNavigate('tasks')}>查看全部任务</Button>
    </div>
    <div className="stat-grid">
      <Card><Statistic title="高风险客户" value={18} suffix="户" valueStyle={{ color: '#b42318' }} /></Card>
      <Card><Statistic title="待处理任务" value={42} suffix="项" valueStyle={{ color: '#b54708' }} /></Card>
      <Card><Statistic title="本月到期专线" value={27} suffix="条" valueStyle={{ color: '#0f766e' }} /></Card>
      <Card><Statistic title="数据异常" value={6} suffix="项" valueStyle={{ color: '#475467' }} /></Card>
    </div>
    <div className="content-grid">
      <Card title="重点风险客户" extra={<Button type="link" onClick={() => onNavigate('ledger')}>查看台账</Button>}>
        <div className="risk-list">{customers.slice(0, 3).map((item) => <div className="risk-row" key={item.key}><div><strong>{item.name}</strong><span>{item.balance} · {item.due}</span></div><Tag color={statusColor[item.status]}>{item.status}</Tag></div>)}</div>
      </Card>
      <Card title="任务处理概况" extra={<Button type="link" onClick={() => onNavigate('reports')}>查看报表</Button>}>
        <div className="progress-summary"><div className="progress-number">68%</div><div><strong>本周任务按时完成率</strong><p>较上周提升 8%，仍有 5 项任务临近截止。</p></div></div>
        <Progress percent={68} showInfo={false} strokeColor="#0f766e" />
      </Card>
    </div>
  </>
}

function App() {
  const [selectedKey, setSelectedKey] = useState('dashboard')
  const [modalOpen, setModalOpen] = useState(false)
  const [messageApi, contextHolder] = message.useMessage()
  const health = useQuery({ queryKey: ['health'], queryFn: fetchHealth, retry: false })
  const current = menuItems.find((item) => item.key === selectedKey) ?? menuItems[0]
  const showAction = (text: string) => messageApi.success(`${text}成功`)

  const customerColumns = [
    { title: '客户名称', dataIndex: 'name', render: (value: string) => <strong>{value}</strong> },
    { title: '客户经理', dataIndex: 'manager' },
    { title: '业务类型', dataIndex: 'type' },
    { title: '余额/欠费', dataIndex: 'balance' },
    { title: '风险状态', dataIndex: 'status', render: (value: string) => <Tag color={statusColor[value]}>{value}</Tag> },
    { title: '下一步', dataIndex: 'due' },
    { title: '操作', render: () => <Button type="link" onClick={() => setModalOpen(true)}>查看详情</Button> },
  ]
  const taskColumns = [
    { title: '任务事项', dataIndex: 'title' }, { title: '负责人', dataIndex: 'owner' },
    { title: '截止日期', dataIndex: 'deadline' },
    { title: '优先级', dataIndex: 'priority', render: (value: string) => <Tag color={value === '高' ? 'error' : value === '中' ? 'warning' : 'default'}>{value}</Tag> },
    { title: '状态', dataIndex: 'status', render: (value: string) => <Tag color={statusColor[value]}>{value}</Tag> },
    { title: '操作', render: (_: unknown, record: typeof tasks[number]) => record.status === '已完成' ? <Typography.Text type="secondary">已归档</Typography.Text> : <Button type="link" onClick={() => showAction('任务处理')}>标记完成</Button> },
  ]

  const renderPage = () => {
    if (selectedKey === 'dashboard') return <Dashboard onNavigate={setSelectedKey} />
    if (selectedKey === 'ledger') return <><PageToolbar action="新增客户" onAction={() => showAction('新增客户')} /><Card title="客户统一台账"><Table columns={customerColumns} dataSource={customers} pagination={{ pageSize: 6 }} /></Card></>
    if (selectedKey === 'tasks') return <><PageToolbar action="新建任务" onAction={() => showAction('新建任务')} /><Card title="任务清单"><Table columns={taskColumns} dataSource={tasks} pagination={false} /></Card></>
    if (selectedKey === 'import') return <><PageToolbar action="导入数据" onAction={() => showAction('导入数据')} /><div className="content-grid"><Card title="最近接入记录"><Table pagination={false} columns={[{ title: '文件名称', dataIndex: 'name' }, { title: '接入时间', dataIndex: 'time' }, { title: '状态', dataIndex: 'status', render: (value: string) => <Tag color="success">{value}</Tag> }]} dataSource={[{ key: 1, name: '2026年10月欠费明细.xlsx', time: '今天 08:40', status: '已完成' }, { key: 2, name: '专线到期明细.xlsx', time: '昨天 17:20', status: '已完成' }]} /></Card><Card title="接入说明"><Typography.Paragraph>支持欠费明细、余额快照、专线到期明细等标准表格接入，导入后自动生成待核查任务。</Typography.Paragraph><Button icon={<ReloadOutlined />} onClick={() => showAction('数据校验')}>执行数据校验</Button></Card></div></>
    if (selectedKey === 'issues') return <><PageToolbar action="新建核查单" onAction={() => showAction('新建核查单')} /><Card title="异常核查"><Alert showIcon type="warning" message="当前有 6 条数据待核查" description="系统发现短信预警与业务台账存在不一致，请优先确认客户余额和到期日期。" /><Table className="table-spaced" pagination={false} columns={[{ title: '异常内容', dataIndex: 'issue' }, { title: '来源', dataIndex: 'source' }, { title: '发现时间', dataIndex: 'time' }, { title: '操作', render: () => <Button type="link" onClick={() => showAction('核查记录')}>开始核查</Button> }]} dataSource={[{ key: 1, issue: '短信显示欠费，台账余额正常', source: '短信反馈', time: '今天 08:15' }, { key: 2, issue: '到期日期缺失', source: '专线明细', time: '今天 07:50' }]} /></Card></>
    if (selectedKey === 'rules') return <><PageToolbar action="新增规则" onAction={() => showAction('新增规则')} /><Card title="预警规则"><Table pagination={false} columns={[{ title: '规则名称', dataIndex: 'name' }, { title: '触发条件', dataIndex: 'condition' }, { title: '提醒对象', dataIndex: 'target' }, { title: '状态', dataIndex: 'enabled', render: (value: boolean) => <Tag color={value ? 'success' : 'default'}>{value ? '启用' : '停用'}</Tag> }, { title: '操作', render: () => <Button type="link" onClick={() => showAction('规则编辑')}>编辑</Button> }]} dataSource={[{ key: 1, name: '欠费预警', condition: '欠费金额 > 0', target: '客户经理、后台台席', enabled: true }, { key: 2, name: '到期提醒', condition: '距离到期 <= 30 天', target: '客户经理', enabled: true }, { key: 3, name: '余额不足', condition: '余额低于阈值', target: '客户经理', enabled: false }]} /></Card></>
    if (selectedKey === 'reports') return <><div className="stat-grid"><Card><Statistic title="本月欠费回收率" value={82} suffix="%" /></Card><Card><Statistic title="按时完成率" value={68} suffix="%" /></Card><Card><Statistic title="异常核查完成" value={91} suffix="%" /></Card><Card><Statistic title="客户台账完整度" value={74} suffix="%" /></Card></div><Card title="运营统计"><Empty description="真实统计数据接入后将在这里展示" /><Button onClick={() => showAction('报表导出')}>导出报表</Button></Card></>
    return <Card title={current.label}><Empty description="系统设置功能将在账号、权限和参数配置接入后开放" /><Button onClick={() => showAction('设置保存')}>保存设置</Button></Card>
  }

  return <Layout className="app-shell">
    {contextHolder}
    <Sider breakpoint="lg" collapsedWidth="0" className="app-sider">
      <div className="brand"><div className="brand-mark">企</div><div><strong>政企客户管理</strong><span>风险与到期工作台</span></div></div>
      <Menu theme="dark" mode="inline" selectedKeys={[selectedKey]} items={menuItems} onClick={({ key }) => setSelectedKey(key)} />
    </Sider>
    <Layout>
      <Header className="app-header"><div><Typography.Text className="eyebrow">运营总览</Typography.Text><Typography.Title level={2}>{current.label}</Typography.Title></div><Space><Tag color={health.isSuccess ? 'success' : 'warning'}>{health.isSuccess ? '后端已连接' : '等待后端'}</Tag><Button type="primary" onClick={() => setSelectedKey('tasks')}>今日待办 12</Button></Space></Header>
      <Content className="app-content">
        {!health.isSuccess && <Alert showIcon type="info" message="当前为演示数据模式" description="后端健康检查未连接时，页面仍可进行菜单和基础操作演示。" action={<Button size="small" onClick={() => health.refetch()}>重试</Button>} className="health-alert" />}
        {renderPage()}
      </Content>
    </Layout>
    <Modal title="客户详情" open={modalOpen} onCancel={() => setModalOpen(false)} footer={<Button type="primary" onClick={() => { setModalOpen(false); showAction('跟进任务创建') }}>创建跟进任务</Button>}><Typography.Paragraph>这里展示客户欠费、余额、专线到期和历史处理记录。当前使用演示数据，后续接入真实台账接口。</Typography.Paragraph></Modal>
  </Layout>
}

export default App
