package com.mobiletools.arrears.task.api;

import com.mobiletools.arrears.common.api.ApiResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
public class TaskWriteController {
    private static final int TEXT_LIMIT = 1000;
    private final JdbcTemplate jdbcTemplate;

    public TaskWriteController(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    @PostMapping
    @Transactional
    public ApiResponse<Map<String, Object>> create(@RequestBody TaskRequest request, Authentication authentication) {
        requireText(request.title(), "任务标题不能为空", 300);
        if (request.customerId() == null) throw new IllegalArgumentException("必须关联正式客户");
        if (request.deadline() == null) throw new IllegalArgumentException("计划完成日期不能为空");
        boolean admin = isAdmin(authentication);
        var customers = jdbcTemplate.query("SELECT c.id,c.manager_name,u.real_name FROM customer c LEFT JOIN app_user u ON u.id=c.manager_user_id AND u.role='CUSTOMER_MANAGER' AND u.enabled=TRUE WHERE c.id=? AND c.customer_code IS NOT NULL AND c.customer_code<>'' AND c.record_status='ACTIVE'",
                (rs, row) -> new CustomerAssignment(rs.getLong("id"), rs.getString("manager_name"), rs.getString("real_name")), request.customerId());
        if (customers.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "客户不存在");
        if (!admin && !inScope(request.customerId(), authentication)) throw denied();
        CustomerAssignment customer = customers.get(0);
        String owner = admin && request.owner() != null && !request.owner().isBlank() ? request.owner().trim()
                : (!admin && customer.realName() != null ? customer.realName() : customer.managerName());
        requireText(owner, "任务责任人不能为空", 100);
        if (request.serviceId() != null && count("SELECT COUNT(*) FROM business_service WHERE id=? AND customer_id=? AND status='ACTIVE'", request.serviceId(), request.customerId()) == 0)
            throw new IllegalArgumentException("业务专线不存在或不属于该客户");
        String priority = blankDefault(request.priority(), "中");
        if (!List.of("高", "中", "低").contains(priority)) throw new IllegalArgumentException("优先级无效");
        String taskType = blankDefault(request.taskType(), "MANUAL");
        requireText(taskType, "任务类型不能为空", 40);
        String sourceType = blankDefault(request.sourceType(), request.serviceId() == null ? "CUSTOMER" : "BUSINESS_SERVICE");
        Long sourceId = request.sourceId() == null ? (request.serviceId() == null ? request.customerId() : request.serviceId()) : request.sourceId();
        if (sourceId <= 0) throw new IllegalArgumentException("来源编号无效");
        Long serviceId = validateSource(sourceType, sourceId, request.customerId(), request.serviceId());
        String identity = sourceType + ":" + sourceId + ":" + taskType;
        // Customer-only tasks can represent different manual matters; preserve that distinction.
        if ("CUSTOMER".equals(sourceType)) identity += ":" + request.title().trim();
        String key = "MANUAL:" + request.customerId() + ":" + java.util.HexFormat.of().formatHex(
                digest(identity));
        jdbcTemplate.queryForObject("SELECT id FROM customer WHERE id=? FOR UPDATE", Long.class, request.customerId());
        var existing = jdbcTemplate.queryForList("SELECT id FROM task WHERE customer_id=? AND idempotency_key=? AND task_status NOT IN ('已完成','已关闭') ORDER BY id LIMIT 1 FOR UPDATE", Long.class, request.customerId(), key);
        if (!existing.isEmpty()) return ApiResponse.success(Map.of("id", existing.get(0), "reused", true, "message", "已复用现有未结束任务"));
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO task(customer_id,service_id,source_type,source_record_id,idempotency_key,title,owner_name,deadline,task_status,priority,task_type,created_by) VALUES (?,?,?,?,?,?,?,?,'待处理',?,?,(SELECT id FROM app_user WHERE username=?))", new String[]{"id"});
            Object[] values = {request.customerId(), serviceId, sourceType, sourceId, key, request.title().trim(), owner, request.deadline(), priority, taskType, authentication.getName()};
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, keys);
        long id = keys.getKey().longValue();
        audit(id, "TASK_CREATE", authentication, "来源：" + sourceType + ":" + sourceId);
        return ApiResponse.success(Map.of("id", id, "reused", false, "message", "任务已创建"));
    }

