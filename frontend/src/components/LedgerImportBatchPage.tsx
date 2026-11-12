import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Card, Descriptions, Drawer, Empty, Input, Modal, Select, Space, Steps, Table, Tag, Typography, Upload, message } from 'antd'
import { CloudUploadOutlined, DownloadOutlined, ReloadOutlined, UploadOutlined } from '@ant-design/icons'
import type { TableProps } from 'antd'
import {
  cancelLedgerImportBatch, confirmLedgerWorkbook, downloadLedgerOriginalWorkbook, fetchLedgerImportBatch, fetchLedgerImportBatches,
  LedgerImportBatch, LedgerImportBatchDetail, LedgerSourceType, LedgerUploadPreview, precheckLedgerWorkbook,
} from '../api'
import { LedgerMergeClassificationSummary, LedgerMergeDifferences, SemanticDuplicateAlert } from './LedgerMergePreview'
import { QueryFeedback } from './QueryFeedback'

const sourceLabel = (value: LedgerSourceType | string) => value === 'ARREARS' ? '欠费明细' : '专线到期明细'
const statusLabel: Record<string, string> = {
  RECEIVED: '已接收', PRECHECKED: '待确认', BLOCKED: '校验阻断', DUPLICATE: '重复文件', CONFIRMED: '已确认', FAILED: '写入失败', CANCELLED: '已取消',
}
const statusColor: Record<string, string> = { PRECHECKED: 'processing', BLOCKED: 'error', DUPLICATE: 'warning', CONFIRMED: 'success', FAILED: 'error', CANCELLED: 'default' }
const contributionLabel: Record<string, string> = { ALL: '全部贡献', PARTIAL: '部分贡献', NONE: '无当前记录' }
const contributionColor: Record<string, string> = { ALL: 'success', PARTIAL: 'warning', NONE: 'default' }
const dateLabel = (value: string | null) => {
  if (!value) return '—'
  return value.length === 6 ? `${value.slice(0, 4)}-${value.slice(4)}` : value.length === 8 ? `${value.slice(0, 4)}-${value.slice(4, 6)}-${value.slice(6)}` : value
}

