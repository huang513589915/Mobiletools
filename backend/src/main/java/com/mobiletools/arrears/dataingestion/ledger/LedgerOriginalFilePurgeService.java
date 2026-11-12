package com.mobiletools.arrears.dataingestion.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/** Retries physical workbook deletion after the database erasure transaction commits. */
@Service
public class LedgerOriginalFilePurgeService {
    private static final int MAX_BATCH = 20;
    private final JdbcTemplate jdbc;
    private final LedgerOriginalWorkbookStore store;
    private final TransactionTemplate transactions;

    @Autowired
    public LedgerOriginalFilePurgeService(JdbcTemplate jdbc, LedgerOriginalWorkbookStore store,
                                          PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.store = store;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public PurgeResult processOperation(String operationId) {
        List<PurgeItem> items = jdbc.query("""
                SELECT id, storage_key, operation_id FROM excel_original_file_purge_queue
                WHERE operation_id=? ORDER BY id
                """, (rs, row) -> new PurgeItem(rs.getLong("id"), rs.getString("storage_key"),
                        rs.getString("operation_id")), operationId);
        int deleted = 0;
        int pending = 0;
        for (PurgeItem item : items) {
            if (purgeOne(item)) deleted++; else pending++;
        }
        return new PurgeResult(deleted, pending);
    }

    /** Resume cleanup items left behind by a process restart. */
    @EventListener(ApplicationReadyEvent.class)
    public void retryPendingOnStartup() {
        retryPending();
    }

    @Scheduled(fixedDelayString = "${app.ledger.original-file-purge-delay-ms:30000}")
    public void retryPending() {
        List<PurgeItem> items = jdbc.query("""
                SELECT id, storage_key, operation_id FROM excel_original_file_purge_queue
                WHERE next_attempt_at<=CURRENT_TIMESTAMP ORDER BY next_attempt_at,id LIMIT ?
                """, (rs, row) -> new PurgeItem(rs.getLong("id"), rs.getString("storage_key"),
                        rs.getString("operation_id")), MAX_BATCH);
        for (PurgeItem item : items) purgeOne(item);
    }

    private boolean purgeOne(PurgeItem item) {
        try {
            Boolean completed = transactions.execute(status -> {
                List<PurgeItem> locked = jdbc.query("""
                        SELECT id, storage_key, operation_id FROM excel_original_file_purge_queue
                        WHERE id=? FOR UPDATE
                        """, (rs, row) -> new PurgeItem(rs.getLong("id"), rs.getString("storage_key"),
                        rs.getString("operation_id")), item.id());
                if (locked.isEmpty()) return true;
                PurgeItem current = locked.get(0);
                Integer liveReferences = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM excel_source_file WHERE storage_key=?
                        """, Integer.class, current.storageKey());
                try {
                    // A re-import may have legitimately reused this content-addressed file.
                    // In that case resolve the old purge request without deleting live content.
                    if (liveReferences == null || liveReferences == 0) store.delete(current.storageKey());
                    jdbc.update("DELETE FROM excel_original_file_purge_queue WHERE id=?", current.id());
                    Integer remaining = jdbc.queryForObject("""
                            SELECT COUNT(*) FROM excel_original_file_purge_queue WHERE operation_id=?
                            """, Integer.class, current.operationId());
                    if (remaining == null || remaining == 0) {
                        jdbc.update("""
                                UPDATE excel_import_batch
                                SET original_file_purge_status='PURGED'
                                WHERE original_file_purge_operation_id=? AND original_file_purge_status='PENDING'
                                """, current.operationId());
                        String pendingStatus = "\u539f\u4ef6\u72b6\u6001=\u5f85\u91cd\u8bd5";
                        String purgedStatus = "\u539f\u4ef6\u72b6\u6001=\u5df2\u6e05\u7406";
                        String pendingLegacyStatus = "\u539f\u4ef6\u72b6\u6001=PENDING";
                        String operationMarker = "\u539f\u4ef6\u64cd\u4f5c=";
                        jdbc.update("""
                                UPDATE operation_audit_log
                                SET result='SUCCESS',
                                    detail=REPLACE(REPLACE(detail, ?, ?), ?, ?)
                                WHERE operation_type='LEDGER_RECORD_ERASURE'
                                  AND result='PENDING_FILE_PURGE'
                                  AND detail LIKE ?
                                """, pendingStatus, purgedStatus, pendingLegacyStatus, purgedStatus,
                                "%" + operationMarker + current.operationId() + "%");
                    }
                    return true;
                } catch (IOException exception) {
                    recordFailure(current, "FILESYSTEM_IO");
                    return false;
                }
            });
            return Boolean.TRUE.equals(completed);
        } catch (RuntimeException exception) {
            try { recordFailure(item, "PURGE_STORE_ERROR"); }
            catch (RuntimeException ignored) { /* Keep the queue item for the next scheduler pass. */ }
            return false;
        }
    }

    private void recordFailure(PurgeItem item, String code) {
        jdbc.update("""
                UPDATE excel_original_file_purge_queue
                SET attempt_count=attempt_count+1,last_error_code=?,last_attempt_at=CURRENT_TIMESTAMP,
                    next_attempt_at=TIMESTAMPADD(SECOND, LEAST(POWER(2, LEAST(attempt_count, 10)), 3600), CURRENT_TIMESTAMP)
                WHERE id=?
                """, code, item.id());
    }

    private record PurgeItem(long id, String storageKey, String operationId) {}
    public record PurgeResult(int deletedFiles, int pendingFiles) {}
}
