package com.mobiletools.arrears.dataingestion.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** Cancels a prechecked import candidate without deleting its audit or source data. */
@Service
public class LedgerBatchCancellationService {

    private final JdbcTemplate jdbc;

    public LedgerBatchCancellationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public CancellationResult cancel(long batchId, String username) {
        if (batchId <= 0) throw new IllegalArgumentException("batchId 必须为正数");

        List<Map<String, Object>> batches = jdbc.queryForList("""
                SELECT id, source_type, statistics_key, batch_status
                FROM excel_import_batch WHERE id=? FOR UPDATE
                """, batchId);
        if (batches.isEmpty()) throw new IllegalArgumentException("导入批次不存在");
        Map<String, Object> batch = batches.get(0);
        String status = String.valueOf(batch.get("batch_status"));
        if (!"PRECHECKED".equals(status)) {
            throw new IllegalArgumentException("只有待确认的批次可以取消；当前状态为 " + status);
        }

        List<Long> actors = jdbc.queryForList("""
                SELECT id FROM app_user
                WHERE username=? AND role='ADMIN' AND enabled=TRUE
                """, Long.class, username);
        if (actors.isEmpty()) throw new IllegalArgumentException("仅启用的管理员可取消导入批次");

        int updated = jdbc.update("""
                UPDATE excel_import_batch SET batch_status='CANCELLED'
                WHERE id=? AND batch_status='PRECHECKED'
                """, batchId);
        if (updated != 1) throw new IllegalStateException("批次状态已变化，请刷新后重试");

        jdbc.update("""
                INSERT INTO operation_audit_log(
                    operator_user_id, operation_type, target_type, target_id, result, detail
                ) VALUES (?, 'LEDGER_IMPORT_CANCEL', 'LEDGER_IMPORT_BATCH', ?, 'SUCCESS', ?)
                """, actors.get(0), batchId, "取消待确认导入批次；正式快照未更改，原始工作簿和预检记录保留");

        return new CancellationResult(batchId, "CANCELLED", String.valueOf(batch.get("source_type")),
                batch.get("statistics_key") == null ? null : String.valueOf(batch.get("statistics_key")));
    }

    public record CancellationResult(long batchId, String status, String sourceType, String statisticsKey) {}
}
