package com.mobiletools.arrears.dataingestion.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Performs the irreversible database side of an administrator ledger erasure. */
@Service
public class LedgerRecordErasureService {
    public static final String CONFIRMATION_TEXT = "永久删除";

    private final JdbcTemplate jdbc;
    private final LedgerErasurePreviewRegistry previews;
    private final LedgerOriginalFilePurgeService purges;

    public LedgerRecordErasureService(JdbcTemplate jdbc, LedgerErasurePreviewRegistry previews,
                                      LedgerOriginalFilePurgeService purges) {
        this.jdbc = jdbc;
        this.previews = previews;
        this.purges = purges;
    }

    @Transactional
    public ErasureResult erase(String previewToken, String operator, boolean deleteOriginalWorkbooks,
                               String confirmationText) {
        if (!CONFIRMATION_TEXT.equals(confirmationText)) {
            throw new IllegalArgumentException("请输入“永久删除”确认此次不可逆操作");
        }
        LedgerErasurePreviewRegistry.Plan plan = previews.consume(previewToken, operator);
        List<Long> actors = jdbc.queryForList("""
                SELECT id FROM app_user WHERE username=? AND role='ADMIN' AND enabled=TRUE FOR UPDATE
                """, Long.class, operator);
        if (actors.isEmpty()) throw new IllegalArgumentException("仅启用的管理员可执行永久删除");
        long actorId = actors.get(0);
        String operationId = UUID.randomUUID().toString();
        List<PeriodTarget> targets = periodTargets(plan);
        Map<String, Long> lockedVersions = lockAndValidateCurrentVersions(plan, targets);
        if (deleteOriginalWorkbooks) validateFileReferences(plan);
        Map<Long, Integer> erasedRowsByBatch = new LinkedHashMap<>();

        long deletedRecords = 0;
        long deletedImportRows = 0;
        long affectedBatches = 0;
        for (PeriodTarget target : targets) {
            ScopeRows rows = loadScopeRows(plan.sourceType(), target);
            if (rows.selectedSnapshotIds().isEmpty()) {
                throw new StaleLedgerErasurePreviewException("删除预览对应的历史记录已发生变化，请重新预览");
            }
            deleteWorkOrders(plan.sourceType(), target);
            deleteAssignments(rows.selectedSnapshotIds());
            deleteLedgerSnapshots(plan.sourceType(), rows.selectedSnapshotIds());
            deleteVersionMembers(plan.sourceType(), target);
            deleteRevisionDiffs(plan.sourceType(), target);
            deleteEffectiveRows(plan.sourceType(), target);
            deleteSnapshotRecords(rows.selectedSnapshotIds());
            deleteImportRows(rows.selectedImportRowIds());
            for (Map.Entry<Long, Integer> batch : rows.rowsByBatch().entrySet()) {
                erasedRowsByBatch.merge(batch.getKey(), batch.getValue(), Integer::sum);
            }
            deletedRecords += target.targets().size();
            deletedImportRows += rows.selectedImportRowIds().size();
            affectedBatches += rows.rowsByBatch().size();
        }

        for (Map.Entry<Long, Integer> batch : erasedRowsByBatch.entrySet()) {
            jdbc.update("UPDATE excel_import_batch SET erased_rows=erased_rows+? WHERE id=?",
                    batch.getValue(), batch.getKey());
        }

        int filesQueued = 0;
        if (deleteOriginalWorkbooks) {
            for (LedgerErasurePreviewRegistry.FileReference file : plan.originalFiles()) {
                jdbc.update("""
                        UPDATE excel_import_batch
                        SET source_file_id=NULL, original_file_purge_operation_id=?, original_file_purge_status='PENDING'
                        WHERE source_file_id=?
                        """, operationId, file.sourceFileId());
                jdbc.update("""
                        INSERT INTO excel_original_file_purge_queue(operation_id,storage_key)
                        VALUES (?,?) ON DUPLICATE KEY UPDATE operation_id=VALUES(operation_id), next_attempt_at=CURRENT_TIMESTAMP
                        """, operationId, file.storageKey());
                jdbc.update("DELETE FROM excel_source_file WHERE id=?", file.sourceFileId());
                filesQueued++;
            }
        }

        String status = !deleteOriginalWorkbooks ? "RETAINED" : filesQueued == 0 ? "PURGED" : "PENDING";
        String periods = targets.stream().map(PeriodTarget::statisticsKey).collect(Collectors.joining(","));
        String detail = "永久清除" + (deleteOriginalWorkbooks ? "并请求原件清理" : "台账记录；原件保留")
                + "；统计期=" + periods + "；统计期数量=" + targets.size() + "；记录数量=" + deletedRecords
                + "；导入行数量=" + deletedImportRows + "；影响批次数=" + affectedBatches
                + "；原件数量=" + filesQueued + "；原件状态=" + status
                + (filesQueued > 0 ? "；原件操作=" + operationId : "");
        int auditRows = jdbc.update("""
                INSERT INTO operation_audit_log(operator_user_id, operation_type, target_type, target_id, result, detail)
                VALUES (?, 'LEDGER_RECORD_ERASURE', ?, NULL, 'SUCCESS', ?)
                """, actorId, plan.sourceType(), detail);
        if (auditRows != 1) throw new IllegalStateException("删除审计写入失败");

        Long auditRecordId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (auditRecordId == null) throw new IllegalStateException("删除审计记录编号读取失败");
        return new ErasureResult(operationId, auditRecordId, plan.sourceType(), deletedRecords, deletedImportRows,
                affectedBatches, filesQueued, status, lockedVersions);
    }

