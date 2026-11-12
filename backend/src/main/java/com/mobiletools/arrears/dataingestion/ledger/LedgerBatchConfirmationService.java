package com.mobiletools.arrears.dataingestion.ledger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Writes one prechecked Excel batch into its immutable business snapshot. */
@Service
public class LedgerBatchConfirmationService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final LedgerBatchFailureRecorder failureRecorder;
    private final LedgerMergePlanner mergePlanner;

    public LedgerBatchConfirmationService(JdbcTemplate jdbc, ObjectMapper objectMapper,
                                          LedgerBatchFailureRecorder failureRecorder) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.failureRecorder = failureRecorder;
        this.mergePlanner = new LedgerMergePlanner(objectMapper);
    }

    @Transactional
    public ConfirmationResult confirm(long batchId, String username) {
        List<Map<String, Object>> identities = jdbc.queryForList("""
                SELECT id, source_type, statistics_key
                FROM excel_import_batch WHERE id=?
                """, batchId);
        if (identities.isEmpty()) throw new IllegalArgumentException("导入批次不存在");
        Map<String, Object> identity = identities.get(0);
        String sourceType = String.valueOf(identity.get("source_type"));
        Object statisticsValue = identity.get("statistics_key");
        if (statisticsValue == null) throw new IllegalArgumentException("批次缺少有效统计日期");
        String statisticsKey = String.valueOf(statisticsValue);
        if (!"ARREARS".equals(sourceType) && !"LINE_EXPIRY".equals(sourceType)) {
            throw new IllegalStateException("导入批次数据来源无效");
        }

        List<Map<String, Object>> batches = jdbc.queryForList("""
                SELECT id, source_type, statistics_key, batch_status, total_rows, preview_base_version_id
                FROM excel_import_batch WHERE id=? FOR UPDATE
                """, batchId);
        if (batches.isEmpty()) throw new IllegalArgumentException("导入批次不存在");
        Map<String, Object> batch = batches.get(0);
        String status = String.valueOf(batch.get("batch_status"));
        if (!"PRECHECKED".equals(status)) {
            throw new IllegalArgumentException("当前批次状态不允许确认导入：" + status);
        }

        List<Long> actors = jdbc.queryForList("""
                SELECT id FROM app_user
                WHERE username=? AND role='ADMIN' AND enabled=TRUE
                """, Long.class, username);
        if (actors.isEmpty()) throw new IllegalArgumentException("仅启用的管理员可确认导入");
        long actorId = actors.get(0);

        List<CandidateRow> rows = loadRows(batchId);
        long declaredRows = number(batch.get("total_rows"));
        if (rows.isEmpty() || rows.size() != declaredRows
                || rows.stream().anyMatch(row -> !List.of("VALID", "WARNING").contains(row.validationStatus()))) {
            throw new IllegalArgumentException("批次预检数据不完整或包含阻断错误，不能确认导入");
        }

        try {
            jdbc.update("""
                    INSERT IGNORE INTO excel_dataset_period(source_type, statistics_key, current_version_id)
                    VALUES (?, ?, NULL)
                    """, sourceType, statisticsKey);
            List<Long> currentVersions = jdbc.queryForList("""
                    SELECT current_version_id FROM excel_dataset_period
                    WHERE source_type=? AND statistics_key=? FOR UPDATE
                    """, Long.class, sourceType, statisticsKey);
            Long currentVersionId = currentVersions.isEmpty() ? null : currentVersions.get(0);
            Long previewBaseVersionId = nullableNumber(batch.get("preview_base_version_id"));
            if (!Objects.equals(currentVersionId, previewBaseVersionId)) {
                RefreshSummary refreshed = refreshPreview(batchId, sourceType, statisticsKey,
                        currentVersionId, actorId);
                return new ConfirmationResult(batchId, sourceType, statisticsKey, rows.size(),
                        refreshed.warningRows(), refreshed.assignmentExceptionRows(), false, true,
                        refreshed.addedRows(), refreshed.updatedRows(), refreshed.unchangedRows(), refreshed.retainedRows());
            }

            LedgerMergePlanner.Plan plan = loadPlan(batchId, sourceType, statisticsKey);
            long exceptions = 0;
            long warnings = 0;
            long versionId = insertVersion(batchId, sourceType, statisticsKey, currentVersionId,
                    actorId, plan);
            if (currentVersionId != null) {
                jdbc.update("""
                        INSERT INTO excel_dataset_version_row(
                            dataset_version_id, source_type, statistics_key, identity_key, effective_row_id
                        )
                        SELECT ?, source_type, statistics_key, identity_key, effective_row_id
                        FROM excel_dataset_version_row WHERE dataset_version_id=?
                        """, versionId, currentVersionId);
            }
            Map<Long, CandidateRow> candidateRows = new LinkedHashMap<>();
            for (CandidateRow candidate : rows) candidateRows.put(candidate.id(), candidate);
            for (LedgerMergePlanner.RowPlan rowPlan : plan.uploadedRows()) {
                if ("UNCHANGED".equals(rowPlan.changeType())) continue;
                CandidateRow row = candidateRows.get(rowPlan.candidate().importRowId());
                if (row == null) throw new IllegalStateException("预览行不存在");
                boolean rowWarning = row.warnings().isArray() && !row.warnings().isEmpty();
                JsonNode normalized = rowPlan.effectiveNormalizedFields();
                String managerHeader = "ARREARS".equals(sourceType) ? "集团客户经理姓名" : "客户经理姓名";
                String excelManagerName = optionalText(normalized, managerHeader);
                LedgerMergePlanner.Current previous = rowPlan.current();
                boolean sameExcelManager = previous != null
                        && Objects.equals(previous.excelManagerName(), excelManagerName);
                List<Long> matches = sameExcelManager ? List.of() : exactManagerMatches(excelManagerName);
                Long effectiveManagerId = sameExcelManager ? previous.effectiveManagerUserId()
                        : matches.size() == 1 ? matches.get(0) : null;

                long snapshotRecordId = insertSnapshotRecord(batchId, sourceType, row.id(),
                        excelManagerName, effectiveManagerId);
                if ("ARREARS".equals(sourceType)) {
                    insertArrearsSnapshot(snapshotRecordId, batchId, normalized);
                    upsertArrearsWorkOrder(statisticsKey,
                            requiredText(normalized, "账户ID"), snapshotRecordId);
                } else {
                    insertLineExpirySnapshot(snapshotRecordId, batchId, normalized);
                    upsertLineRenewalWorkOrder(statisticsKey,
                            requiredText(normalized, "成员服务号码"), snapshotRecordId);
                }

                insertEffectiveRow(sourceType, statisticsKey, rowPlan, batchId, snapshotRecordId);
                jdbc.update("DELETE FROM excel_dataset_version_row WHERE dataset_version_id=? AND identity_key=?",
                        versionId, rowPlan.candidate().identityKey());
                jdbc.update("""
                        INSERT INTO excel_dataset_version_row(
                            dataset_version_id, source_type, statistics_key, identity_key, effective_row_id
                        )
                        SELECT ?, source_type, statistics_key, identity_key, id
                        FROM excel_effective_row WHERE snapshot_record_id=?
                        """, versionId, snapshotRecordId);

                if (sameExcelManager && previous != null) {
                    copyAssignmentState(previous.snapshotRecordId(), snapshotRecordId);
                    if (effectiveManagerId == null) {
                        exceptions++;
                        rowWarning = true;
                    }
                } else if (effectiveManagerId == null) {
                    String exceptionType = excelManagerName == null || excelManagerName.isBlank()
                            ? "EMPTY_NAME" : matches.isEmpty() ? "NO_MATCH" : "MULTIPLE_MATCHES";
                    String reason = switch (exceptionType) {
                        case "EMPTY_NAME" -> "Excel 客户经理姓名为空，无法匹配账号";
                        case "NO_MATCH" -> "未找到真实姓名完全一致的启用客户经理账号";
                        default -> "存在多个真实姓名完全一致的启用客户经理账号";
                    };
                    jdbc.update("""
                            INSERT INTO assignment_exception(snapshot_record_id, exception_type, reason)
                            VALUES (?, ?, ?)
                            """, snapshotRecordId, exceptionType, reason);
                    exceptions++;
                    rowWarning = true;
                } else {
                    jdbc.update("""
                            INSERT INTO assignment_history(
                                snapshot_record_id, previous_manager_user_id, new_manager_user_id,
                                change_type, changed_by_user_id, reason
                            ) VALUES (?, ?, ?, 'IMPORT_MATCH', ?, ?)
                            """, snapshotRecordId,
                            previous == null ? null : previous.effectiveManagerUserId(),
                            effectiveManagerId, actorId, "按 Excel 客户经理姓名精确匹配");
                }
                if (rowWarning) warnings++;
            }

            switchCurrentVersion(sourceType, statisticsKey, batchId, versionId, actorId);
            jdbc.update("""
                    UPDATE excel_import_batch
                    SET batch_status='CONFIRMED', confirmed_at=CURRENT_TIMESTAMP,
                        total_rows=?, valid_rows=?, warning_rows=?, error_rows=0
                    WHERE id=? AND batch_status='PRECHECKED'
                    """, rows.size(), rows.size(), warnings, batchId);
            return new ConfirmationResult(batchId, sourceType, statisticsKey,
                    rows.size(), warnings, exceptions, true, false, plan.addedRows(), plan.updatedRows(),
                    plan.unchangedRows(), plan.retainedCount());
        } catch (ConfirmationWriteException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            registerFailure(batchId, exception.getMessage());
            throw new ConfirmationWriteException(batchId, "正式快照写入失败，事务已回滚", exception);
        }
    }

    private LedgerMergePlanner.Plan loadPlan(long batchId, String sourceType, String statisticsKey) {
        List<LedgerMergePlanner.Candidate> candidates = jdbc.query("""
                SELECT id, identity_key, sheet_name, source_row_number, normalized_fields, raw_fields
                FROM excel_import_row
                WHERE batch_id=? AND identity_key IS NOT NULL
                    AND validation_status IN ('VALID','WARNING')
                ORDER BY sheet_name, source_row_number
                """, (rs, row) -> new LedgerMergePlanner.Candidate(rs.getLong("id"),
                rs.getString("identity_key"), rs.getString("sheet_name"), rs.getInt("source_row_number"),
                readJson(rs.getString("normalized_fields")), readJson(rs.getString("raw_fields"))), batchId);
        List<LedgerMergePlanner.Current> current = jdbc.query("""
                SELECT e.id AS effective_row_id, e.identity_key, e.latest_batch_id, e.snapshot_record_id,
                       e.raw_fields, e.normalized_fields, e.field_provenance,
                       s.import_row_id, s.excel_manager_name, s.effective_manager_user_id,
                       r.sheet_name, r.source_row_number
                FROM excel_dataset_period p
                JOIN excel_dataset_version_row m ON m.dataset_version_id=p.current_version_id
                JOIN excel_effective_row e ON e.id=m.effective_row_id
                JOIN excel_snapshot_record s ON s.id=e.snapshot_record_id
                JOIN excel_import_row r ON r.id=s.import_row_id AND r.batch_id=s.batch_id
                WHERE p.source_type=? AND p.statistics_key=?
                ORDER BY m.identity_key
                """, (rs, row) -> new LedgerMergePlanner.Current(
                rs.getString("identity_key"), rs.getLong("effective_row_id"), rs.getLong("latest_batch_id"),
                rs.getLong("snapshot_record_id"), rs.getLong("import_row_id"), rs.getString("sheet_name"),
                rs.getInt("source_row_number"), rs.getString("excel_manager_name"),
                nullableLong(rs, "effective_manager_user_id"), readJson(rs.getString("normalized_fields")),
                readJson(rs.getString("raw_fields")), readJson(rs.getString("field_provenance"))),
                sourceType, statisticsKey);
        return mergePlanner.plan(sourceType, candidates, current, batchId);
    }

    private long insertVersion(long batchId, String sourceType, String statisticsKey,
                               Long basedOnVersionId, long actorId, LedgerMergePlanner.Plan plan) {
        jdbc.update("""
                INSERT INTO excel_dataset_version(
                    source_type, statistics_key, source_batch_id, based_on_version_id, created_by_user_id,
                    added_rows, updated_rows, unchanged_rows, retained_rows
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, sourceType, statisticsKey, batchId, basedOnVersionId, actorId,
                plan.addedRows(), plan.updatedRows(), plan.unchangedRows(), plan.retainedCount());
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (id == null) throw new IllegalStateException("无法取得数据集版本编号");
        return id;
    }

    private void insertEffectiveRow(String sourceType, String statisticsKey,
                                    LedgerMergePlanner.RowPlan row, long batchId, long snapshotRecordId) {
        jdbc.update("""
                INSERT INTO excel_effective_row(
                    source_type, statistics_key, identity_key, latest_batch_id, snapshot_record_id,
                    raw_fields, normalized_fields, field_provenance
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, sourceType, statisticsKey, row.candidate().identityKey(), batchId, snapshotRecordId,
                writeJson(row.effectiveRawFields()), writeJson(row.effectiveNormalizedFields()),
                writeJson(row.effectiveProvenance()));
    }

    private void copyAssignmentState(long fromSnapshotRecordId, long toSnapshotRecordId) {
        jdbc.update("""
                INSERT INTO assignment_exception(
                    snapshot_record_id, exception_type, reason, status, resolution,
                    handled_by_user_id, handled_at, created_at
                )
                SELECT ?, exception_type, reason, status, resolution,
                       handled_by_user_id, handled_at, created_at
                FROM assignment_exception WHERE snapshot_record_id=?
                """, toSnapshotRecordId, fromSnapshotRecordId);
        jdbc.update("""
                INSERT INTO assignment_history(
                    snapshot_record_id, previous_manager_user_id, new_manager_user_id, change_type,
                    changed_by_user_id, reason, changed_at
                )
                SELECT ?, previous_manager_user_id, new_manager_user_id, change_type,
                       changed_by_user_id, reason, changed_at
                FROM assignment_history WHERE snapshot_record_id=?
                """, toSnapshotRecordId, fromSnapshotRecordId);
    }

    private RefreshSummary refreshPreview(long batchId, String sourceType, String statisticsKey,
                                          Long currentVersionId, long actorId) {
        LedgerMergePlanner.Plan plan = loadPlan(batchId, sourceType, statisticsKey);
        jdbc.update("DELETE FROM excel_revision_diff WHERE batch_id=?", batchId);
        for (LedgerMergePlanner.RowPlan row : plan.uploadedRows()) {
            jdbc.update("""
                    INSERT INTO excel_revision_diff(
                        batch_id, identity_key, change_type, previous_import_row_id, candidate_import_row_id,
                        previous_fields, candidate_fields, changed_fields
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, batchId, row.candidate().identityKey(), row.changeType(),
                    row.current() == null ? null : row.current().importRowId(), row.candidate().importRowId(),
                    row.current() == null ? null : writeJson(row.current().normalizedFields()),
                    writeJson(row.effectiveNormalizedFields()), writeJson(row.changedFields()));
        }
        for (LedgerMergePlanner.Current row : plan.retainedRows()) {
            jdbc.update("""
                    INSERT INTO excel_revision_diff(
                        batch_id, identity_key, change_type, previous_import_row_id, candidate_import_row_id,
                        previous_fields, candidate_fields, changed_fields
                    ) VALUES (?, ?, 'RETAINED', ?, NULL, ?, ?, JSON_ARRAY())
                    """, batchId, row.identityKey(), row.importRowId(),
                    writeJson(row.normalizedFields()), writeJson(row.normalizedFields()));
        }
        List<CandidateRow> rows = loadRows(batchId);
        long warningRows = rows.stream().filter(row -> row.warnings().isArray() && !row.warnings().isEmpty()).count();
        long exceptions = rows.stream().filter(row -> row.warnings().toString().contains("MANAGER_")).count();
        boolean duplicate = currentVersionId != null && plan.semanticNoOp();
        JsonNode summary = jdbc.queryForObject("SELECT validation_summary FROM excel_import_batch WHERE id=?",
                (rs, row) -> readJson(rs.getString(1)), batchId);
        if (summary == null || !summary.isObject()) summary = objectMapper.createObjectNode();
        ((com.fasterxml.jackson.databind.node.ObjectNode) summary).put("duplicate", duplicate);
        ((com.fasterxml.jackson.databind.node.ObjectNode) summary).put("filePreviouslyUploaded", true);
        ((com.fasterxml.jackson.databind.node.ObjectNode) summary).set("revision", objectMapper.valueToTree(
                new LedgerImportService.RevisionSummary(
                        currentVersionId == null ? null : currentSourceBatch(sourceType, statisticsKey),
                        plan.addedRows(), 0, plan.updatedRows(), plan.unchangedRows(),
                        plan.retainedCount(), List.of())));
        jdbc.update("""
                UPDATE excel_import_batch SET preview_base_version_id=?, revision_of_batch_id=?,
                    added_rows=?, removed_rows=0, changed_rows=?, unchanged_rows=?, retained_rows=?,
                    warning_rows=?, batch_status=?, validation_summary=? WHERE id=?
                """, currentVersionId, currentSourceBatch(sourceType, statisticsKey), plan.addedRows(),
                plan.updatedRows(), plan.unchangedRows(), plan.retainedCount(), warningRows,
                duplicate ? "DUPLICATE" : "PRECHECKED", writeJson(summary), batchId);
        return new RefreshSummary(warningRows, exceptions, plan.addedRows(), plan.updatedRows(),
                plan.unchangedRows(), plan.retainedCount());
    }

    private Long currentSourceBatch(String sourceType, String statisticsKey) {
        List<Long> batches = jdbc.queryForList("""
                SELECT v.source_batch_id FROM excel_dataset_period p
                JOIN excel_dataset_version v ON v.id=p.current_version_id
                WHERE p.source_type=? AND p.statistics_key=?
                """, Long.class, sourceType, statisticsKey);
        return batches.isEmpty() ? null : batches.get(0);
    }

    private Long nullableNumber(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法保存导入合并预览", exception);
        }
    }

    private record RefreshSummary(long warningRows, long assignmentExceptionRows, int addedRows,
                                  int updatedRows, int unchangedRows, int retainedRows) {}

    private void registerFailure(long batchId, String reason) {
        if (failureRecorder == null || !TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    failureRecorder.markFailed(batchId, reason);
                }
            }
        });
    }

    private List<CandidateRow> loadRows(long batchId) {
        return jdbc.query("""
                SELECT id, validation_status, normalized_fields, raw_fields, warnings
                FROM excel_import_row WHERE batch_id=?
                ORDER BY sheet_name, source_row_number
                """, (rs, rowNumber) -> new CandidateRow(rs.getLong("id"),
                rs.getString("validation_status"), rs.getString("normalized_fields"),
                readJson(rs.getString("warnings"))), batchId);
    }

    private List<Long> exactManagerMatches(String realName) {
        if (realName == null || realName.isBlank()) return List.of();
        return jdbc.queryForList("""
                SELECT id FROM app_user
                WHERE role='CUSTOMER_MANAGER' AND enabled=TRUE AND real_name=?
                ORDER BY id
                """, Long.class, realName);
    }

    private long insertSnapshotRecord(long batchId, String sourceType, long importRowId,
                                      String excelManagerName, Long effectiveManagerId) {
        jdbc.update("""
                INSERT INTO excel_snapshot_record(
                    batch_id, source_type, import_row_id, excel_manager_name, effective_manager_user_id
                ) VALUES (?, ?, ?, ?, ?)
                """, batchId, sourceType, importRowId, excelManagerName, effectiveManagerId);
        Long generated = jdbc.queryForObject(
                "SELECT id FROM excel_snapshot_record WHERE import_row_id=?", Long.class, importRowId);
        if (generated == null) throw new IllegalStateException("无法取得正式快照记录编号");
        return generated;
    }

    private void insertArrearsSnapshot(long snapshotRecordId, long batchId, JsonNode fields) {
        jdbc.update("""
                INSERT INTO arrears_snapshot(
                    snapshot_record_id, batch_id, source_type, account_id, group_code, group_name,
                    carrying_product_name, ending_arrears_amount, aging_value
                ) VALUES (?, ?, 'ARREARS', ?, ?, ?, ?, ?, ?)
                """, snapshotRecordId, batchId,
                requiredText(fields, "账户ID"), requiredText(fields, "集团编码"), requiredText(fields, "集团名称"),
                optionalText(fields, "承载产品名称"), requiredDecimal(fields, "期末欠费金额"),
                optionalText(fields, "账龄"));
    }

    private void insertLineExpirySnapshot(long snapshotRecordId, long batchId, JsonNode fields) {
        jdbc.update("""
                INSERT INTO line_expiry_snapshot(
                    snapshot_record_id, batch_id, source_type, member_service_number, group_code,
                    group_name, product_code, product_name, billing_cutoff_date
                ) VALUES (?, ?, 'LINE_EXPIRY', ?, ?, ?, ?, ?, ?)
                """, snapshotRecordId, batchId,
                requiredText(fields, "成员服务号码"), requiredText(fields, "集团编码"), requiredText(fields, "集团名称"),
                optionalText(fields, "产品编码"), optionalText(fields, "产品名称"),
                Date.valueOf(requiredDate(fields, "计费截止日期")));
    }

    private void upsertLineRenewalWorkOrder(String statisticsKey, String memberServiceNumber,
                                            long snapshotRecordId) {
        List<Long> existing = jdbc.queryForList("""
                SELECT id FROM line_renewal_work_order
                WHERE statistics_key=? AND member_service_number=? FOR UPDATE
                """, Long.class, statisticsKey, memberServiceNumber);
        if (existing.isEmpty()) {
            jdbc.update("""
                    INSERT INTO line_renewal_work_order(
                        statistics_key, member_service_number, current_snapshot_record_id, status
                    ) VALUES (?, ?, ?, '待处理')
                    """, statisticsKey, memberServiceNumber, snapshotRecordId);
        } else {
            jdbc.update("""
                    UPDATE line_renewal_work_order
                    SET current_snapshot_record_id=?
                    WHERE id=?
                    """, snapshotRecordId, existing.get(0));
        }
    }

    private void upsertArrearsWorkOrder(String statisticsKey, String accountId,
                                        long snapshotRecordId) {
        List<Long> existing = jdbc.queryForList("""
                SELECT id FROM arrears_work_order
                WHERE statistics_key=? AND account_id=? FOR UPDATE
                """, Long.class, statisticsKey, accountId);
        if (existing.isEmpty()) {
            jdbc.update("""
                    INSERT INTO arrears_work_order(
                        statistics_key, account_id, current_snapshot_record_id, status
                    ) VALUES (?, ?, ?, '待处理')
                    """, statisticsKey, accountId, snapshotRecordId);
        } else {
            jdbc.update("""
                    UPDATE arrears_work_order SET current_snapshot_record_id=? WHERE id=?
                    """, snapshotRecordId, existing.get(0));
        }
    }

    private void switchCurrentVersion(String sourceType, String statisticsKey, long batchId,
                                      long versionId, long actorId) {
        jdbc.update("""
                UPDATE excel_dataset_period
                SET current_version_id=?, changed_by_user_id=?, changed_at=CURRENT_TIMESTAMP
                WHERE source_type=? AND statistics_key=?
                """, versionId, actorId, sourceType, statisticsKey);
        List<Long> current = jdbc.queryForList("""
                SELECT batch_id FROM excel_current_version
                WHERE source_type=? AND statistics_key=? FOR UPDATE
                """, Long.class, sourceType, statisticsKey);
        if (current.isEmpty()) {
            jdbc.update("""
                    INSERT INTO excel_current_version(source_type, statistics_key, batch_id, changed_by_user_id)
                    VALUES (?, ?, ?, ?)
                    """, sourceType, statisticsKey, batchId, actorId);
        } else {
            jdbc.update("""
                    UPDATE excel_current_version
                    SET batch_id=?, changed_by_user_id=?, changed_at=CURRENT_TIMESTAMP
                    WHERE source_type=? AND statistics_key=?
                    """, batchId, actorId, sourceType, statisticsKey);
        }
    }

    private String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("快照规范化字段缺失：" + field);
        return value;
    }

    private BigDecimal requiredDecimal(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull() || !value.isNumber()) {
            throw new IllegalArgumentException("快照规范化数值字段缺失：" + field);
        }
        return value.decimalValue();
    }

    private LocalDate requiredDate(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) throw new IllegalArgumentException("快照日期字段缺失：" + field);
        if (value.isArray() && value.size() == 3) {
            return LocalDate.of(value.get(0).asInt(), value.get(1).asInt(), value.get(2).asInt());
        }
        return LocalDate.parse(value.asText());
    }

    private String optionalText(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) return objectMapper.createObjectNode();
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("无法读取预检保存的行数据", exception);
        }
    }

    private long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    private record CandidateRow(long id, String validationStatus, String normalizedFields,
                                JsonNode warnings) {}

    public record ConfirmationResult(long batchId, String sourceType, String statisticsKey,
                                     int confirmedRows, long warningRows,
                                     long assignmentExceptionRows, boolean currentVersion,
                                     boolean confirmationRequired, int addedRows, int updatedRows,
                                     int unchangedRows, int retainedRows) {
        public ConfirmationResult(long batchId, String sourceType, String statisticsKey,
                                  int confirmedRows, long warningRows,
                                  long assignmentExceptionRows, boolean currentVersion) {
            this(batchId, sourceType, statisticsKey, confirmedRows, warningRows,
                    assignmentExceptionRows, currentVersion, false, confirmedRows, 0, 0, 0);
        }
    }

    public static class ConfirmationWriteException extends RuntimeException {
        private final long batchId;

        public ConfirmationWriteException(long batchId, String message, Throwable cause) {
            super(message, cause);
            this.batchId = batchId;
        }

        public long batchId() { return batchId; }
    }
}
