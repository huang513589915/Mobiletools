import { useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Input, Modal, Space, Table, Typography, Upload, message } from 'antd'
import { ReloadOutlined, UploadOutlined } from '@ant-design/icons'
import { cancelImport, confirmImport, fetchBatchExceptions, fetchImportBatches, fetchImportRows, fetchSavedPreview, ImportPreview, previewImport } from '../api'

const labels: Record<string, string> = { INSERT: '新增', UPDATE: '更新', DUPLICATE: '重复', CONFLICT: '冲突', EXCLUDED: '剔除', INVALID: '无效', PRECHECKED: '待确认', COMPLETED: '已完成', CANCELLED: '已取消', FAILED: '失败', INSERTED: '已新增', UPDATED: '已更新', VALID: '有效' }
const label = (value: string) => labels[value] ?? value

export function ImportCenter({ initialBatchId, initialContext, admin = true }: { initialBatchId?: number; initialContext?: Record<string, string | number>; admin?: boolean }) {
  const client = useQueryClient()
  const [messageApi, holder] = message.useMessage()
  const [preview, setPreview] = useState<ImportPreview>()
  const [batchId, setBatchId] = useState(initialBatchId)
  const [busy, setBusy] = useState(false)
  const operation = useRef(false)
  const [error, setError] = useState('')
  const [search, setSearch] = useState('')
  useEffect(() => { setBatchId(initialBatchId); setSearch('') }, [initialBatchId, initialContext?.exceptionId, initialContext?.sourceRowNumber, initialContext?.rowNumber, initialContext?.sheetName])
  const sourceRowNumber = Number(initialContext?.sourceRowNumber ?? initialContext?.rowNumber ?? 0) || undefined
  const sheetName = String(initialContext?.sheetName ?? '') || undefined
  const exceptionId = Number(initialContext?.exceptionId ?? 0) || undefined
  const batches = useQuery({ queryKey: ['imports'], queryFn: fetchImportBatches, retry: false, enabled: admin && !initialBatchId })
  const rows = useQuery({ queryKey: ['import-rows', batchId, sheetName, sourceRowNumber], queryFn: () => fetchImportRows(batchId!, { sheetName, sourceRowNumber }), enabled: Boolean(batchId), retry: false })
  const exceptions = useQuery({ queryKey: ['import-errors', batchId], queryFn: () => fetchBatchExceptions(batchId!), enabled: Boolean(batchId), retry: false })
  const refresh = () => client.invalidateQueries()
  async function act(confirm: boolean) {
    if (!preview || operation.current) return
    operation.current = true
    setBusy(true); setError('')
    try {
      if (confirm) {
        const result = await confirmImport(preview.batchId)
        if (result.status !== 'COMPLETED') {
          setError(result.message)
          setBatchId(preview.batchId)
          setPreview(undefined)
          await refresh()
          return
        }
        messageApi.success(`已新增 ${result.insertedRows}，更新 ${result.updatedRows}，重复 ${result.duplicateRows}`)
      } else await cancelImport(preview.batchId)
      setBatchId(preview.batchId); setPreview(undefined); await refresh()
    } catch (e) { setError(e instanceof Error ? e.message : '操作失败') }
    finally { operation.current = false; setBusy(false) }
  }
  return <>{holder}
    {admin && <Space className="page-toolbar" wrap>
      <Upload accept=".xlsx,.xls" showUploadList={false} disabled={busy || Boolean(preview)} beforeUpload={async file => {
        if (operation.current) return false
        operation.current = true; setBusy(true); setError('')
        try { setPreview(await previewImport(file)); await batches.refetch() }
        catch (e) { setError(e instanceof Error ? e.message : '预检失败') }
        finally { operation.current = false; setBusy(false) }
        return false
      }}><Button type="primary" icon={<UploadOutlined />} loading={busy}>导入预检</Button></Upload>
      <Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新批次</Button>
    </Space>}
    {error && <Alert type="error" showIcon message={error} />}
    {batches.isError && <Alert type="error" message={(batches.error as Error).message} />}
    {admin && !initialBatchId && <Table rowKey="id" loading={batches.isFetching} dataSource={batches.data ?? []} scroll={{ x: 1050 }} columns={[
      { title: '批次', dataIndex: 'id' }, { title: '文件名称', dataIndex: 'fileName' }, { title: '接入时间', dataIndex: 'createdAt' },
      { title: '状态', dataIndex: 'status', render: label }, { title: '总行数', dataIndex: 'totalRows' },
      { title: '新增', dataIndex: 'insertedRows' }, { title: '更新', dataIndex: 'updatedRows' }, { title: '重复', dataIndex: 'duplicateRows' },
      { title: '剔除', dataIndex: 'excludedRows' }, { title: '无效', dataIndex: 'invalidRows' }, { title: '写入失败', dataIndex: 'failedRows' },
      { title: '操作', render: (_, row) => <Space><Button type="link" onClick={() => { setBatchId(row.id); setSearch('') }}>查看批次</Button>{row.status === 'PRECHECKED' && <Button type="link" disabled={busy} onClick={async () => {
        setError(''); try { setPreview(await fetchSavedPreview(row.id)) } catch(e) { setError(e instanceof Error ? e.message : '预览读取失败') }
      }}>继续确认</Button>}</Space> },
    ]} />}
    {batchId && <section>
      <Typography.Title level={4}>批次 #{batchId} 明细</Typography.Title>
      {sourceRowNumber && <Alert type="info" showIcon message={`已定位：${sheetName ?? '全部工作表'}，Excel 原始行 ${sourceRowNumber}${exceptionId ? `，异常 #${exceptionId}` : ''}`} />}
      <Input.Search aria-label="批次明细筛选" placeholder="原始行、业务标识或错误原因" allowClear value={search} onChange={event => setSearch(event.target.value)} style={{ maxWidth: 320, marginBottom: 12 }} />
      {rows.isError && <Alert type="error" showIcon message="批次明细加载失败" description={(rows.error as Error).message} />}
      {rows.data && sourceRowNumber && rows.data.length === 0 && <Alert type="warning" showIcon message="无法显示定位行" description="该原始行不在当前账号授权范围内，或批次中已无此行。" />}
      <Table rowKey="id" loading={rows.isFetching} dataSource={(rows.data ?? []).filter(row => !search || `${row.sourceRowNumber} ${row.sheetName} ${row.businessKey} ${row.errorMessage}`.includes(search))} rowClassName={row => sourceRowNumber && row.sourceRowNumber === sourceRowNumber && (!sheetName || row.sheetName === sheetName) ? 'exception-target-row' : ''} pagination={{ pageSize: 10 }} scroll={{ x: 800 }} columns={[
        { title: '工作表', dataIndex: 'sheetName' }, { title: '原始行', dataIndex: 'sourceRowNumber' }, { title: '业务标识', dataIndex: 'businessKey' },
        { title: '客户编号', dataIndex: 'customerCode' }, { title: '状态', dataIndex: 'status', render: label }, { title: '分类', dataIndex: 'category', render: label },
        { title: '错误原因', dataIndex: 'errorMessage' }, { title: '错误码', dataIndex: 'errorCode' },
      ]} />
      {rows.isError && <Alert type="error" message={(rows.error as Error).message} />}
      {exceptions.isError && <Alert type="error" message={(exceptions.error as Error).message} />}
      {(exceptions.data ?? []).map(row => <Alert key={row.id} type="warning" message={row.reason} />)}
    </section>}
    <Modal title="导入预检结果" open={Boolean(preview)} width={1000} closable={!busy} maskClosable={false} onCancel={() => void act(false)} footer={[
      <Button key="cancel" disabled={busy} onClick={() => void act(false)}>取消导入</Button>,
      <Button key="confirm" type="primary" loading={busy} disabled={!preview || preview.workbookErrors.length > 0 || preview.validRows === 0} onClick={() => void act(true)}>确认正式导入</Button>,
    ]}>
      {preview && <>
        {error && <Alert type="error" message={error} />}
        {preview.workbookErrors.map(text => <Alert key={text} type="error" message={text} />)}
        <Typography.Paragraph>文件：{preview.fileName}；有效 {preview.validRows}；剔除 {preview.excludedRows}；无效或冲突 {preview.invalidRows}</Typography.Paragraph>
        <Table size="small" rowKey={row => `${row.sheetName}-${row.rowNumber}`} dataSource={preview.rows} pagination={{ pageSize: 8 }} scroll={{ x: 700 }} columns={[
          { title: '工作表', dataIndex: 'sheetName' }, { title: '原始行', dataIndex: 'rowNumber' }, { title: '业务标识', dataIndex: 'businessKey' },
          { title: '客户编号', dataIndex: 'customerCode' }, { title: '分类', dataIndex: 'classification', render: label }, { title: '原因', dataIndex: 'reason' },
        ]} />
      </>}
    </Modal>
  </>
}