    /** Runs physical cleanup after the transaction has committed; callers may expose pending count. */
    public ErasureResult processPhysicalCleanup(ErasureResult result) {
        if (result.filesQueued() == 0) return result;
        try {
            LedgerOriginalFilePurgeService.PurgeResult purge = purges.processOperation(result.operationId());
            String status = purge.pendingFiles() == 0 ? "PURGED" : "PENDING";
            if (status.equals("PURGED")) {
                jdbc.update("UPDATE operation_audit_log SET result='SUCCESS', detail=REPLACE(detail, '原件状态=PENDING', '原件状态=已清理') WHERE id=?",
                        result.auditRecordId());
            } else {
                jdbc.update("UPDATE operation_audit_log SET result='PENDING_FILE_PURGE', detail=REPLACE(detail, '原件状态=PENDING', '原件状态=待重试') WHERE id=?",
                        result.auditRecordId());
            }
            return new ErasureResult(result.operationId(), result.auditRecordId(), result.sourceType(),
                    result.deletedRecords(), result.deletedImportRows(), result.affectedBatches(),
                    result.filesQueued(), status, result.lockedVersions());
        } catch (RuntimeException exception) {
            return new ErasureResult(result.operationId(), result.auditRecordId(), result.sourceType(),
                    result.deletedRecords(), result.deletedImportRows(), result.affectedBatches(),
                    result.filesQueued(), "PENDING", result.lockedVersions());
        }
    }