export function LedgerImportBatchPage() {
  const client = useQueryClient()
  const [sourceType, setSourceType] = useState<LedgerSourceType>('ARREARS')
  const [preview, setPreview] = useState<LedgerUploadPreview>()
  const [detailId, setDetailId] = useState<number>()
  const [resumeBatch, setResumeBatch] = useState<LedgerImportBatch>()
  const [status, setStatus] = useState<string>()
  const [statisticsKey, setStatisticsKey] = useState('')
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(20)
  const [messageApi, holder] = message.useMessage()
  const [busy, setBusy] = useState(false)
  const [rowPage, setRowPage] = useState(1)
  const [rowPageSize, setRowPageSize] = useState(20)
  const batches = useQuery({
    queryKey: ['ledger-import-batches', sourceType, status, statisticsKey, page, pageSize],
    queryFn: () => fetchLedgerImportBatches({ sourceType, status, statisticsKey: statisticsKey || undefined, page, pageSize }),
    retry: false,
  })
  const detail = useQuery({
    queryKey: ['ledger-import-batch', detailId, rowPage, rowPageSize],
    queryFn: () => fetchLedgerImportBatch(detailId!, rowPage, rowPageSize),
    enabled: detailId !== undefined,
    retry: false,
  })

  const refresh = () => {
    void client.invalidateQueries({ queryKey: ['ledger-import-batches'] })
    if (detailId !== undefined) void client.invalidateQueries({ queryKey: ['ledger-import-batch', detailId] })
  }

  const requestCancelBatch = (batchId: number, closePreview = false) => {
    Modal.confirm({
      title: '取消这次上传？',
      content: '取消后，该批次不会写入正式数据；原始工作簿和预检记录仍会保留供追溯。已确认的批次不能取消。',
      okText: '确认取消上传',
      cancelText: '返回',
      okButtonProps: { danger: true },
      onOk: async () => {
        setBusy(true)
        try {
          await cancelLedgerImportBatch(batchId)
          messageApi.success(`批次 #${batchId} 已取消，正式数据未更改`)
          if (closePreview && preview?.batchId === batchId) setPreview(undefined)
          refresh()
        } catch (error) {
          messageApi.error(error instanceof Error ? error.message : '取消上传失败')
        } finally {
          setBusy(false)
        }
      },
    })
  }

  const upload = async (file: File) => {
    setBusy(true)
    try {
      setPreview(await precheckLedgerWorkbook(sourceType, file))
      refresh()
    } catch (error) {
      messageApi.error(error instanceof Error ? error.message : 'Excel 预检失败')
    } finally {
      setBusy(false)
    }
  }

  const confirm = async () => {
    if (!preview) return
    setBusy(true)
    try {
      const result = await confirmLedgerWorkbook(preview.batchId)
      messageApi.success(`已确认 ${result.confirmedRows} 行，归属异常 ${result.assignmentExceptionRows} 行`)
      setPreview(undefined)
      refresh()
    } catch (error) {
      messageApi.error(error instanceof Error ? error.message : '确认导入失败')
    } finally {
      setBusy(false)
    }
  }

  const confirmSavedBatch = async () => {
    if (!resumeBatch) return
    setBusy(true)
    try {
      const result = await confirmLedgerWorkbook(resumeBatch.batchId)
      messageApi.success(`已确认 ${result.confirmedRows} 行，归属异常 ${result.assignmentExceptionRows} 行`)
      setResumeBatch(undefined)
      refresh()
    } catch (error) {
      messageApi.error(error instanceof Error ? error.message : '确认导入失败')
    } finally {
      setBusy(false)
    }
  }

  const download = async (batchId: number, filename: string) => {
    try {
      const result = await downloadLedgerOriginalWorkbook(batchId)
      const expectedHash = batches.data?.items.find(item => item.batchId === batchId)?.sha256 ?? (detail.data?.batch.batchId === batchId ? detail.data.batch.sha256 : undefined)
      if (expectedHash && result.sha256 !== expectedHash) throw new Error('原始工作簿 SHA-256 校验失败，未保存下载文件')
      const url = URL.createObjectURL(result.blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = filename
      anchor.click()
      URL.revokeObjectURL(url)
    } catch (error) {
      messageApi.error(error instanceof Error ? error.message : '原始工作簿下载失败')
    }
  }

  const columns: TableProps<LedgerImportBatch>['columns'] = [
    { title: '批次', dataIndex: 'batchId', width: 80 },
    { title: '数据来源', dataIndex: 'sourceType', width: 120, render: sourceLabel },
    { title: '统计日期', dataIndex: 'statisticsKey', width: 120, render: dateLabel },
    { title: '文件', dataIndex: 'originalFilename', ellipsis: true },
    { title: '上传时间', dataIndex: 'uploadedAt', width: 170, render: (value: string) => value?.replace('T', ' ').replace(/\.\d+$/, '') ?? '—' },
    { title: '状态', dataIndex: 'status', width: 110, render: (value: string) => <Tag color={statusColor[value]}>{statusLabel[value] ?? value}</Tag> },
    { title: '当前数据贡献', width: 180, render: (_: unknown, row: LedgerImportBatch) => <Space size={4}>
      <Tag color={contributionColor[row.contributionStatus]}>{contributionLabel[row.contributionStatus] ?? row.contributionStatus}</Tag>
      <Typography.Text type="secondary">{row.contributingRows}/{row.validRows} 行</Typography.Text>
    </Space> },
    { title: '警告/错误', width: 105, render: (_: unknown, row: LedgerImportBatch) => `${row.warningRows} / ${row.errorRows}` },
    { title: '操作', fixed: 'right' as const, width: 250, render: (_: unknown, row: LedgerImportBatch) => <Space>
      <Button type="link" onClick={() => { setDetailId(row.batchId); setRowPage(1) }}>详情</Button>
      {row.originalFileAvailable
        ? <Button type="link" icon={<DownloadOutlined />} onClick={() => void download(row.batchId, row.originalFilename)}>原件</Button>
        : <OriginalFileState batch={row} />}
      {row.status === 'PRECHECKED' && <Button type="link" danger disabled={busy} onClick={() => requestCancelBatch(row.batchId)}>取消上传</Button>}
    </Space> },
  ]

  return <>
    {holder}
    <div className="management-page-intro">
      <div className="management-page-icon"><CloudUploadOutlined /></div>
      <div><Typography.Text className="management-page-kicker">Excel 数据维护</Typography.Text><Typography.Title level={3}>导入批次</Typography.Title>
        <Typography.Text type="secondary">上传工作簿、检查预览与版本差异，再确认写入对应统计日期。</Typography.Text></div>
    </div>
    <Card className="management-card" title="上传与批次记录" extra={<Button icon={<ReloadOutlined />} loading={batches.isFetching} onClick={refresh}>刷新</Button>}>
      <Space wrap className="batch-toolbar">
        <Select aria-label="导入数据来源" value={sourceType} onChange={value => { setSourceType(value); setPage(1) }} options={[
          { value: 'ARREARS', label: '欠费明细' }, { value: 'LINE_EXPIRY', label: '专线到期明细' },
        ]} style={{ width: 160 }} />
        <Input aria-label="统计日期筛选" allowClear placeholder="统计日期" value={statisticsKey} onChange={event => { setStatisticsKey(event.target.value); setPage(1) }} style={{ width: 145 }} />
        <Select aria-label="批次状态" allowClear placeholder="全部状态" value={status} onChange={value => { setStatus(value); setPage(1) }} options={Object.entries(statusLabel).map(([value, label]) => ({ value, label }))} style={{ width: 140 }} />
        <Upload accept=".xlsx,.xls" showUploadList={false} disabled={busy} beforeUpload={file => { void upload(file); return false }}>
          <Button type="primary" icon={<UploadOutlined />} loading={busy}>上传并预检</Button>
        </Upload>
      </Space>
      <Typography.Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 16 }}>
        同一来源和统计期的多个批次可以共同组成当前数据。确认合并时，上传未包含的记录会保留；此处显示每个批次仍贡献到当前数据的行数，详情可查看本行最近来源。
      </Typography.Paragraph>
      <QueryFeedback error={batches.error} retry={() => batches.refetch()} />
      <Table<LedgerImportBatch> className="management-table" rowKey="batchId" loading={batches.isFetching} dataSource={batches.data?.items ?? []} columns={columns} scroll={{ x: 1250 }} pagination={{ current: page, pageSize, total: batches.data?.total ?? 0, showSizeChanger: true, pageSizeOptions: [20, 50, 100], onChange: (nextPage, nextSize) => { setPage(nextPage); setPageSize(nextSize) } }} locale={{ emptyText: <Empty description="暂无导入批次" /> }} />
    </Card>
    <Modal title="导入预览" open={preview !== undefined} width={1100} onCancel={() => setPreview(undefined)} maskClosable={false} footer={[
      <Button key="close" onClick={() => setPreview(undefined)}>关闭预览</Button>,
      preview?.status === 'PRECHECKED' ? <Button key="cancel" danger disabled={busy} onClick={() => requestCancelBatch(preview.batchId, true)}>取消本次上传</Button> : null,
      <Button key="confirm" type="primary" loading={busy} disabled={!preview || preview.status !== 'PRECHECKED' || preview.errorRows > 0 || preview.totalRows === 0} onClick={() => void confirm()}>确认合并到当前数据</Button>,
    ]}>
      {preview && <PreviewContent preview={preview} />}
    </Modal>
    <BatchDetailDrawer detailId={detailId} detail={detail.data} loading={detail.isFetching} error={detail.error} rowPage={rowPage} rowPageSize={rowPageSize} onPage={(page, size) => { setRowPage(page); setRowPageSize(size) }} onClose={() => setDetailId(undefined)} onDownload={download} onResume={setResumeBatch} onCancelBatch={requestCancelBatch} busy={busy} />
    <Modal title="确认合并导入批次" open={resumeBatch !== undefined} confirmLoading={busy} onCancel={() => setResumeBatch(undefined)} onOk={() => void confirmSavedBatch()} okText="确认合并">
      {resumeBatch && <>
        <Typography.Paragraph>批次 #{resumeBatch.batchId} · {sourceLabel(resumeBatch.sourceType)} · {dateLabel(resumeBatch.statisticsKey)}</Typography.Paragraph>
        <Typography.Paragraph>{resumeBatch.originalFilename}</Typography.Paragraph>
        <Typography.Paragraph>有效 {resumeBatch.validRows} 行，警告 {resumeBatch.warningRows} 行；确认后只合并新增和更新记录，文件未包含的现有记录继续保留，历史批次仍可追溯。</Typography.Paragraph>
      </>}
    </Modal>
  </>
}

