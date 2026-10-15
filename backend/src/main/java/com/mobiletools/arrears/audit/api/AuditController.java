package com.mobiletools.arrears.audit.api;

import com.mobiletools.arrears.common.api.ApiResponse;
import com.mobiletools.arrears.system.api.UserController.AdminAccess;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/audit-logs")
public class AuditController {
    private final JdbcTemplate jdbc;
    private final AdminAccess users;

    public AuditController(JdbcTemplate jdbc, AdminAccess users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    @GetMapping
    public ApiResponse<AuditPage> list(Authentication authentication,
            @RequestParam(required = false) Long operatorUserId,
            @RequestParam(required = false) String operationType,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) Long targetId,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        users.requireAdmin(authentication, "AUDIT_QUERY", "AUDIT_LOG", null);
        if (page < 1 || pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("page must be positive; pageSize must be 1..100");
        if (from != null && to != null && from.isAfter(to)) throw new IllegalArgumentException("from must not be after to");
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        filter(where, args, "a.operator_user_id", operatorUserId);
        filter(where, args, "a.operation_type", operationType);
        filter(where, args, "a.target_type", targetType);
        filter(where, args, "a.target_id", targetId);
        filter(where, args, "a.result", result);
        if (from != null) { where.append(" AND a.created_at>=?"); args.add(Timestamp.valueOf(from)); }
        if (to != null) { where.append(" AND a.created_at<=?"); args.add(Timestamp.valueOf(to)); }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM operation_audit_log a" + where, Long.class, args.toArray());
        args.add(pageSize);
        args.add(((long) page - 1) * pageSize);
        var items = jdbc.query("""
                SELECT a.id,a.operator_user_id,u.username AS operator_username,a.operation_type,
                       a.target_type,a.target_id,a.result,a.detail,a.created_at
                FROM operation_audit_log a LEFT JOIN app_user u ON u.id=a.operator_user_id
                """ + where + " ORDER BY a.created_at DESC,a.id DESC LIMIT ? OFFSET ?", (rs, row) -> new AuditResponse(
                rs.getLong("id"), rs.getObject("operator_user_id", Long.class), rs.getString("operator_username"),
                rs.getString("operation_type"), rs.getString("target_type"), rs.getObject("target_id", Long.class),
                rs.getString("result"), rs.getString("detail"), rs.getTimestamp("created_at")), args.toArray());
        return ApiResponse.success(new AuditPage(items, total, page, pageSize));
    }

    private static void filter(StringBuilder where, List<Object> args, String column, Object value) {
        if (value != null) { where.append(" AND ").append(column).append("=?"); args.add(value); }
    }

    public record AuditPage(List<AuditResponse> items, long total, int page, int pageSize) {}
    public record AuditResponse(long id, Long operatorUserId, String operatorUsername, String operationType,
                                String targetType, Long targetId, String result, String detail, Timestamp createdAt) {}
}
