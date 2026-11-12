package com.mobiletools.arrears.dataingestion.ledger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/** Administrator queries for row-level manager matching exceptions. */
@Service
public class AssignmentExceptionQueryService {

    private static final String FROM = """
            FROM assignment_exception e
            JOIN excel_snapshot_record s ON s.id=e.snapshot_record_id
            JOIN excel_current_dataset cv ON cv.snapshot_record_id=s.id AND cv.source_type=s.source_type
            JOIN excel_import_batch b ON b.id=s.batch_id AND b.source_type=s.source_type
            JOIN excel_import_row r ON r.id=s.import_row_id AND r.batch_id=s.batch_id
            LEFT JOIN arrears_snapshot a ON a.snapshot_record_id=s.id AND s.source_type='ARREARS'
            LEFT JOIN line_expiry_snapshot l ON l.snapshot_record_id=s.id AND s.source_type='LINE_EXPIRY'
            LEFT JOIN app_user u ON u.id=s.effective_manager_user_id
            """;

    private static final String COLUMNS = """
            e.id AS exception_id, e.exception_type, e.reason, e.status, e.resolution,
            e.handled_by_user_id, e.handled_at, e.created_at,
            s.id AS snapshot_record_id, s.excel_manager_name, s.effective_manager_user_id,
            u.real_name AS effective_manager_name,
            b.source_type, b.statistics_key, b.id AS batch_id, b.original_filename,
            r.id AS import_row_id, r.sheet_name, r.source_row_number, r.identity_key,
            COALESCE(a.group_code, l.group_code) AS group_code,
            COALESCE(a.group_name, l.group_name) AS group_name,
            cv.effective_raw_fields AS raw_fields, cv.effective_normalized_fields AS normalized_fields,
            r.errors, r.warnings
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AssignmentExceptionQueryService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Page list(String sourceType, String statisticsKey, String groupCode, String groupName,
                     String excelManagerName, Long batchId, String status, int page, int pageSize) {
        validatePage(page, pageSize);
        validateSourceAndDate(sourceType, statisticsKey);
        validateStatus(status);
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        exact(where, args, "b.source_type", sourceType);
        exact(where, args, "b.statistics_key", statisticsKey);
        contains(where, args, "COALESCE(a.group_code, l.group_code)", groupCode);
        contains(where, args, "COALESCE(a.group_name, l.group_name)", groupName);
        contains(where, args, "s.excel_manager_name", excelManagerName);
        exact(where, args, "b.id", batchId);
        exact(where, args, "e.status", status);

        Long total = jdbc.queryForObject("SELECT COUNT(*) " + FROM + where, Long.class, args.toArray());
        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.add(pageSize);
        queryArgs.add((long) (page - 1) * pageSize);
        List<ExceptionItem> items = jdbc.query("SELECT " + COLUMNS + FROM + where
                        + " ORDER BY e.created_at DESC, e.id DESC LIMIT ? OFFSET ?",
                (rs, row) -> mapItem(rs), queryArgs.toArray());
        return new Page(items, total == null ? 0 : total, page, pageSize);
    }

    @Transactional(readOnly = true)
    public ExceptionDetail detail(long id) {
        List<ExceptionDetail> matches = jdbc.query("SELECT " + COLUMNS + FROM
                        + " WHERE e.id=?", (rs, row) -> new ExceptionDetail(mapItem(rs),
                        readJson(rs.getString("raw_fields")), readJson(rs.getString("normalized_fields")),
                        readJson(rs.getString("errors")), readJson(rs.getString("warnings")), List.of()), id);
        if (matches.isEmpty()) throw new IllegalArgumentException("归属匹配异常不存在");
        ExceptionDetail detail = matches.get(0);
        List<AssignmentHistory> history = jdbc.query("""
                SELECT h.previous_manager_user_id, previous.real_name AS previous_manager_name,
                       h.new_manager_user_id, current.real_name AS new_manager_name,
                       h.changed_by_user_id, actor.username AS changed_by_username,
                       h.change_type, h.reason, h.changed_at
                FROM assignment_history h
                LEFT JOIN app_user previous ON previous.id=h.previous_manager_user_id
                JOIN app_user current ON current.id=h.new_manager_user_id
                JOIN app_user actor ON actor.id=h.changed_by_user_id
                WHERE h.snapshot_record_id=? ORDER BY h.changed_at, h.id
                """, (rs, row) -> new AssignmentHistory(nullableLong(rs, "previous_manager_user_id"),
                rs.getString("previous_manager_name"), rs.getLong("new_manager_user_id"),
                rs.getString("new_manager_name"), rs.getLong("changed_by_user_id"),
                rs.getString("changed_by_username"), rs.getString("change_type"), rs.getString("reason"),
                rs.getTimestamp("changed_at")), detail.item().snapshotRecordId());
        return new ExceptionDetail(detail.item(), detail.rawFields(), detail.normalizedFields(),
                detail.errors(), detail.warnings(), history);
    }

    public List<Long> filteredSnapshotRecordIds(String sourceType, String statisticsKey, String groupCode,
                                                String groupName, String excelManagerName, Long batchId,
                                                String status) {
        if (sourceType == null) throw new IllegalArgumentException("全筛选结果分配时必须指定数据来源");
        validateSourceAndDate(sourceType, statisticsKey);
        validateStatus(status);
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        exact(where, args, "b.source_type", sourceType);
        exact(where, args, "b.statistics_key", statisticsKey);
        contains(where, args, "COALESCE(a.group_code, l.group_code)", groupCode);
        contains(where, args, "COALESCE(a.group_name, l.group_name)", groupName);
        contains(where, args, "s.excel_manager_name", excelManagerName);
        exact(where, args, "b.id", batchId);
        exact(where, args, "e.status", status);
        List<Long> ids = jdbc.queryForList("SELECT DISTINCT s.id " + FROM + where
                + " ORDER BY s.id", Long.class, args.toArray());
        return ids;
    }

    private ExceptionItem mapItem(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ExceptionItem(rs.getLong("exception_id"), rs.getLong("snapshot_record_id"),
                rs.getString("source_type"),
                rs.getString("statistics_key"), rs.getString("group_code"), rs.getString("group_name"),
                rs.getString("excel_manager_name"), rs.getString("reason"), rs.getLong("batch_id"),
                rs.getString("original_filename"), rs.getLong("import_row_id"), rs.getString("sheet_name"),
                rs.getInt("source_row_number"), rs.getString("identity_key"), rs.getString("exception_type"),
                rs.getString("status"), rs.getString("resolution"),
                nullableLong(rs, "effective_manager_user_id"), rs.getString("effective_manager_name"),
                nullableLong(rs, "handled_by_user_id"), rs.getTimestamp("handled_at"), rs.getTimestamp("created_at"));
    }

    private JsonNode readJson(String value) {
        if (value == null || value.isBlank()) return objectMapper.createObjectNode();
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("归属异常保存的原始字段无法读取", exception);
        }
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static void exact(StringBuilder where, List<Object> args, String column, Object value) {
        if (value != null && (!(value instanceof String text) || !text.isBlank())) {
            where.append(" AND ").append(column).append("=?");
            args.add(value);
        }
    }

    private static void contains(StringBuilder where, List<Object> args, String column, String value) {
        if (value != null && !value.isBlank()) {
            where.append(" AND ").append(column).append(" LIKE ?");
            args.add("%" + value.strip() + "%");
        }
    }

    private static void validatePage(int page, int pageSize) {
        if (page < 1) throw new IllegalArgumentException("page 必须为正数");
        if (pageSize != 20 && pageSize != 50 && pageSize != 100) {
            throw new IllegalArgumentException("pageSize 仅支持 20、50 或 100");
        }
    }

    private static void validateSourceAndDate(String sourceType, String statisticsKey) {
        if (sourceType != null && !List.of("ARREARS", "LINE_EXPIRY").contains(sourceType)) {
            throw new IllegalArgumentException("sourceType 必须为 ARREARS 或 LINE_EXPIRY");
        }
        if (statisticsKey == null) return;
        if (sourceType == null && !statisticsKey.matches("(?:[0-9]{6}|[0-9]{8})")) {
            throw new IllegalArgumentException("statisticsKey 格式无效");
        }
        if ("ARREARS".equals(sourceType) && !statisticsKey.matches("[0-9]{6}")) {
            throw new IllegalArgumentException("欠费统计日期应为 yyyyMM");
        }
        if ("LINE_EXPIRY".equals(sourceType) && !statisticsKey.matches("[0-9]{8}")) {
            throw new IllegalArgumentException("专线统计日期应为 yyyyMMdd");
        }
    }

    private static void validateStatus(String status) {
        if (status != null && !List.of("OPEN", "RESOLVED").contains(status)) {
            throw new IllegalArgumentException("异常处理状态无效");
        }
    }

    public record Page(List<ExceptionItem> items, long total, int page, int pageSize) {
        public Page { items = List.copyOf(items); }
    }

    public record ExceptionItem(long id, long snapshotRecordId, String sourceType, String statisticsKey, String groupCode,
                                String groupName, String excelManagerName, String reason, long batchId,
                                String originalFilename, long importRowId, String sheetName,
                                int sourceRowNumber, String identityKey, String exceptionType, String status,
                                String resolution, Long effectiveManagerUserId, String effectiveManagerName,
                                Long handledByUserId, Timestamp handledAt, Timestamp createdAt) {}

    public record ExceptionDetail(ExceptionItem item, JsonNode rawFields, JsonNode normalizedFields,
                                  JsonNode errors, JsonNode warnings, List<AssignmentHistory> assignmentHistory) {
        public ExceptionDetail {
            assignmentHistory = List.copyOf(assignmentHistory);
        }
    }
    public record AssignmentHistory(Long previousManagerUserId, String previousManagerName,
                                    long newManagerUserId, String newManagerName, long changedByUserId,
                                    String changedByUsername, String changeType, String reason, Timestamp changedAt) {}
}