function PreviewContent({ preview }: { preview: LedgerUploadPreview }) {
  const issues = preview.issues ?? []
  return <>
    <Steps className="import-steps" size="small" current={1} status={preview.errorRows > 0 ? 'error' : 'process'} items={[
      { title: '选择工作簿', description: sourceLabel(preview.sourceType) },
      { title: '检查预览', description: preview.errorRows > 0 ? '存在阻断错误' : '核对数据与差异' },
      { title: '确认合并', description: '合并新增与更新记录' },
    ]} />
    <Descriptions bordered size="small" column={2} items={[
      { key: 'source', label: '数据来源', children: sourceLabel(preview.sourceType) },
      { key: 'date', label: '统计日期', children: dateLabel(preview.statisticsKey) },
      { key: 'file', label: '文件', children: preview.originalFilename },
      { key: 'status', label: '状态', children: <Tag color={statusColor[preview.status]}>{statusLabel[preview.status] ?? preview.status}</Tag> },
      { key: 'rows', label: '行数', children: `总计 ${preview.totalRows}，有效 ${preview.validRows}，警告 ${preview.warningRows}，错误 ${preview.errorRows}` },
      { key: 'revision', label: '合并预览分类', children: <LedgerMergeClassificationSummary revision={preview.revision} /> },
    ]} />
    {preview.duplicate && <SemanticDuplicateAlert />}
    {issues.map((issue, index) => <Alert key={`${issue.code}-${index}`} style={{ marginTop: 12 }} type={issue.severity === 'BLOCKING' ? 'error' : 'warning'} showIcon message={`${issue.message}${issue.sheetName ? `（${issue.sheetName}${issue.rowNumber ? `，第 ${issue.rowNumber} 行` : ''}）` : ''}`} />)}
    {preview.revision.differences.length > 0 && <LedgerMergeDifferences differences={preview.revision.differences} />}
    <Typography.Paragraph type="secondary" style={{ marginTop: 16 }}>批次 #{preview.batchId}；SHA-256：{preview.fileSha256}</Typography.Paragraph>
    <Table size="small" rowKey={row => `${row.sheetName}-${row.rowNumber}`} dataSource={preview.rows} pagination={{ pageSize: 8 }} scroll={{ x: 1000 }} expandable={{ expandedRowRender: row => <RawFieldTable fields={row.rawFields} /> }} columns={[
      { title: '工作表', dataIndex: 'sheetName' }, { title: '原始行', dataIndex: 'rowNumber' }, { title: '业务键', dataIndex: 'identityKey' },
      { title: 'Excel 客户经理', dataIndex: 'excelManagerName', render: (value: string | null) => value || '—' },
      { title: '校验问题', dataIndex: 'issues', render: (values: LedgerUploadPreview['rows'][number]['issues']) => values.length ? values.map(item => item.message).join('；') : '—' },
    ]} />
  </>
}

