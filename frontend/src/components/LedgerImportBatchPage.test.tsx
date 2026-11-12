import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { LedgerImportBatchDetail } from '../api'
import { BatchDetailDrawer } from './LedgerImportBatchPage'

const detail = (overrides: Partial<LedgerImportBatchDetail['batch']> = {}): LedgerImportBatchDetail => ({
  batch: {
    batchId: 17,
    sourceType: 'ARREARS',
    statisticsKey: '202610',
    originalFilename: 'arrears.xlsx',
    sha256: 'a'.repeat(64),
    originalFileSize: 1200,
    uploaderUsername: 'admin',
    uploadedAt: '2026-10-01T10:00:00',
    confirmedAt: '2026-10-01T10:10:00',
    status: 'CONFIRMED',
    contributingRows: 3,
    contributionStatus: 'ALL',
    totalRows: 3,
    validRows: 3,
    warningRows: 0,
    errorRows: 0,
    addedRows: 3,
    removedRows: 0,
    changedRows: 0,
    unchangedRows: 0,
    retainedRows: 0,
    erasedRows: 0,
    originalFileAvailable: true,
    originalFilePurgeStatus: 'AVAILABLE',
    ...overrides,
  },
  validationSummary: {},
  sheets: [],
  rows: [],
  rowPage: { page: 1, pageSize: 20, total: 0 },
  revisionDifferences: [],
  revisionDifferenceTotal: 0,
})

afterEach(() => cleanup())

function renderDrawer(batch: LedgerImportBatchDetail['batch']) {
  const onDownload = vi.fn().mockResolvedValue(undefined)
  render(<BatchDetailDrawer
    detailId={batch.batchId}
    detail={detail({ ...batch })}
    loading={false}
    error={undefined}
    rowPage={1}
    rowPageSize={20}
    onPage={vi.fn()}
    onClose={vi.fn()}
    onDownload={onDownload}
    onResume={vi.fn()}
    onCancelBatch={vi.fn()}
    busy={false}
  />)
  return onDownload
}

describe('ledger import batch original file state', () => {
  it('shows deleted-row count and blocks download while physical cleanup is pending', async () => {
    renderDrawer(detail({
      erasedRows: 4,
      originalFileAvailable: false,
      originalFilePurgeStatus: 'PENDING',
    }).batch)

    expect(await screen.findByText('原始工作簿清理待重试')).toBeInTheDocument()
    expect(screen.getAllByText('原件清理待重试')).toHaveLength(2)
    expect(screen.getByText('永久清除行数')).toBeInTheDocument()
    const erasedRow = screen.getByText('永久清除行数').closest('tr')
    expect(erasedRow).not.toBeNull()
    expect(within(erasedRow as HTMLElement).getByText('4')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '下载原始工作簿' })).not.toBeInTheDocument()
  })

  it('keeps original workbook download available for retained batches', async () => {
    const onDownload = renderDrawer(detail().batch)
    const download = (await screen.findByText('下载原始工作簿')).closest('button')
    expect(download).not.toBeNull()

    fireEvent.click(download as HTMLElement)
    await waitFor(() => expect(onDownload).toHaveBeenCalledWith(17, 'arrears.xlsx'))
    expect(screen.queryByText('原始工作簿已永久销毁')).not.toBeInTheDocument()
  })
})