    private List<PeriodTarget> periodTargets(LedgerErasurePreviewRegistry.Plan plan) {
        Map<String, List<LedgerErasurePreviewRegistry.TargetIdentity>> grouped = plan.targets().stream()
                .collect(Collectors.groupingBy(LedgerErasurePreviewRegistry.TargetIdentity::statisticsKey,
                        LinkedHashMap::new, Collectors.toList()));
        return grouped.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> new PeriodTarget(entry.getKey(), entry.getValue())).toList();
    }

    private Map<String, Long> lockAndValidateCurrentVersions(LedgerErasurePreviewRegistry.Plan plan,
                                                              List<PeriodTarget> targets) {
        Map<String, Long> locked = new LinkedHashMap<>();
        for (PeriodTarget target : targets) {
            List<Map<String, Object>> period = jdbc.queryForList("""
                    SELECT current_version_id FROM excel_dataset_period
                    WHERE source_type=? AND statistics_key=? FOR UPDATE
                    """, plan.sourceType(), target.statisticsKey());
            if (period.isEmpty() || period.get(0).get("current_version_id") == null) {
                throw new StaleLedgerErasurePreviewException("统计期当前版本不存在，请重新预览");
            }
            long currentVersion = ((Number) period.get(0).get("current_version_id")).longValue();
            Long previewVersion = plan.currentVersionByPeriod().get(target.statisticsKey());
            if (previewVersion == null || previewVersion.longValue() != currentVersion) {
                throw new StaleLedgerErasurePreviewException("预览后的当前版本已变化，请重新预览");
            }
            Map<String, Long> currentByIdentity = new HashMap<>();
            List<Map<String, Object>> current = jdbc.queryForList("""
                    SELECT snapshot_record_id, identity_key FROM excel_current_dataset
                    WHERE source_type=? AND statistics_key=? AND identity_key IN (%s)
                    """.formatted(marks(target.targets().size())),
                    scopedArgs(plan.sourceType(), target.statisticsKey(), target.targets().stream()
                            .map(LedgerErasurePreviewRegistry.TargetIdentity::identityKey).toList()).toArray());
            for (Map<String, Object> row : current) {
                currentByIdentity.put(String.valueOf(row.get("identity_key")),
                        ((Number) row.get("snapshot_record_id")).longValue());
            }
            for (LedgerErasurePreviewRegistry.TargetIdentity selected : target.targets()) {
                if (!Long.valueOf(selected.selectedSnapshotRecordId()).equals(currentByIdentity.get(selected.identityKey()))) {
                    throw new StaleLedgerErasurePreviewException("所选记录当前版本已变化，请重新预览");
                }
            }
            locked.put(target.statisticsKey(), currentVersion);
        }
        return locked;
    }

    private void validateFileReferences(LedgerErasurePreviewRegistry.Plan plan) {
        for (LedgerErasurePreviewRegistry.FileReference file : plan.originalFiles()) {
            List<String> storageKeys = jdbc.queryForList("""
                    SELECT storage_key FROM excel_source_file WHERE id=? FOR UPDATE
                    """, String.class, file.sourceFileId());
            if (storageKeys.size() != 1 || !storageKeys.get(0).equals(file.storageKey())) {
                throw new StaleLedgerErasurePreviewException("原始工作簿引用已变化，请重新预览");
            }
            List<Long> batchIds = jdbc.queryForList("""
                    SELECT id FROM excel_import_batch WHERE source_file_id=? ORDER BY id FOR UPDATE
                    """, Long.class, file.sourceFileId());
            if (!batchIds.equals(file.batchIds().stream().sorted().toList())) {
                throw new StaleLedgerErasurePreviewException("原始工作簿的批次影响范围已变化，请重新预览");
            }
        }
    }

    private ScopeRows loadScopeRows(String sourceType, PeriodTarget target) {
        List<String> keys = target.targets().stream().map(LedgerErasurePreviewRegistry.TargetIdentity::identityKey).toList();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.id AS import_row_id, s.id AS snapshot_record_id, b.id AS batch_id
                FROM excel_import_batch b JOIN excel_import_row r ON r.batch_id=b.id
                LEFT JOIN excel_snapshot_record s ON s.import_row_id=r.id
                WHERE b.source_type=? AND b.statistics_key=? AND r.identity_key IN (%s)
                """.formatted(marks(keys.size())), scopedArgs(sourceType, target.statisticsKey(), keys).toArray());
        Set<Long> importIds = new LinkedHashSet<>();
        Set<Long> snapshotIds = new LinkedHashSet<>();
        Map<Long, Integer> byBatch = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            long importId = ((Number) row.get("import_row_id")).longValue();
            importIds.add(importId);
            Object snapshot = row.get("snapshot_record_id");
            if (snapshot != null) snapshotIds.add(((Number) snapshot).longValue());
            long batchId = ((Number) row.get("batch_id")).longValue();
            byBatch.merge(batchId, 1, Integer::sum);
        }
        return new ScopeRows(importIds, snapshotIds, byBatch);
    }

    private void deleteWorkOrders(String sourceType, PeriodTarget target) {
        String table = "ARREARS".equals(sourceType) ? "arrears_work_order" : "line_renewal_work_order";
        String history = "ARREARS".equals(sourceType) ? "arrears_work_order_status_history" : "line_renewal_status_history";
        String follow = "ARREARS".equals(sourceType) ? "arrears_work_order_follow_up" : "line_renewal_work_order_follow_up";
        String keyColumn = "ARREARS".equals(sourceType) ? "account_id" : "member_service_number";
        List<String> keys = target.targets().stream().map(LedgerErasurePreviewRegistry.TargetIdentity::stableIdentity).toList();
        List<Long> ids = jdbc.queryForList("SELECT id FROM " + table + " WHERE statistics_key=? AND " + keyColumn + " IN (" + marks(keys.size()) + ")",
                Long.class, scopedArgs(target.statisticsKey(), keys).toArray());
        if (ids.isEmpty()) return;
        String orderMarks = marks(ids.size());
        jdbc.update("DELETE FROM " + history + " WHERE work_order_id IN (" + orderMarks + ")", ids.toArray());
        jdbc.update("DELETE FROM " + follow + " WHERE work_order_id IN (" + orderMarks + ")", ids.toArray());
        jdbc.update("DELETE FROM " + table + " WHERE id IN (" + orderMarks + ")", ids.toArray());
    }

    private void deleteAssignments(Set<Long> snapshots) {
        if (snapshots.isEmpty()) return;
        String marks = marks(snapshots.size());
        Object[] args = snapshots.toArray();
        jdbc.update("DELETE FROM assignment_history WHERE snapshot_record_id IN (" + marks + ")", args);
        jdbc.update("DELETE FROM assignment_exception WHERE snapshot_record_id IN (" + marks + ")", args);
    }

    private void deleteLedgerSnapshots(String sourceType, Set<Long> snapshots) {
        if (snapshots.isEmpty()) return;
        String table = "ARREARS".equals(sourceType) ? "arrears_snapshot" : "line_expiry_snapshot";
        jdbc.update("DELETE FROM " + table + " WHERE snapshot_record_id IN (" + marks(snapshots.size()) + ")", snapshots.toArray());
    }

    private void deleteVersionMembers(String sourceType, PeriodTarget target) {
        List<String> keys = target.targets().stream().map(LedgerErasurePreviewRegistry.TargetIdentity::identityKey).toList();
        jdbc.update("""
                DELETE m FROM excel_dataset_version_row m
                JOIN excel_dataset_version v ON v.id=m.dataset_version_id
                WHERE v.source_type=? AND v.statistics_key=? AND m.identity_key IN (%s)
                """.formatted(marks(keys.size())), scopedArgs(sourceType, target.statisticsKey(), keys).toArray());
    }

    private void deleteRevisionDiffs(String sourceType, PeriodTarget target) {
        List<String> keys = target.targets().stream().map(LedgerErasurePreviewRegistry.TargetIdentity::identityKey).toList();
        jdbc.update("""
                DELETE d FROM excel_revision_diff d JOIN excel_import_batch b ON b.id=d.batch_id
                WHERE b.statistics_key=? AND b.source_type=? AND d.identity_key IN (%s)
                """.formatted(marks(keys.size())), scopedArgs(target.statisticsKey(), sourceType, keys).toArray());
    }

    private void deleteEffectiveRows(String sourceType, PeriodTarget target) {
        List<String> keys = target.targets().stream().map(LedgerErasurePreviewRegistry.TargetIdentity::identityKey).toList();
        jdbc.update("""
                DELETE e FROM excel_effective_row e
                WHERE e.source_type=? AND e.statistics_key=? AND e.identity_key IN (%s)
                  AND NOT EXISTS (SELECT 1 FROM excel_dataset_version_row m WHERE m.effective_row_id=e.id)
                """.formatted(marks(keys.size())), scopedArgs(sourceType, target.statisticsKey(), keys).toArray());
    }

    private void deleteSnapshotRecords(Set<Long> snapshots) {
        if (!snapshots.isEmpty()) jdbc.update("DELETE FROM excel_snapshot_record WHERE id IN (" + marks(snapshots.size()) + ")", snapshots.toArray());
    }

    private void deleteImportRows(Set<Long> importRows) {
        if (!importRows.isEmpty()) jdbc.update("DELETE FROM excel_import_row WHERE id IN (" + marks(importRows.size()) + ")", importRows.toArray());
    }

    private static List<Object> scopedArgs(Object first, Object second, List<String> keys) {
        List<Object> values = new ArrayList<>(); values.add(first); values.add(second); values.addAll(keys); return values;
    }

    private static List<Object> scopedArgs(String period, List<String> keys) {
        List<Object> values = new ArrayList<>(); values.add(period); values.addAll(keys); return values;
    }

    private static String marks(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private record PeriodTarget(String statisticsKey, List<LedgerErasurePreviewRegistry.TargetIdentity> targets) {}
    private record ScopeRows(Set<Long> selectedImportRowIds, Set<Long> selectedSnapshotIds,
                             Map<Long, Integer> rowsByBatch) {}

    public record ErasureResult(String operationId, long auditRecordId, String sourceType, long deletedRecords,
                                long deletedImportRows, long affectedBatches, int filesQueued,
                                String originalFileStatus, Map<String, Long> lockedVersions) {
        public ErasureResult {
            lockedVersions = Map.copyOf(lockedVersions);
        }
    }
}