export function BatchDetailDrawer({ detailId, detail, loading, error, rowPage, rowPageSize, onPage, onClose, onDownload, onResume, onCancelBatch, busy }: {
  detailId?: number; detail?: LedgerImportBatchDetail; loading: boolean; error: unknown; rowPage: number; rowPageSize: number;
  onPage: (page: number, pageSize: number) => void; onClose: () => void; onDownload: (id: number, filename: string) => Promise<void>;
  onResume: (batch: LedgerImportBatch) => void; onCancelBatch: (id: number) => void; busy: boolean
}) {
  return <Drawer title={detail ? `批次 #${detail.batch.batchId} 详情` : '批次详情'} width={1000} open={detailId !== undefined} onClose={onClose}>
    {error instanceof Error && <Alert type="error" showIcon message={error.message} />}
    {loading && !detail && <Typography.Text type="secondary">正在加载…</Typography.Text>}
    {detail && <>
      <Space style={{ marginBottom: 16 }}>
        {detail.batch.originalFileAvailable
          ? <Button icon={<DownloadOutlined />} onClick={() => void onDownload(detail.batch.batchId, detail.batch.originalFilename)}>下载原始工作簿</Button>
          : <OriginalFileState batch={detail.batch} />}
        {detail.batch.status === 'PRECHECKED' && <Button type="primary" onClick={() => onResume(detail.batch)}>继续确认</Button>}
        {detail.batch.status === 'PRECHECKED' && <Button danger disabled={busy} onClick={() => onCancelBatch(detail.batch.batchId)}>取消上传</Button>}
      </Space>
      {!detail.batch.originalFileAvailable && <Alert style={{ marginBottom: 16 }} type={detail.batch.originalFilePurgeStatus === 'PENDING' ? 'warning' : 'info'} showIcon
        message={detail.batch.originalFilePurgeStatus === 'PENDING' ? '原始工作簿清理待重试' : '原始工作簿已永久销毁'}
        description={detail.batch.originalFilePurgeStatus === 'PENDING'
          ? '该原件当前不可下载，系统会自动重试物理文件清理。'
          : '此批次原件已由管理员永久删除，批次和其他记录仍保留追溯信息。'} />}
      <Alert style={{ marginBottom: 16 }} type="info" showIcon message="当前数据由多个批次合并时，本批次可能只贡献部分记录；此处展示该批次仍在当前数据集中的行数。每条台账详情显示该行最近一次新增或更新的批次、工作表和原始行号。" />
      <Descriptions bordered size="small" column={2} items={[
        { key: 'source', label: '数据来源', children: sourceLabel(detail.batch.sourceType) },
        { key: 'date', label: '统计日期', children: dateLabel(detail.batch.statisticsKey) },
        { key: 'file', label: '文件', children: detail.batch.originalFilename },
        { key: 'status', label: '状态', children: statusLabel[detail.batch.status] ?? detail.batch.status },
        { key: 'uploader', label: '上传人', children: detail.batch.uploaderUsername },
        { key: 'hash', label: '原文件 SHA-256', children: <Typography.Text copyable>{detail.batch.sha256}</Typography.Text> },
        { key: 'counts', label: '行计数', children: `总计 ${detail.batch.totalRows}，有效 ${detail.batch.validRows}，警告 ${detail.batch.warningRows}，错误 ${detail.batch.errorRows}` },
        { key: 'contribution', label: '当前数据贡献', children: <Space><Tag color={contributionColor[detail.batch.contributionStatus]}>{contributionLabel[detail.batch.contributionStatus] ?? detail.batch.contributionStatus}</Tag>{detail.batch.contributingRows}/{detail.batch.validRows} 行</Space> },
        { key: 'diff', label: '合并预览分类', children: `新增 ${detail.batch.addedRows}，更新 ${detail.batch.changedRows}，未变化 ${detail.batch.unchangedRows}，保留 ${detail.batch.retainedRows}` },
        { key: 'erased', label: '永久清除行数', children: detail.batch.erasedRows },
        { key: 'original', label: '原始工作簿状态', children: <OriginalFileState batch={detail.batch} /> },
      ]} />
      {detail.sheets.length > 0 && <><Typography.Title level={5} style={{ marginTop: 20 }}>工作表与行位置</Typography.Title><Table size="small" rowKey="sheetName" pagination={false} dataSource={detail.sheets} columns={[{ title: '工作表', dataIndex: 'sheetName' }, { title: '行数', dataIndex: 'rowCount' }, { title: '起始行', dataIndex: 'firstRowNumber' }, { title: '结束行', dataIndex: 'lastRowNumber' }]} /></>}
      <Typography.Title level={5} style={{ marginTop: 20 }}>行级校验与归属异常</Typography.Title>
      <Table size="small" rowKey="importRowId" dataSource={detail.rows} scroll={{ x: 1100 }} expandable={{ expandedRowRender: row => <>
        {row.errors?.map((issue, index) => <Alert key={`error-${index}`} style={{ marginBottom: 8 }} type="error" showIcon message={typeof issue === 'string' ? issue : JSON.stringify(issue)} />)}
        {row.warnings?.map((issue, index) => <Alert key={`warning-${index}`} style={{ marginBottom: 8 }} type="warning" showIcon message={typeof issue === 'string' ? issue : JSON.stringify(issue)} />)}
        {row.assignmentExceptionReason && <Alert style={{ marginBottom: 8 }} type="warning" showIcon message={row.assignmentExceptionReason} />}
        <RawFieldTable fields={row.rawFields} />
      </> }} pagination={{ current: rowPage, pageSize: rowPageSize, total: detail.rowPage.total, showSizeChanger: true, pageSizeOptions: [20, 50, 100], onChange: onPage }} columns={[
        { title: '工作表', dataIndex: 'sheetName' }, { title: '原始行', dataIndex: 'sourceRowNumber' }, { title: '业务键', dataIndex: 'identityKey' },
        { title: '状态', dataIndex: 'validationStatus' }, { title: '异常原因', dataIndex: 'assignmentExceptionReason', render: (value: string | null) => value || '—' },
        { title: '原始字段数', dataIndex: 'rawFields', render: (value: unknown[]) => value?.length ?? 0 },
      ]} />
      {detail.revisionDifferenceTotal > 0 && <>
        <Typography.Title level={5} style={{ marginTop: 20 }}>修正版差异（{detail.revisionDifferenceTotal} 条）</Typography.Title>
        <Table size="small" rowKey={row => row.identityKey} pagination={false} dataSource={detail.revisionDifferences} columns={[{ title: '业务键', dataIndex: 'identityKey' }, { title: '变化类型', dataIndex: 'changeType' }, { title: '旧批次行', dataIndex: 'previousImportRowId', render: (value: number | null) => value ?? '—' }, { title: '候选批次行', dataIndex: 'candidateImportRowId', render: (value: number | null) => value ?? '—' }]} />
        {detail.revisionDifferenceTotal > detail.revisionDifferences.length && <Typography.Text type="secondary">差异明细当前最多显示 {detail.revisionDifferences.length} 条。</Typography.Text>}
      </>}
    </>}
  </Drawer>
}

function OriginalFileState({ batch }: { batch: LedgerImportBatch }) {
  if (batch.originalFileAvailable) return <Tag color="success">原件保留，可下载</Tag>
  if (batch.originalFilePurgeStatus === 'PENDING') return <Tag color="processing">原件清理待重试</Tag>
  if (batch.originalFilePurgeStatus === 'PURGED') return <Tag>原件已销毁</Tag>
  return <Tag color="default">原件不可用</Tag>
}

function RawFieldTable({ fields }: { fields: Array<{ columnIndex: number; header: string; cellType?: string; value: string | null; formula?: string | null }> }) {
  return <Table size="small" rowKey="columnIndex" pagination={false} dataSource={fields ?? []} columns={[
    { title: '列位置', dataIndex: 'columnIndex', width: 80 }, { title: '字段', dataIndex: 'header', width: 220 },
    { title: '原始值', dataIndex: 'value', render: (value: string | null) => value == null || value === '' ? '—' : value },
    { title: '公式', dataIndex: 'formula', render: (value: string | null) => value || '—' },
  ]} />
}
