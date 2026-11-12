package com.mobiletools.arrears.dataingestion.ledger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class LedgerImportService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final LedgerWorkbookParser parser;
    private final LedgerOriginalWorkbookStore workbookStore;
    private final LedgerMergePlanner mergePlanner;

    public LedgerImportService(JdbcTemplate jdbc, ObjectMapper objectMapper,
                               LedgerWorkbookParser parser, LedgerOriginalWorkbookStore workbookStore) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.parser = parser;
        this.workbookStore = workbookStore;
        this.mergePlanner = new LedgerMergePlanner(objectMapper);
    }

    @Transactional
    public UploadPreview precheck(LedgerWorkbookParser.SourceType sourceType, String originalFilename,
                                  byte[] bytes, String username) throws IOException {
        if (sourceType == null) throw new IllegalArgumentException("必须指定数据来源");
        String filename = safeFilename(originalFilename);

        // Reserve the content-addressed database row before writing the file. This
        // serializes uploads with queued deletions of a shared content-addressed file.
        LedgerOriginalWorkbookStore.StoredWorkbook identified = workbookStore.identify(bytes);
        jdbc.update("""
                INSERT INTO excel_source_file (sha256, storage_key, byte_size)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)
                """, identified.sha256(), identified.storageKey(), identified.byteSize());
        Long sourceFileId = jdbc.queryForObject(
                "SELECT id FROM excel_source_file WHERE sha256=?", Long.class, identified.sha256());
        Long storedSize = jdbc.queryForObject(
                "SELECT byte_size FROM excel_source_file WHERE id=?", Long.class, sourceFileId);
        if (storedSize == null || storedSize != identified.byteSize()) {
            throw new IllegalStateException("原始工作簿记录与文件大小不一致");
        }

        // Serialize concurrent uploads of the same content before duplicate lookup.
        jdbc.queryForObject("SELECT id FROM excel_source_file WHERE id=? FOR UPDATE", Long.class, sourceFileId);
        List<String> pendingOperations = jdbc.queryForList("""
                SELECT operation_id FROM excel_original_file_purge_queue WHERE storage_key=? FOR UPDATE
                """, String.class, identified.storageKey());
        jdbc.update("DELETE FROM excel_original_file_purge_queue WHERE storage_key=?", identified.storageKey());
        for (String operationId : pendingOperations) {
            jdbc.update("""
                    UPDATE excel_import_batch SET original_file_purge_status='PURGED'
                    WHERE original_file_purge_operation_id=? AND original_file_purge_status='PENDING'
                    """, operationId);
        }
        // Persist the exact bytes before parsing so invalid workbooks remain traceable.
        LedgerOriginalWorkbookStore.StoredWorkbook stored = workbookStore.store(bytes);
        Long userId = jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, username);
        jdbc.update("""
                INSERT INTO excel_import_batch (
                    source_type, original_filename, source_file_id, uploaded_by_user_id, batch_status
                ) VALUES (?, ?, ?, ?, 'RECEIVED')
                """, sourceType.name(), filename, sourceFileId, userId);
        Long batchId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        LedgerWorkbookParser.ParseResult parsed = addManagerMatchWarnings(parser.parse(bytes, sourceType));
        persistPreviewRows(batchId, parsed.rows());

        Integer priorAttempts = jdbc.queryForObject("""
                SELECT COUNT(*) FROM excel_import_batch
                WHERE source_type=? AND source_file_id=? AND id<>?
                    AND batch_status NOT IN ('CANCELLED', 'FAILED')
                """, Integer.class, sourceType.name(), sourceFileId, batchId);
        boolean priorFileAttempt = priorAttempts != null && priorAttempts > 0;
        Integer pendingSameFile = jdbc.queryForObject("""
                SELECT COUNT(*) FROM excel_import_batch
                WHERE source_type=? AND source_file_id=? AND id<>? AND batch_status='PRECHECKED'
                """, Integer.class, sourceType.name(), sourceFileId, batchId);
        boolean sameAsPendingPreview = pendingSameFile != null && pendingSameFile > 0;
        String status = parsed.blocking() ? "BLOCKED" : "PRECHECKED";
        RevisionSummary revision = RevisionSummary.none();
        Long baseVersionId = null;
        boolean duplicate = sameAsPendingPreview;
        if (duplicate && !parsed.blocking()) status = "DUPLICATE";
        if (!parsed.blocking() && parsed.statisticsKey() != null) {
            List<Map<String, Object>> currentVersion = jdbc.queryForList("""
                    SELECT p.current_version_id, v.source_batch_id
                    FROM excel_dataset_period p
                    JOIN excel_dataset_version v ON v.id=p.current_version_id
                    WHERE p.source_type=? AND p.statistics_key=?
                    """, sourceType.name(), parsed.statisticsKey());
            Long previousBatchId = null;
            if (!currentVersion.isEmpty()) {
                baseVersionId = number(currentVersion.get(0).get("current_version_id"));
                previousBatchId = number(currentVersion.get(0).get("source_batch_id"));
            }
            LedgerMergePlanner.Plan plan = compareDataset(batchId, sourceType.name(),
                    parsed.statisticsKey(), previousBatchId);
            revision = persistRevision(batchId, previousBatchId, plan);
            duplicate = duplicate || (baseVersionId != null && plan.semanticNoOp());
            if (duplicate) status = "DUPLICATE";
            jdbc.update("UPDATE excel_import_batch SET preview_base_version_id=? WHERE id=?",
                    baseVersionId, batchId);
        }
        long warningRows = parsed.rows().stream().filter(this::hasWarning).count();
        long errorRows = parsed.rows().stream().filter(LedgerWorkbookParser.ParsedRow::blocking).count();
        if (parsed.issues().stream().anyMatch(issue -> issue.severity() == LedgerWorkbookParser.Severity.BLOCKING)
                && errorRows == 0) errorRows = 1;

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("issues", parsed.issues());
        summary.put("sha256", stored.sha256());
        summary.put("filePreviouslyUploaded", priorFileAttempt);
        summary.put("duplicate", duplicate);
        summary.put("headers", parsed.headers());
        summary.put("revision", revision);
        jdbc.update("""
                UPDATE excel_import_batch
                SET statistics_key=?, batch_status=?, total_rows=?, valid_rows=?, warning_rows=?, error_rows=?,
                    revision_of_batch_id=?, added_rows=?, removed_rows=?, changed_rows=?, unchanged_rows=?, retained_rows=?,
                    validation_summary=?
                WHERE id=?
                """, parsed.statisticsKey(), status, parsed.rows().size(),
                parsed.rows().stream().filter(row -> !row.blocking()).count(), warningRows,
                errorRows, revision.previousBatchId(), revision.addedRows(), revision.removedRows(),
                revision.changedRows(), revision.unchangedRows(), revision.retainedRows(), writeJson(summary), batchId);

        return new UploadPreview(batchId, sourceType.name(), parsed.statisticsKey(), filename,
                stored.sha256(), stored.byteSize(), status, parsed.rows().size(),
                parsed.rows().stream().filter(row -> !row.blocking()).count(), warningRows,
                errorRows, duplicate, revision, parsed.issues(), parsed.rows());
    }

    @Transactional(readOnly = true)
    public OriginalWorkbook originalWorkbook(long batchId) throws IOException {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT b.original_filename, f.storage_key, f.sha256
                FROM excel_import_batch b
                LEFT JOIN excel_source_file f ON f.id=b.source_file_id
                WHERE b.id=?
                """, batchId);
        if (rows.isEmpty()) throw new IllegalArgumentException("导入批次不存在");
        Map<String, Object> row = rows.get(0);
        if (row.get("storage_key") == null) {
            throw new OriginalWorkbookDestroyedException();
        }
        String storageKey = String.valueOf(row.get("storage_key"));
        byte[] bytes = workbookStore.retrieve(storageKey);
        if (!workbookStoreHash(bytes).equals(String.valueOf(row.get("sha256")))) {
            throw new IOException("原始工作簿 SHA-256 校验失败");
        }
        return new OriginalWorkbook(String.valueOf(row.get("original_filename")),
                String.valueOf(row.get("sha256")), bytes);
    }

    private void persistPreviewRows(long batchId, List<LedgerWorkbookParser.ParsedRow> rows) {
        for (LedgerWorkbookParser.ParsedRow row : rows) {
            List<LedgerWorkbookParser.ValidationIssue> errors = row.issues().stream()
                    .filter(issue -> issue.severity() == LedgerWorkbookParser.Severity.BLOCKING).toList();
            List<LedgerWorkbookParser.ValidationIssue> warnings = row.issues().stream()
                    .filter(issue -> issue.severity() == LedgerWorkbookParser.Severity.WARNING).toList();
            String status = !errors.isEmpty() ? "BLOCKED" : !warnings.isEmpty() ? "WARNING" : "VALID";
            String rawFields = writeJson(row.rawFields());
            jdbc.update("""
                    INSERT INTO excel_import_row (
                        batch_id, sheet_name, source_row_number, identity_key, row_hash,
                        validation_status, raw_fields, normalized_fields, errors, warnings
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, batchId, row.sheetName(), row.rowNumber(), row.identityKey(), sha256(rawFields), status,
                    rawFields, writeJson(row.normalizedFields()),
                    writeJson(errors), writeJson(warnings));
        }
    }

    private LedgerWorkbookParser.ParseResult addManagerMatchWarnings(LedgerWorkbookParser.ParseResult parsed) {
        List<LedgerWorkbookParser.ParsedRow> rows = new ArrayList<>(parsed.rows().size());
        for (LedgerWorkbookParser.ParsedRow row : parsed.rows()) {
            List<LedgerWorkbookParser.ValidationIssue> rowIssues = new ArrayList<>(row.issues());
            if (!row.blocking() && row.excelManagerName() != null && !row.excelManagerName().isBlank()) {
                List<Long> matches = jdbc.queryForList("""
                        SELECT id FROM app_user
                        WHERE role='CUSTOMER_MANAGER' AND enabled=TRUE AND real_name=?
                        """, Long.class, row.excelManagerName());
                if (matches.size() != 1) {
                    String reason = matches.isEmpty()
                            ? "未找到真实姓名完全一致的启用客户经理账号"
                            : "存在多个真实姓名完全一致的启用客户经理账号";
                    LedgerWorkbookParser.ValidationIssue warning = new LedgerWorkbookParser.ValidationIssue(
                            matches.isEmpty() ? "MANAGER_NO_MATCH" : "MANAGER_MULTIPLE_MATCHES",
                            LedgerWorkbookParser.Severity.WARNING,
                            "客户经理“" + row.excelManagerName() + "”" + reason,
                            row.sheetName(), row.rowNumber(), null);
                    rowIssues.add(warning);
                }
            }
            rows.add(new LedgerWorkbookParser.ParsedRow(row.sheetName(), row.rowNumber(), row.statisticsKey(),
                    row.identityKey(), row.excelManagerName(), row.normalizedFields(), row.rawFields(), rowIssues));
        }
        return new LedgerWorkbookParser.ParseResult(parsed.sourceType(), parsed.statisticsKey(),
                parsed.headers(), rows, parsed.issues());
    }

    private LedgerMergePlanner.Plan compareDataset(long batchId, String sourceType, String statisticsKey,
                                                   Long previousBatchId) {
        List<LedgerMergePlanner.Candidate> candidates = jdbc.query("""
                SELECT id, identity_key, sheet_name, source_row_number, normalized_fields, raw_fields
                FROM excel_import_row
                WHERE batch_id=? AND identity_key IS NOT NULL
                    AND validation_status IN ('VALID', 'WARNING')
                ORDER BY sheet_name, source_row_number
                """, (rs, row) -> new LedgerMergePlanner.Candidate(rs.getLong("id"),
                rs.getString("identity_key"), rs.getString("sheet_name"), rs.getInt("source_row_number"),
                readJson(rs.getString("normalized_fields")), readJson(rs.getString("raw_fields"))), batchId);
        List<LedgerMergePlanner.Current> current = previousBatchId == null ? List.of() : jdbc.query("""
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

    private RevisionSummary persistRevision(long batchId, Long previousBatchId,
                                            LedgerMergePlanner.Plan plan) {
        List<RevisionDiff> differences = new ArrayList<>();
        for (LedgerMergePlanner.RowPlan row : plan.uploadedRows()) {
            LedgerMergePlanner.Current previous = row.current();
            LedgerMergePlanner.Candidate candidate = row.candidate();
            String previousJson = previous == null ? null : writeJson(previous.normalizedFields());
            String candidateJson = writeJson(row.effectiveNormalizedFields());
            String changedJson = writeJson(row.changedFields());
            jdbc.update("""
                    INSERT INTO excel_revision_diff (
                        batch_id, identity_key, change_type, previous_import_row_id, candidate_import_row_id,
                        previous_fields, candidate_fields, changed_fields
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, batchId, candidate.identityKey(), row.changeType(),
                    previous == null ? null : previous.importRowId(), candidate.importRowId(),
                    previousJson, candidateJson, changedJson);
            differences.add(new RevisionDiff(candidate.identityKey(), row.changeType(),
                    previous == null ? null : previous.importRowId(), candidate.importRowId(),
                    previous == null ? null : previous.normalizedFields(),
                    row.effectiveNormalizedFields(), row.changedFields()));
        }
        for (LedgerMergePlanner.Current retained : plan.retainedRows()) {
            JsonNode noChanges = objectMapper.createArrayNode();
            jdbc.update("""
                    INSERT INTO excel_revision_diff (
                        batch_id, identity_key, change_type, previous_import_row_id, candidate_import_row_id,
                        previous_fields, candidate_fields, changed_fields
                    ) VALUES (?, ?, 'RETAINED', ?, NULL, ?, ?, ?)
                    """, batchId, retained.identityKey(), retained.importRowId(),
                    writeJson(retained.normalizedFields()), writeJson(retained.normalizedFields()), writeJson(noChanges));
            differences.add(new RevisionDiff(retained.identityKey(), "RETAINED", retained.importRowId(), null,
                    retained.normalizedFields(), retained.normalizedFields(), noChanges));
        }
        return new RevisionSummary(previousBatchId, plan.addedRows(), 0,
                plan.updatedRows(), plan.unchangedRows(), plan.retainedCount(), differences);
    }

    private long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 运行环境不支持 SHA-256", exception);
        }
    }

    private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private boolean hasWarning(LedgerWorkbookParser.ParsedRow row) {
        return row.issues().stream().anyMatch(issue -> issue.severity() == LedgerWorkbookParser.Severity.WARNING);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法保存导入预览数据", exception);
        }
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) return objectMapper.createObjectNode();
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("导入合并字段 JSON 无法读取", exception);
        }
    }

    private String safeFilename(String filename) {
        if (filename == null || filename.isBlank()) return "workbook.xlsx";
        String basename = filename.replace('\\', '/');
        basename = basename.substring(basename.lastIndexOf('/') + 1).strip();
        if (basename.isEmpty()) return "workbook.xlsx";
        return basename.length() > 255 ? basename.substring(basename.length() - 255) : basename;
    }

    private String workbookStoreHash(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 运行环境不支持 SHA-256", exception);
        }
    }

    public record UploadPreview(long batchId, String sourceType, String statisticsKey,
                                String originalFilename, String fileSha256, long byteSize,
                                String status, int totalRows, long validRows, long warningRows,
                                long errorRows, boolean duplicate, RevisionSummary revision,
                                List<LedgerWorkbookParser.ValidationIssue> issues,
                                List<LedgerWorkbookParser.ParsedRow> rows) {
        public UploadPreview {
            issues = List.copyOf(issues);
            rows = List.copyOf(rows);
        }
    }

    public record RevisionSummary(Long previousBatchId, int addedRows, int removedRows,
                                  int changedRows, int unchangedRows, int retainedRows,
                                  List<RevisionDiff> differences) {
        public RevisionSummary {
            differences = List.copyOf(differences);
        }

        static RevisionSummary none() {
            return new RevisionSummary(null, 0, 0, 0, 0, 0, List.of());
        }
    }

    public record RevisionDiff(String identityKey, String changeType,
                               Long previousImportRowId, Long candidateImportRowId,
                               JsonNode previousFields, JsonNode candidateFields, JsonNode changedFields) {}

    public record OriginalWorkbook(String filename, String sha256, byte[] bytes) {}
}
