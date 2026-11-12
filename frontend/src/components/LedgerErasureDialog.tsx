import { useState } from 'react'
import { Alert, Button, Checkbox, Input, Modal, Space, Table, Tag, Typography, message } from 'antd'
import type { LedgerErasurePreview, LedgerSourceType } from '../api'
import { confirmLedgerErasure, previewLedgerErasure } from '../api'

const dateLabel = (value: string) => value.length === 6 ? `${value.slice(0, 4)}-${value.slice(4)}` : `${value.slice(0, 4)}-${value.slice(4, 6)}-${value.slice(6)}`

export function LedgerErasureDialog({ sourceType, selectedSnapshotIds, onClear, onCompleted }: {
  sourceType: LedgerSourceType; selectedSnapshotIds: number[]; onClear: () => void; onCompleted: () => void
}) {
  const [preview, setPreview] = useState<LedgerErasurePreview>()
  const [deleteWorkbooks, setDeleteWorkbooks] = useState(false)
  const [confirmationText, setConfirmationText] = useState('')
  const [busy, setBusy] = useState(false)
  const [messageApi, messageContext] = message.useMessage()

  const openPreview = async () => {
    setBusy(true)
    try {
      setDeleteWorkbooks(false)
      setConfirmationText('')
      setPreview(await previewLedgerErasure(sourceType, selectedSnapshotIds))
    } catch (error) {
      messageApi.error(error instanceof Error ? error.message : '永久删除预览失败')
    } finally { setBusy(false) }
  }

  const confirm = async () => {
    if (!preview || confirmationText !== '永久删除') return
    setBusy(true)
    try {
      const result = await confirmLedgerErasure(preview.previewToken, deleteWorkbooks, confirmationText)
      messageApi.success(result.originalFileStatus === 'PENDING' ? '台账记录已删除，原件清理待重试' : '所选记录已永久删除')
      setPreview(undefined)
      setConfirmationText('')
      onClear()
      onCompleted()
    } catch (error) {
      messageApi.error(error instanceof Error ? error.message : '永久删除失败')
    } finally { setBusy(false) }
  }

  const periodColumns = [
    { title: '统计日期', dataIndex: 'statisticsKey', render: (value: string) => dateLabel(value) },
    { title: '所选记录', dataIndex: 'selectedRecords' },
    { title: '历史导入行', dataIndex: 'historicalImportRows' },
    { title: '影响批次', dataIndex: 'affectedBatches' },
    { title: '工单/状态历史/跟进', render: (_: unknown, row: LedgerErasurePreview['periods'][number]) => `${row.workOrders} / ${row.workOrderStatusChanges} / ${row.workOrderFollowUps}` },
  ]

  return <>
    {messageContext}
    <Button danger disabled={!selectedSnapshotIds.length} loading={busy} onClick={() => void openPreview()}>
      永久删除所选记录 ({selectedSnapshotIds.length})
    </Button>
    <Modal title="永久删除影响预览" open={preview !== undefined} width={860} maskClosable={false}
      onCancel={() => setPreview(undefined)} confirmLoading={busy} okText="确认永久删除" cancelText="取消"
      okButtonProps={{ danger: true, disabled: confirmationText !== '永久删除' }} onOk={() => void confirm()}>
      {preview && <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Alert type="warning" showIcon message={`将永久删除 ${preview.selectedRecords.length} 条所选${sourceType === 'ARREARS' ? '欠费账户' : '专线实例'}在对应统计期内的全部数据库历史、归属记录和工单记录。此操作不可撤销。`} />
        <Table size="small" rowKey="statisticsKey" pagination={false} dataSource={preview.periods} columns={periodColumns} />
        <div>
          <Typography.Text strong>所选记录</Typography.Text>
          <div>{preview.selectedRecords.map(row => <Tag key={row.snapshotRecordId} style={{ marginTop: 6 }}>
            {dateLabel(row.statisticsKey)} · {row.groupName} · {row.stableIdentity}
          </Tag>)}</div>
        </div>
        <Checkbox checked={deleteWorkbooks} onChange={event => setDeleteWorkbooks(event.target.checked)}>
          同时永久删除关联的整份原始工作簿
        </Checkbox>
        {deleteWorkbooks && <>
          {preview.originalFiles.map((file, index) => <Alert key={`${index}-${file.filenames.join('|')}`} type="error" showIcon
            message={file.filenames.join('、') || '原始工作簿'}
            description={`共 ${file.referenceBatches} 个批次引用，包含约 ${file.parsedBusinessKeys} 条业务键，其中 ${file.otherBusinessKeys} 条不在本次删除范围；其他记录数据保留，但这些批次的原件也会不可下载。`} />)}
          {preview.originalFiles.length === 0 && <Typography.Text type="secondary">所选记录没有仍保留的原始工作簿。</Typography.Text>}
        </>}
        <Alert type="info" showIcon message="外部数据库备份不会自动清除，恢复旧备份可能重新带回已删除的数据。" />
        <label>
          <Typography.Text>输入“永久删除”以确认：</Typography.Text>
          <Input aria-label="永久删除确认词" value={confirmationText} onChange={event => setConfirmationText(event.target.value)} maxLength={10} />
        </label>
      </Space>}
    </Modal>
  </>
}
