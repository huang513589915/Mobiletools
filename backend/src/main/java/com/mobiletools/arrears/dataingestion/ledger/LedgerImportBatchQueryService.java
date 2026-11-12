package com.mobiletools.arrears.dataingestion.ledger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only administrator views for import attempts and their row-level validation details. */
@Service
public class LedgerImportBatchQueryService {

    private static final List<String> STATUSES = List.of(
            "RECEIVED", "PRECHECKED", "BLOCKED", "DUPLICATE", "CONFIRMED", "FAILED", "CANCELLED");
    private static final RowMapper<BatchListItem> BATCH_MAPPER = LedgerImportBatchQueryService::mapBatch;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public LedgerImportBatchQueryService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Page<BatchListItem> list(String sourceType, String statisticsKey, String status,
                                    int page, int pageSize) {
        if (page < 1 || page > 1_000_000) throw new IllegalArgumentException("page 超出有效范围");
        if (pageSize != 20 && pageSize != 50 && pageSize != 100) {
            throw new IllegalArgumentException("pageSize 仅支持 20、50 或 100");
        }
        if (sourceType != null && !List.of("ARREARS", "LINE_EXPIRY").contains(sourceType)) {
            throw new IllegalArgumentException("sourceType 必须为 ARREARS 或 LINE_EXPIRY");
        }
        if (status != null && !STATUSES.contains(status)) {
            throw new IllegalArgumentException("batch status 无效");
        }
        validateStatisticsKey(sourceType, statisticsKey);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (sourceType != null) { where.append(" AND b.source_type=?"); args.add(sourceType); }
        if (statisticsKey != null) { where.append(" AND b.statistics_key=?"); args.add(statisticsKey); }
        if (status != null) { where.append(" AND b.batch_status=?"); args.add(status); }
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM excel_import_batch b" + where, Integer.class,
                args.toArray());
        long total = count == null ? 0 : count;
        int offset = Math.multiplyExact(page - 1, pageSize);
        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.add(pageSize);
        queryArgs.add(offset);
        List<BatchListItem> items = jdbc.query(batchSelect() + where
                        + " GROUP BY b.id ORDER BY b.uploaded_at DESC, b.id DESC LIMIT ? OFFSET ?",
                BATCH_MAPPER, queryArgs.toArray());
        return new Page<>(items, page, pageSize, total);
    }

    @Transactional(readOnly = true)
    public BatchDetail detail(long batchId, int rowPage, int pageSize) {
        if (rowPage < 1 || rowPage > 1_000_000) throw new IllegalArgumentException("rowPage 超出有效范围");
        if (pageSize != 20 && pageSize != 50 && pageSize != 100) {
            throw new IllegalArgumentException("pageSize 仅支持 20、50 或 100");
        }
        List<BatchListItem> matches = jdbc.query(batchSelect() + " WHERE b.id=? GROUP BY b.id", BATCH_MAPPER, batchId);
        if (matches.isEmpty()) throw new IllegalArgumentException("导入批次不存在");
        BatchListItem batch = matches.get(0);
        String summaryText = jdbc.queryForObject(
                "SELECT validation_summary FROM excel_import_batch WHERE id=?", String.class, batchId);
        JsonNode summary = readJson(summaryText);

        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM excel_import_row WHERE batch_id=?", Integer.class, batchId);
        long totalRows = rowCount == null ? 0 : rowCount;
        int offset = Math.multiplyExact(rowPage - 1, pageSize);
        List<RowDetail> rows = jdbc.query("""
                SELECT r.id, r.sheet_name, r.source_row_number, r.identity_key, r.validation_status,
                       r.raw_fields, r.normalized_fields, r.errors, r.warnings,
                       e.exception_type AS assignment_exception_type,
                       e.reason AS assignment_exception_reason,
                       e.status AS assignment_exception_status
                FROM excel_import_row r
                LEFT JOIN excel_snapshot_record s ON s.import_row_id=r.id
                LEFT JOIN assignment_exception e ON e.snapshot_record_id=s.id
                WHERE r.batch_id=?
                ORDER BY r.sheet_name, r.source_row_number LIMIT ? OFFSET ?
                """, (rs, rowNumber) -> new RowDetail(rs.getLong("id"), rs.getString("sheet_name"),
                rs.getInt("source_row_number"), rs.getString("identity_key"),
                rs.getString("validation_status"), readJson(rs.getString("raw_fields")),
                readJson(rs.getString("normalized_fields")), readJson(rs.getString("errors")),
                readJson(rs.getString("warnings")), rs.getString("assignment_exception_type"),
                rs.getString("assignment_exception_reason"), rs.getString("assignment_exception_status")),
                batchId, pageSize, offset);

        List<SheetSummary> sheets = sheetSummaries(batchId, summary);
        List<RevisionDiff> differences = jdbc.query("""
                SELECT identity_key, change_type, previous_import_row_id, candidate_import_row_id
                       , previous_fields, candidate_fields, changed_fields
                FROM excel_revision_diff WHERE batch_id=? ORDER BY id LIMIT ? OFFSET ?
                """, (rs, rowNumber) -> new RevisionDiff(rs.getString("identity_key"),
                rs.getString("change_type"), nullableLong(rs, "previous_import_row_id"),
                nullableLong(rs, "candidate_import_row_id"), readJson(rs.getString("previous_fields")),
                readJson(rs.getString("candidate_fields")), readJson(rs.getString("changed_fields"))),
                batchId, pageSize, offset);
        Integer diffCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM excel_revision_diff WHERE batch_id=?", Integer.class, batchId);
        return new BatchDetail(batch, summary, sheets, rows,
                new PageInfo(rowPage, pageSize, totalRows), differences,
                diffCount == null ? 0 : diffCount);
    }

    private List<SheetSummary> sheetSummaries(long batchId, JsonNode summary) {
        Map<String, SheetSummary> byName = new LinkedHashMap<>();
        jdbc.query("""
                SELECT sheet_name, COUNT(*) AS row_count,
                       MIN(source_row_number) AS first_row_number,
                       MAX(source_row_number) AS last_row_number
                FROM excel_import_row WHERE batch_id=?
                GROUP BY sheet_name ORDER BY MIN(id)
                """, rs -> {
            String name = rs.getString("sheet_name");
            byName.put(name, new SheetSummary(name, rs.getInt("row_count"),
                    nullableInt(rs, "first_row_number"), nullableInt(rs, "last_row_number")));
        }, batchId);
        JsonNode issues = summary.path("issues");
        if (issues.isArray()) {
            for (JsonNode issue : issues) {
                String name = text(issue, "sheetName");
                if (name != null && !byName.containsKey(name)) {
                    byName.put(name, new SheetSummary(name, 0, null, null));
                }
            }
        }
        return List.copyOf(byName.values());
    }

    private static String batchSelect() {
        return """
                SELECT b.id AS batch_id, b.source_type, b.statistics_key, b.original_filename,
                       f.sha256, f.byte_size, u.username AS uploader_username, b.uploaded_at,
                       b.confirmed_at, b.batch_status, b.total_rows, b.valid_rows, b.warning_rows,
                       b.error_rows, b.added_rows, b.removed_rows, b.changed_rows, b.unchanged_rows,
                       b.retained_rows, b.erased_rows, b.original_file_purge_status,
                       (b.source_file_id IS NOT NULL) AS original_file_available,
                       COUNT(DISTINCT cv.snapshot_record_id) AS contributing_rows,
                       CASE WHEN COUNT(DISTINCT cv.snapshot_record_id)=0 THEN 'NONE'
                            WHEN COUNT(DISTINCT cv.snapshot_record_id)>=b.valid_rows THEN 'ALL'
                            ELSE 'PARTIAL' END AS contribution_status
                FROM excel_import_batch b
                LEFT JOIN excel_source_file f ON f.id=b.source_file_id
                JOIN app_user u ON u.id=b.uploaded_by_user_id
                LEFT JOIN excel_current_dataset cv ON cv.batch_id=b.id
                    AND cv.source_type=b.source_type AND cv.statistics_key=b.statistics_key
                """;
    }

    private static BatchListItem mapBatch(ResultSet rs, int rowNumber) throws SQLException {
        return new BatchListItem(rs.getLong("batch_id"), rs.getString("source_type"),
                rs.getString("statistics_key"), rs.getString("original_filename"),
                rs.getString("sha256"), nullableLong(rs, "byte_size"), rs.getString("uploader_username"),
                rs.getTimestamp("uploaded_at"), rs.getTimestamp("confirmed_at"),
                rs.getString("batch_status"),
                rs.getInt("contributing_rows"), rs.getString("contribution_status"),
                rs.getInt("total_rows"), rs.getInt("valid_rows"), rs.getInt("warning_rows"),
                rs.getInt("error_rows"), rs.getInt("added_rows"), rs.getInt("removed_rows"),
                rs.getInt("changed_rows"), rs.getInt("unchanged_rows"), rs.getInt("retained_rows"),
                rs.getInt("erased_rows"), rs.getBoolean("original_file_available"),
                rs.getString("original_file_purge_status"));
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) return objectMapper.createObjectNode();
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("导入批次保存的 JSON 数据无法读取", exception);
        }
    }

    private void validateStatisticsKey(String sourceType, String key) {
        if (key == null) return;
        if (sourceType == null) {
            if (!key.matches("(?:[0-9]{6}|[0-9]{8})")) throw new IllegalArgumentException("statisticsKey 格式无效");
        } else if ("ARREARS".equals(sourceType) && !key.matches("[0-9]{6}")) {
            throw new IllegalArgumentException("欠费统计日期应为 yyyyMM");
        } else if ("LINE_EXPIRY".equals(sourceType) && !key.matches("[0-9]{8}")) {
            throw new IllegalArgumentException("专线统计日期应为 yyyyMMdd");
        }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    public record Page<T>(List<T> items, int page, int pageSize, long total) {
        public Page { items = List.copyOf(items); }
    }
    public record PageInfo(int page, int pageSize, long total) {}
    public record BatchListItem(long batchId, String sourceType, String statisticsKey,
                                String originalFilename, String sha256, Long originalFileSize,
                                String uploaderUsername, Timestamp uploadedAt, Timestamp confirmedAt,
                                String status, int contributingRows, String contributionStatus,
                                int totalRows, int validRows,
                                int warningRows, int errorRows, int addedRows, int removedRows,
                                int changedRows, int unchangedRows, int retainedRows,
                                int erasedRows, boolean originalFileAvailable,
                                String originalFilePurgeStatus) {}
    public record SheetSummary(String sheetName, int rowCount, Integer firstRowNumber, Integer lastRowNumber) {}
    public record RowDetail(long importRowId, String sheetName, int sourceRowNumber,
                            String identityKey, String validationStatus, JsonNode rawFields,
                            JsonNode normalizedFields, JsonNode errors, JsonNode warnings,
                            String assignmentExceptionType, String assignmentExceptionReason,
                            String assignmentExceptionStatus) {}
    public record RevisionDiff(String identityKey, String changeType,
                               Long previousImportRowId, Long candidateImportRowId,
                               JsonNode previousFields, JsonNode candidateFields, JsonNode changedFields) {}
    public record BatchDetail(BatchListItem batch, JsonNode validationSummary, List<SheetSummary> sheets,
                              List<RowDetail> rows, PageInfo rowPage, List<RevisionDiff> revisionDifferences,
                              int revisionDifferenceTotal) {
        public BatchDetail {
            sheets = List.copyOf(sheets);
            rows = List.copyOf(rows);
            revisionDifferences = List.copyOf(revisionDifferences);
        }
    }
}
