import { Alert, Space, Table, Tag, Typography } from 'antd'
import type { LedgerUploadPreview } from '../api'

type Revision = LedgerUploadPreview['revision']
type Difference = Revision['differences'][number]
type ChangedField = Difference['changedFields'][number]

const changeLabel: Record<string, string> = {
  ADDED: '新增',
  UPDATED: '更新',
  UNCHANGED: '未变化',
  RETAINED: '保留',
}

const changeColor: Record<string, string> = {
  ADDED: 'success',
  UPDATED: 'processing',
  UNCHANGED: 'default',
  RETAINED: 'warning',
}

const displayFieldValue = (value: string | null) => value == null || value === '' ? '（空）' : value

export function LedgerMergeClassificationSummary({ revision }: { revision: Revision }) {
  return <Space wrap aria-label="合并预览分类">
    <Tag color="success">新增 {revision.addedRows}</Tag>
    <Tag color="processing">更新 {revision.changedRows}</Tag>
    <Tag>未变化 {revision.unchangedRows}</Tag>
    <Tag color="warning">保留 {revision.retainedRows}</Tag>
  </Space>
}

export function SemanticDuplicateAlert() {
  return <Alert
    style={{ marginTop: 12 }}
    type="warning"
    showIcon
    message="与当前数据相比没有新增或更新，本次按重复上传处理，不会写入正式数据。"
  />
}

export function LedgerChangedFieldsTable({ fields }: { fields: ChangedField[] }) {
  return <Table<ChangedField>
    size="small"
    rowKey={field => field.header + '-' + field.occurrence}
    pagination={false}
    dataSource={fields}
    columns={[
      { title: '字段', dataIndex: 'header', render: (value: string, field) => field.occurrence > 1 ? value + '（第 ' + field.occurrence + ' 列）' : value },
      { title: '原值', dataIndex: 'before', render: displayFieldValue },
      { title: '上传后', dataIndex: 'after', render: (value: string | null, field) => field.cleared
        ? <Tag color="error">将清空</Tag> : displayFieldValue(value) },
    ]}
  />
}

export function LedgerMergeDifferences({ differences }: { differences: Difference[] }) {
  return <>
    <Typography.Title level={5} style={{ marginTop: 20 }}>记录合并预览（{differences.length} 条）</Typography.Title>
    <Table<Difference>
      size="small"
      rowKey="identityKey"
      dataSource={differences}
      pagination={{ pageSize: 8 }}
      scroll={{ x: 800 }}
      expandable={{
        expandedRowRender: difference => difference.changedFields.length === 0
          ? <Typography.Text type="secondary">该记录没有字段变化。</Typography.Text>
          : <LedgerChangedFieldsTable fields={difference.changedFields} />,
      }}
      columns={[
        { title: '业务键', dataIndex: 'identityKey', ellipsis: true },
        { title: '合并分类', dataIndex: 'changeType', width: 110, render: (value: string) => <Tag color={changeColor[value] ?? 'default'}>{changeLabel[value] ?? value}</Tag> },
        { title: '旧批次行', dataIndex: 'previousImportRowId', width: 100, render: (value: number | null) => value ?? '—' },
        { title: '本次上传行', dataIndex: 'candidateImportRowId', width: 110, render: (value: number | null) => value ?? '—' },
        { title: '字段变化', dataIndex: 'changedFields', width: 90, render: (value: ChangedField[]) => value?.length ?? 0 },
      ]}
    />
  </>
}
