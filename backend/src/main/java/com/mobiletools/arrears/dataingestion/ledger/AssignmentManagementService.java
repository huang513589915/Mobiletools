package com.mobiletools.arrears.dataingestion.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Applies row-scoped manager assignment changes without changing Excel source fields. */
@Service
public class AssignmentManagementService {

    private final JdbcTemplate jdbc;
    private final AssignmentExceptionQueryService exceptionQueries;

    public AssignmentManagementService(JdbcTemplate jdbc) {
        this(jdbc, null);
    }

    @Autowired
    public AssignmentManagementService(JdbcTemplate jdbc, AssignmentExceptionQueryService exceptionQueries) {
        this.jdbc = jdbc;
        this.exceptionQueries = exceptionQueries;
    }

    @Transactional
    public AssignmentResult assignFiltered(String sourceType, String statisticsKey, String groupCode,
                                           String groupName, String excelManagerName, Long batchId,
                                           String status, long targetManagerUserId, long actorUserId,
                                           String reason) {
        validateSource(sourceType);
        if (exceptionQueries == null) throw new IllegalStateException("异常查询服务未配置");
        List<Long> ids = exceptionQueries.filteredSnapshotRecordIds(sourceType, statisticsKey, groupCode,
                groupName, excelManagerName, batchId, status);
        return assign(sourceType, ids, targetManagerUserId, actorUserId, reason);
    }

    @Transactional
    public AssignmentResult assign(String sourceType, List<Long> snapshotRecordIds,
                                   long targetManagerUserId, long actorUserId, String reason) {
        validateSource(sourceType);
        if (snapshotRecordIds == null || snapshotRecordIds.isEmpty()
                || snapshotRecordIds.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(snapshotRecordIds).size() != snapshotRecordIds.size()) {
            throw new IllegalArgumentException("snapshotRecordIds 必须包含不重复的有效行编号");
        }
        String note = reason == null || reason.isBlank() ? "管理员手动分配归属" : reason.strip();
        if (note.length() > 1000) throw new IllegalArgumentException("reason 长度不能超过 1000");

        List<Long> managers = jdbc.queryForList("""
                SELECT id FROM app_user
                WHERE id=? AND role='CUSTOMER_MANAGER' AND enabled=TRUE
                FOR UPDATE
                """, Long.class, targetManagerUserId);
        if (managers.isEmpty()) throw new IllegalArgumentException("目标客户经理账号不存在、停用或角色无效");

        List<Long> sortedIds = snapshotRecordIds.stream().sorted().toList();
        List<Row> rows = new ArrayList<>();
        for (Long id : sortedIds) {
            List<Row> matches = jdbc.query("""
                    SELECT id, source_type, effective_manager_user_id
                    FROM excel_snapshot_record WHERE id=? FOR UPDATE
                    """, (rs, row) -> new Row(rs.getLong("id"), rs.getString("source_type"),
                    nullableLong(rs, "effective_manager_user_id")), id);
            if (matches.isEmpty()) throw new IllegalArgumentException("归属记录不存在：" + id);
            Row row = matches.get(0);
            if (!sourceType.equals(row.sourceType())) {
                throw new IllegalArgumentException("一次批量分配只能包含同一种数据来源");
            }
            rows.add(row);
        }

        for (Row row : rows) {
            Long previous = row.effectiveManagerUserId();
            jdbc.update("UPDATE excel_snapshot_record SET effective_manager_user_id=? WHERE id=?",
                    targetManagerUserId, row.id());
            List<Long> exceptions = jdbc.queryForList(
                    "SELECT id FROM assignment_exception WHERE snapshot_record_id=? FOR UPDATE",
                    Long.class, row.id());
            if (!exceptions.isEmpty()) {
                jdbc.update("""
                        UPDATE assignment_exception
                        SET status='RESOLVED', resolution=?, handled_by_user_id=?, handled_at=CURRENT_TIMESTAMP
                        WHERE snapshot_record_id=?
                        """, note, actorUserId, row.id());
            }
            jdbc.update("""
                    INSERT INTO assignment_history(
                        snapshot_record_id, previous_manager_user_id, new_manager_user_id,
                        change_type, changed_by_user_id, reason
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """, row.id(), previous, targetManagerUserId,
                    previous == null ? "MANUAL_ASSIGNMENT" : "REASSIGNMENT", actorUserId, note);
        }
        jdbc.update("""
                INSERT INTO operation_audit_log(operator_user_id,operation_type,target_type,target_id,result,detail)
                VALUES (?,?,?,?,?,?)
                """, actorUserId, "ASSIGNMENT_BULK_UPDATE", "ASSIGNMENT_EXCEPTION", null, "SUCCESS",
                "sourceType=" + sourceType + ", selectedCount=" + rows.size() + ", targetManagerUserId=" + targetManagerUserId);
        return new AssignmentResult(sourceType, rows.size(), targetManagerUserId);
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static void validateSource(String sourceType) {
        if (sourceType == null || !List.of("ARREARS", "LINE_EXPIRY").contains(sourceType)) {
            throw new IllegalArgumentException("sourceType 必须为 ARREARS 或 LINE_EXPIRY");
        }
    }

    private record Row(long id, String sourceType, Long effectiveManagerUserId) {}

    public record AssignmentResult(String sourceType, int selectedCount, long targetManagerUserId) {}
}
