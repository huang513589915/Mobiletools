import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { confirmLedgerErasure, previewLedgerErasure } from '../api'
import { LedgerErasureDialog } from './LedgerErasureDialog'

const { success } = vi.hoisted(() => ({ success: vi.fn() }))

vi.mock('antd', async importOriginal => {
  const antd = await importOriginal<typeof import('antd')>()
  return { ...antd, message: { ...antd.message, useMessage: () => [{ success }, null] } }
})

vi.mock('../api', () => ({
  previewLedgerErasure: vi.fn(),
  confirmLedgerErasure: vi.fn(),
}))

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

const preview = {
  previewToken: 'one-use-preview-token',
  expiresAt: '2026-11-12T00:05:00Z',
  sourceType: 'ARREARS' as const,
  selectedRecords: [
    { snapshotRecordId: 91, statisticsKey: '202611', stableIdentity: 'A-1', groupCode: 'G-1', groupName: '示例集团' },
    { snapshotRecordId: 92, statisticsKey: '202611', stableIdentity: 'A-2', groupCode: 'G-2', groupName: '示例集团二' },
  ],
  periods: [{
    statisticsKey: '202611', selectedRecords: 2, historicalImportRows: 4, snapshots: 4, affectedBatches: 3,
    versions: 2, revisionDiffs: 2, assignmentExceptions: 1, assignmentHistory: 2,
    workOrders: 2, workOrderStatusChanges: 1, workOrderFollowUps: 1,
  }],
  originalFiles: [{ filenames: ['shared.xlsx'], referenceBatches: 3, affectedPeriods: 2, parsedBusinessKeys: 10, otherBusinessKeys: 8 }],
  maxSelectedRecords: 100,
}

describe('ledger erasure dialog', () => {
  it('previews the multi-selection, warns about shared files, and reports pending cleanup', async () => {
    vi.mocked(previewLedgerErasure).mockResolvedValue(preview)
    vi.mocked(confirmLedgerErasure).mockResolvedValue({
      operationId: 'operation-1', sourceType: 'ARREARS', deletedRecords: 2, deletedImportRows: 4,
      affectedBatches: 3, filesQueued: 1, originalFileStatus: 'PENDING', lockedVersions: { '202611': 8 },
    })
    const onClear = vi.fn()
    const onCompleted = vi.fn()
    render(<LedgerErasureDialog sourceType="ARREARS" selectedSnapshotIds={[91, 92]} onClear={onClear} onCompleted={onCompleted} />)

    fireEvent.click(screen.getByRole('button', { name: /永久删除所选记录 \(2\)/ }))
    expect(await screen.findByText(/将永久删除 2 条所选欠费账户/)).toBeInTheDocument()
    expect(previewLedgerErasure).toHaveBeenCalledWith('ARREARS', [91, 92])

    fireEvent.click(screen.getByRole('checkbox', { name: '同时永久删除关联的整份原始工作簿' }))
    expect(screen.getByText(/其中 8 条不在本次删除范围/)).toBeInTheDocument()
    const modal = screen.getByRole('dialog', { name: '永久删除影响预览' })
    const confirmButton = within(modal).getByRole('button', { name: '确认永久删除' })
    expect(confirmButton).toBeDisabled()
    fireEvent.change(within(modal).getByRole('textbox', { name: '永久删除确认词' }), { target: { value: '永久删除' } })
    fireEvent.click(confirmButton)

    await waitFor(() => expect(confirmLedgerErasure).toHaveBeenCalledWith('one-use-preview-token', true, '永久删除'))
    expect(onClear).toHaveBeenCalledOnce()
    expect(onCompleted).toHaveBeenCalledOnce()
    expect(success).toHaveBeenCalledWith('台账记录已删除，原件清理待重试')
    expect(screen.getByRole('dialog', { hidden: true })).toHaveStyle({ display: 'none' })
  })
})
