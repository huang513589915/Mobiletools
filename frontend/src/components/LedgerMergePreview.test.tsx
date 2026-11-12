import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { LedgerUploadPreview } from '../api'
import { LedgerMergeClassificationSummary, LedgerMergeDifferences, SemanticDuplicateAlert } from './LedgerMergePreview'

const revision: LedgerUploadPreview['revision'] = {
  previousBatchId: 12,
  addedRows: 2,
  removedRows: 0,
  changedRows: 1,
  unchangedRows: 4,
  retainedRows: 3,
  differences: [{
    identityKey: '202611:A-100',
    changeType: 'UPDATED',
    previousImportRowId: 44,
    candidateImportRowId: 87,
    previousFields: { 账龄: '60天', 备注: '旧说明' },
    candidateFields: { 账龄: null, 备注: '新说明' },
    changedFields: [
      { header: '账龄', occurrence: 1, before: '60天', after: null, cleared: true },
      { header: '备注', occurrence: 1, before: '旧说明', after: '新说明', cleared: false },
    ],
  }],
}

describe('ledger merge preview', () => {
  it('shows all four merge classifications', () => {
    render(<LedgerMergeClassificationSummary revision={revision} />)

    expect(screen.getByText('新增 2')).toBeInTheDocument()
    expect(screen.getByText('更新 1')).toBeInTheDocument()
    expect(screen.getByText('未变化 4')).toBeInTheDocument()
    expect(screen.getByText('保留 3')).toBeInTheDocument()
  })

  it('shows before and after values and marks an empty cell as a clear', () => {
    render(<LedgerMergeDifferences differences={revision.differences} />)

    const row = screen.getByText('202611:A-100').closest('tr')
    expect(row).not.toBeNull()
    const expand = within(row as HTMLElement).getByRole('button')
    fireEvent.click(expand)

    expect(screen.getByText('账龄')).toBeInTheDocument()
    expect(screen.getByText('60天')).toBeInTheDocument()
    expect(screen.getByText('将清空')).toBeInTheDocument()
    expect(screen.getByText('旧说明')).toBeInTheDocument()
    expect(screen.getByText('新说明')).toBeInTheDocument()
  })

  it('explains a semantic duplicate without relying on an identical file hash', () => {
    render(<SemanticDuplicateAlert />)

    expect(screen.getByText(/与当前数据相比没有新增或更新/)).toBeInTheDocument()
  })
})