    private byte[] digest(String value) {
        try { return java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private Long validateSource(String type, long id, long customer, Long suppliedService) {
        List<Map<String, Object>> rows;
        switch (type) {
            case "CUSTOMER":
                if (id != customer) throw new IllegalArgumentException("来源客户不一致");
                return suppliedService;
            case "BUSINESS_SERVICE":
                rows = jdbcTemplate.queryForList("SELECT id AS service_id FROM business_service WHERE id=? AND customer_id=? AND status='ACTIVE'", id, customer);
                break;
            case "ARREARS_BILL":
                rows = jdbcTemplate.queryForList("SELECT NULL AS service_id FROM arrears_bill WHERE id=? AND customer_id=? AND record_status='ACTIVE'", id, customer);
                break;
            case "CONTRACT_EXPIRY":
                rows = jdbcTemplate.queryForList("SELECT e.service_id FROM contract_expiry e LEFT JOIN business_service s ON s.id=e.service_id WHERE e.id=? AND e.customer_id=? AND (e.service_id IS NULL OR (s.customer_id=e.customer_id AND s.status='ACTIVE'))", id, customer);
                break;
            case "RISK_RESULT":
                rows = jdbcTemplate.queryForList("SELECT service_id FROM risk_result WHERE id=? AND customer_id=? AND status='ACTIVE'", id, customer);
                break;
            case "EXCEPTION":
                rows = jdbcTemplate.queryForList("SELECT NULL AS service_id FROM exception_record e JOIN import_row r ON r.id=e.source_row_id JOIN customer c ON c.customer_code=r.customer_id WHERE e.id=? AND c.id=? AND e.status='OPEN' AND r.row_status NOT IN ('EXCLUDED','INVALID')", id, customer);
                break;
            default: throw new IllegalArgumentException("不支持的任务来源类型");
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("来源不存在、已失效或不属于该客户");
        Object value = rows.get(0).get("service_id");
        Long related = value == null ? null : ((Number) value).longValue();
        if (suppliedService != null && !suppliedService.equals(related)) throw new IllegalArgumentException("来源业务专线不一致");
        return related;
    }

    @PostMapping("/{id}/start")
    @Transactional
    public ApiResponse<Map<String, Object>> start(@PathVariable long id, Authentication authentication) {
        requireAccessibleUnfinished(id, authentication);
        int updated = updateLifecycle(id, authentication, "处理中", "", null, "AND t.task_status IN ('待处理','待核实')");
        if (updated == 0) throw new IllegalArgumentException("任务当前状态不允许开始处理");
        audit(id, "TASK_START", authentication, "处理中");
        return result(id, "处理中");
    }

    @PostMapping("/{id}/complete")
    @Transactional
    public ApiResponse<Map<String, Object>> complete(@PathVariable long id, Authentication authentication) {
        requireAccessibleUnfinished(id, authentication);
        int updated = updateLifecycle(id, authentication, "已完成", "t.completed_at=NOW(),", null, "AND t.task_status NOT IN ('已完成','已关闭')");
        if (updated == 0) throw new IllegalArgumentException("任务当前状态不允许完成");
        audit(id, "TASK_COMPLETE", authentication, "已完成");
        return result(id, "已完成");
    }

    @PostMapping("/{id}/follow-ups")
    @Transactional
    public ApiResponse<Map<String, Object>> followUp(@PathVariable long id, @RequestBody FollowUpRequest request, Authentication authentication) {
        String note = normalize(request.note());
        String nextAction = normalize(request.nextAction());
        if (note.isEmpty() && nextAction.isEmpty()) throw new IllegalArgumentException("跟进内容和下一步不能同时为空");
        if (note.length() > TEXT_LIMIT || nextAction.length() > TEXT_LIMIT) throw new IllegalArgumentException("跟进内容和下一步不能超过1000个字符");
        requireAccessibleUnfinished(id, authentication);
        jdbcTemplate.update("INSERT INTO follow_up(task_id, note, next_action, created_by, created_by_user_id) VALUES (?, ?, ?, ?, (SELECT id FROM app_user WHERE username=?))",
                id, note, nextAction.isEmpty() ? null : nextAction, authentication.getName(), authentication.getName());
        audit(id, "TASK_FOLLOW_UP", authentication, "追加跟进");
        return result(id, "跟进已保存");
    }

    @GetMapping("/{id}")
    public ApiResponse<TaskDetail> detail(@PathVariable long id, Authentication authentication) {
        String access = accessibleClause(authentication);
        Object[] args = isAdmin(authentication) ? new Object[]{id} : new Object[]{id, authentication.getName()};
        var tasks = jdbcTemplate.query("SELECT t.id,t.title,t.owner_name,t.deadline,t.task_status,t.priority,t.source_type,t.source_record_id,t.task_type,t.close_reason,c.customer_code,c.customer_name FROM task t JOIN customer c ON c.id=t.customer_id WHERE t.id=? AND c.customer_code IS NOT NULL AND c.record_status='ACTIVE'" + access,
                (rs, row) -> {
                    LocalDate deadline = rs.getObject("deadline", LocalDate.class);
                    String status = rs.getString("task_status");
                    long days = deadline.isBefore(LocalDate.now()) && !"已完成".equals(status) && !"已关闭".equals(status)
                            ? java.time.temporal.ChronoUnit.DAYS.between(deadline, LocalDate.now()) : 0;
                    List<FollowUpDetail> followUps = jdbcTemplate.query("SELECT id,note,next_action,created_by,created_at FROM follow_up WHERE task_id=? ORDER BY created_at,id",
                            (fr, fn) -> new FollowUpDetail(fr.getLong("id"), fr.getString("note"), fr.getString("next_action"), fr.getString("created_by"), fr.getTimestamp("created_at").toLocalDateTime()), id);
                    return new TaskDetail(rs.getLong("id"), rs.getString("title"), rs.getString("owner_name"), deadline, status, rs.getString("priority"),
                            rs.getString("customer_code"), rs.getString("customer_name"), days > 0, days, followUps,
                            rs.getString("source_type"), (Long) rs.getObject("source_record_id"), rs.getString("task_type"), rs.getString("close_reason"));
                }, args);
        if (tasks.isEmpty()) throw denied();
        return ApiResponse.success(tasks.get(0));
    }

    @PostMapping("/{id}/close")
    @Transactional
    public ApiResponse<Map<String, Object>> close(@PathVariable long id, @RequestBody CloseRequest request, Authentication authentication) {
        String reason = normalize(request.reason());
        requireText(reason, "关闭原因不能为空", TEXT_LIMIT);
        requireAccessibleUnfinished(id, authentication);
        int updated = updateLifecycle(id, authentication, "已关闭", "t.closed_at=NOW(),t.close_reason=?,", reason, "AND t.task_status NOT IN ('已完成','已关闭')");
        if (updated == 0) throw new IllegalArgumentException("任务当前状态不允许关闭");
        audit(id, "TASK_CLOSE", authentication, "已关闭");
        return result(id, "已关闭");
    }

    private int updateLifecycle(long id, Authentication auth, String status, String setPrefix,
                                Object extraValue, String stateClause) {
        boolean admin = isAdmin(auth);
        String access = admin ? "" : " AND EXISTS (SELECT 1 FROM user_customer_scope s WHERE s.customer_id=t.customer_id AND s.user_id=(SELECT id FROM app_user WHERE username=?))";
        String sql = "UPDATE task t JOIN customer c ON c.id=t.customer_id SET " + setPrefix
                + "t.task_status=?,t.updated_by=(SELECT id FROM app_user WHERE username=?) WHERE t.id=?"
                + " AND c.customer_code IS NOT NULL AND c.customer_code<>'' AND c.record_status='ACTIVE' " + stateClause + access;
        if (extraValue != null) {
            return admin ? jdbcTemplate.update(sql, extraValue, status, auth.getName(), id)
                    : jdbcTemplate.update(sql, extraValue, status, auth.getName(), id, auth.getName());
        }
        return admin ? jdbcTemplate.update(sql, status, auth.getName(), id)
                : jdbcTemplate.update(sql, status, auth.getName(), id, auth.getName());
    }

    private void requireAccessibleUnfinished(long id, Authentication auth) {
        String clause = accessibleClause(auth);
        Object[] args = isAdmin(auth) ? new Object[]{id} : new Object[]{id, auth.getName()};
        // Lock the task so a concurrent close cannot race with appending a follow-up.
        var states = jdbcTemplate.query("SELECT t.task_status FROM task t JOIN customer c ON c.id=t.customer_id WHERE t.id=? AND c.customer_code IS NOT NULL AND c.customer_code<>'' AND c.record_status='ACTIVE'" + clause + " FOR UPDATE",
                (rs, row) -> rs.getString("task_status"), args);
        if (states.isEmpty()) throw denied();
        if (!List.of("待处理", "处理中", "待核实").contains(states.get(0)))
            throw new IllegalArgumentException("任务当前状态不允许修改");
    }

    private String accessibleClause(Authentication auth) {
        return " AND c.customer_code<>''" + (isAdmin(auth) ? "" : " AND EXISTS (SELECT 1 FROM user_customer_scope s WHERE s.customer_id=t.customer_id AND s.user_id=(SELECT id FROM app_user WHERE username=?))");
    }
    private boolean inScope(Long customerId, Authentication auth) {
        return count("SELECT COUNT(*) FROM user_customer_scope WHERE customer_id=? AND user_id=(SELECT id FROM app_user WHERE username=?)", customerId, auth.getName()) > 0;
    }
    private int count(String sql, Object... args) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }
    private void audit(long id, String action, Authentication auth, String detail) {
        jdbcTemplate.update("INSERT INTO operation_audit_log(operator_user_id,operation_type,target_type,target_id,result,detail) VALUES ((SELECT id FROM app_user WHERE username=?), ?, 'TASK', ?, 'SUCCESS', ?)",
                auth.getName(), action, id, detail);
    }
    private ApiResponse<Map<String, Object>> result(long id, String status) { return ApiResponse.success(Map.of("id", id, "status", status)); }
    private boolean isAdmin(Authentication auth) { return auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())); }
    private AccessDeniedException denied() { return new AccessDeniedException("任务不存在、客户不在当前账号范围内或任务已结束"); }
    private String normalize(String value) { return value == null ? "" : value.trim(); }
    private void requireText(String value, String message, int max) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) throw new IllegalArgumentException(message);
        if (normalized.length() > max) throw new IllegalArgumentException(message + "且不能超过" + max + "个字符");
    }
    private String blankDefault(String value, String fallback) { String normalized = normalize(value); return normalized.isEmpty() ? fallback : normalized; }

    private record CustomerAssignment(long id, String managerName, String realName) {}
    public record TaskRequest(String title, String owner, LocalDate deadline, String priority, Long customerId, Long serviceId, String sourceType, Long sourceId, String taskType) {}
    public record FollowUpRequest(String note, String nextAction, String createdBy) {}
    public record CloseRequest(String reason) {}
    public record TaskDetail(Long id, String title, String owner, LocalDate deadline, String status, String priority,
                             String customerCode, String customerName, boolean overdue, long overdueDays, List<FollowUpDetail> followUps,
                             String sourceType, Long sourceId, String taskType, String closeReason) {}
    public record FollowUpDetail(Long id, String note, String nextAction, String createdBy, LocalDateTime createdAt) {}
}
