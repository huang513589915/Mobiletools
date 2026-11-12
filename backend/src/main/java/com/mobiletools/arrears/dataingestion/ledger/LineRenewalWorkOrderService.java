package com.mobiletools.arrears.dataingestion.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Handles user initiated status changes for current line-expiry work orders. */
@Service
public class LineRenewalWorkOrderService {
    private static final List<String> STATUSES = List.of("待处理", "跟进中", "已续签", "不续签");
    private final JdbcTemplate jdbc;

    public LineRenewalWorkOrderService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public UpdateResult updateStatus(long workOrderId, String newStatus,
                                     SnapshotAccessScopeService.Scope scope) {
        return updateHandling(workOrderId, newStatus, null, scope);
    }

    @Transactional
    public UpdateResult updateHandling(long workOrderId, String requestedStatus, String requestedNote,
                                       SnapshotAccessScopeService.Scope scope) {
        if (workOrderId <= 0) throw new IllegalArgumentException("workOrderId 必须为正数");
        List<WorkOrderRow> rows = jdbc.query("""
                SELECT w.id, w.status, w.last_handled_by_user_id, last_handler.real_name AS last_handled_by_name,
                       w.last_handled_at, s.effective_manager_user_id
                FROM line_renewal_work_order w
                JOIN excel_snapshot_record s ON s.id=w.current_snapshot_record_id
                    AND s.source_type='LINE_EXPIRY'
                JOIN line_expiry_snapshot l ON l.snapshot_record_id=s.id
                JOIN excel_current_dataset cv ON cv.source_type='LINE_EXPIRY'
                    AND cv.statistics_key=w.statistics_key AND cv.snapshot_record_id=s.id
                LEFT JOIN app_user last_handler ON last_handler.id=w.last_handled_by_user_id
                WHERE w.id=? FOR UPDATE
                """, (rs, row) -> new WorkOrderRow(rs.getLong("id"), rs.getString("status"),
                nullableLong(rs, "last_handled_by_user_id"), rs.getString("last_handled_by_name"),
                nullableDateTime(rs, "last_handled_at"),
                nullableLong(rs, "effective_manager_user_id")), workOrderId);
        if (rows.isEmpty()) throw new AccessDeniedException("工单不存在或不属于当前专线版本");
        WorkOrderRow row = rows.get(0);
        if (!scope.administrator() && !Long.valueOf(scope.userId()).equals(row.effectiveManagerUserId())) {
            throw new AccessDeniedException("无权处理该专线工单");
        }
        String newStatus = requestedStatus == null || requestedStatus.isBlank()
                ? row.status() : requestedStatus.strip();
        if (!STATUSES.contains(newStatus)) throw new IllegalArgumentException("续签处理状态无效");
        String note = requestedNote == null ? null : requestedNote.strip();
        if (note != null && note.isEmpty()) note = null;
        if (note != null && !"跟进中".equals(newStatus)) {
            throw new IllegalArgumentException("仅工单处于跟进中时可以填写跟进说明");
        }
        boolean statusChanged = !row.status().equals(newStatus);
        if (!statusChanged && note == null) {
            return new UpdateResult(workOrderId, row.status(), row.lastHandledByUserId(),
                    row.lastHandledByName(), row.lastHandledAt(), false, false, false);
        }

        if (statusChanged) {
            jdbc.update("""
                    INSERT INTO line_renewal_status_history(
                        work_order_id, previous_status, new_status, changed_by_user_id
                    ) VALUES (?, ?, ?, ?)
                    """, workOrderId, row.status(), newStatus, scope.userId());
        }
        if (note != null) {
            jdbc.update("""
                    INSERT INTO line_renewal_work_order_follow_up(work_order_id, note, created_by_user_id)
                    VALUES (?, ?, ?)
                    """, workOrderId, note, scope.userId());
        }
        jdbc.update("""
                UPDATE line_renewal_work_order
                SET status=?, last_handled_by_user_id=?, last_handled_at=CURRENT_TIMESTAMP
                WHERE id=?
                """, newStatus, scope.userId(), workOrderId);
        Timestamp timestamp = jdbc.queryForObject(
                "SELECT last_handled_at FROM line_renewal_work_order WHERE id=?", Timestamp.class, workOrderId);
        String handledByName = jdbc.queryForObject("SELECT real_name FROM app_user WHERE id=?",
                String.class, scope.userId());
        return new UpdateResult(workOrderId, newStatus, scope.userId(), handledByName,
                timestamp == null ? null : timestamp.toLocalDateTime(), true, statusChanged, note != null);
    }

    @Transactional(readOnly = true)
    public List<TimelineEvent> timeline(long workOrderId) {
        List<TimelineEvent> events = new ArrayList<>();
        events.addAll(jdbc.query("""
                SELECT h.id, h.previous_status, h.new_status, h.changed_by_user_id,
                       u.real_name AS actor_name, h.changed_at
                FROM line_renewal_status_history h
                LEFT JOIN app_user u ON u.id=h.changed_by_user_id
                WHERE h.work_order_id=?
                """, (rs, row) -> new TimelineEvent(rs.getLong("id"), "STATUS",
                rs.getString("previous_status"), rs.getString("new_status"), null,
                rs.getLong("changed_by_user_id"), rs.getString("actor_name"),
                rs.getTimestamp("changed_at").toLocalDateTime()), workOrderId));
        events.addAll(jdbc.query("""
                SELECT f.id, f.note, f.created_by_user_id, u.real_name AS actor_name, f.created_at
                FROM line_renewal_work_order_follow_up f
                LEFT JOIN app_user u ON u.id=f.created_by_user_id
                WHERE f.work_order_id=?
                """, (rs, row) -> new TimelineEvent(rs.getLong("id"), "FOLLOW_UP",
                null, null, rs.getString("note"), rs.getLong("created_by_user_id"),
                rs.getString("actor_name"), rs.getTimestamp("created_at").toLocalDateTime()), workOrderId));
        events.sort(Comparator.comparing(TimelineEvent::happenedAt)
                .thenComparingInt(event -> "STATUS".equals(event.type()) ? 0 : 1)
                .thenComparingLong(TimelineEvent::sourceId));
        return List.copyOf(events);
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static LocalDateTime nullableDateTime(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private record WorkOrderRow(long id, String status, Long lastHandledByUserId, String lastHandledByName,
                                LocalDateTime lastHandledAt, Long effectiveManagerUserId) {}

    public record TimelineEvent(long sourceId, String type, String previousStatus, String newStatus,
                                String note, long actorUserId, String actorName,
                                @com.fasterxml.jackson.annotation.JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
                                LocalDateTime happenedAt) {}

    public record UpdateResult(long workOrderId, String status, Long lastHandledByUserId,
                               String lastHandledByName,
                               @com.fasterxml.jackson.annotation.JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
                               LocalDateTime lastHandledAt, boolean changed,
                               boolean statusChanged, boolean followUpAdded) {
        public UpdateResult(long workOrderId, String status, Long lastHandledByUserId,
                            String lastHandledByName, LocalDateTime lastHandledAt, boolean changed) {
            this(workOrderId, status, lastHandledByUserId, lastHandledByName, lastHandledAt,
                    changed, changed, false);
        }
    }
}
